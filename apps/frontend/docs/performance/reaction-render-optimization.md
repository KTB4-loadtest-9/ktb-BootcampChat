# 실시간 채팅 메시지 재렌더링 최적화

## 1. 개요

- 대상: BootcampChat Next.js/React 프론트엔드
- 영역: Socket.IO 실시간 채팅 메시지 렌더링
- 개선 커밋: `c3296c4 perf: stabilize reaction callbacks across message updates`
- 측정 도구: React Profiler, Playwright 기반 자동 캡처 스크립트
- 작성일: 2026-08-12

실시간 채팅에서 메시지가 증가할수록 새 메시지 한 건을 처리할 때 기존 메시지 컴포넌트까지 다시 렌더링되는 현상을 분석했다. React Profiler 기록과 실제 코드 흐름을 함께 확인한 결과, `messages` 변경으로 리액션 콜백의 참조가 바뀌고 이 변경이 메시지 목록 전체로 전파되는 구조가 확인됐다.

최신 메시지 상태를 ref로 읽고 리액션 콜백의 참조를 안정화한 결과, 메시지 40개 상태에서 새 메시지 한 건을 전송할 때 `UserMessage`의 채팅 commit당 평균 렌더 횟수가 31.25개에서 1개로 감소했다.

## 2. 문제 상황

메시지 수가 늘어난 채팅방에서 새 메시지를 수신하면 다음 경로로 렌더링이 전파될 가능성이 있었다.

```text
Socket.IO message 이벤트
→ messages 상태 변경
→ ChatRoomView 렌더
→ ChatMessages 렌더
→ UserMessage × N 렌더
→ MessageActions / ReadStatus / CustomAvatar 렌더
```

최적화 전 React Profiler 기록에서는 사용자 메시지가 약 36개인 구간에서 기존 `UserMessage`가 모두 렌더되는 commit이 확인됐다. 일반적인 채팅 commit은 약 17~19ms였으며, 같은 기록에서 81.4ms와 122.6ms의 긴 commit도 발생했다.

## 3. 원인 분석

관련 코드는 [`useReactionHandling.js`](../../features/chat/room/useReactionHandling.js)에 있었다.

최적화 전에는 리액션 추가·삭제 콜백이 `messages` 배열을 직접 참조하고 있었다.

```js
const handleReactionAdd = useCallback(async (messageId, reaction) => {
  // ...
  const previousReactions = messages.find(
    message => message._id === messageId
  )?.reactions || {};
}, [currentUser, messages, setMessages]);
```

새 메시지가 추가될 때마다 `messages` 배열의 참조가 변경되므로 다음 연쇄가 발생했다.

```text
messages 변경
→ handleReactionAdd / handleReactionRemove 재생성
→ ChatMessages에 전달되는 reaction callback 변경
→ renderMessage callback 변경
→ 기존 UserMessage의 props 변경
→ React.memo 경계 무효화
→ 기존 메시지 N개 재렌더
```

핵심은 메시지 내용 자체가 바뀐 것이 아니라, 메시지 변경마다 하위 컴포넌트로 전달되는 함수 참조가 바뀌었다는 점이다.

## 4. 개선 내용

### 4.1 최신 메시지 상태를 ref로 관리

```js
const messagesRef = useRef(messages);

useEffect(() => {
  messagesRef.current = messages;
}, [messages]);
```

콜백은 재생성하지 않으면서도 실행 시점의 최신 메시지 상태를 읽을 수 있도록 했다.

### 4.2 콜백 dependency 안정화

```js
const currentUserId = currentUser?.id;

const handleReactionAdd = useCallback(async (messageId, reaction) => {
  const previousReactions = messagesRef.current.find(
    message => message._id === messageId
  )?.reactions || {};

  // 낙관적 업데이트 및 Socket.IO 요청
}, [currentUserId, setMessages]);
```

- `messages`를 dependency에서 제거했다.
- `currentUser` 객체 전체 대신 실제로 필요한 `currentUserId`를 사용했다.
- 새 메시지가 추가돼도 리액션 콜백 참조가 유지되도록 했다.

### 4.3 실패 롤백 동작 보존

성능 개선 과정에서 낙관적 업데이트의 실패 롤백이 최신 메시지 상태와 충돌하지 않도록 요청 시작 시점의 리액션 상태를 별도로 보존했다.

```js
let previousReactions;
let optimisticUpdateStarted = false;

previousReactions = messagesRef.current.find(
  message => message._id === messageId
)?.reactions || {};

// 낙관적 업데이트 성공 후
optimisticUpdateStarted = true;

// 요청 실패 시
if (optimisticUpdateStarted) {
  setMessages(prevMessages =>
    prevMessages.map(message =>
      message._id === messageId
        ? { ...message, reactions: previousReactions }
        : message
    )
  );
}
```

Socket 연결 자체가 불가능해 낙관적 업데이트가 시작되지 않은 경우에는 불필요한 롤백 state update도 실행하지 않는다.

## 5. 자동 측정 환경

수동 React Profiler 기록만으로는 실행마다 동작과 메시지 수가 달라질 수 있어 자동 캡처 도구를 추가했다.

관련 파일:

- [`capture-chat-render-profile.mjs`](../../scripts/capture-chat-render-profile.mjs): 채팅방 준비 및 Profiler JSON 자동 캡처
- [`compare-react-profiler.mjs`](../../scripts/compare-react-profiler.mjs): Before/After 분석 보고서 생성
- [`chatRenderProfiler.js`](../../lib/performance/chatRenderProfiler.js): 선택 컴포넌트 렌더 수집기

자동 캡처 흐름은 다음과 같다.

```text
로컬 백엔드 health 확인
→ Socket.IO 포트 확인
→ 3100 포트에 격리된 계측용 프론트 실행
→ 임시 사용자 생성 및 로그인
→ 임시 채팅방 생성
→ 메시지 40개 준비
→ Profiler collector 초기화
→ 41번째 메시지 한 건 전송
→ 렌더 안정화 대기
→ Profiler JSON 저장
→ 브라우저와 임시 프론트 종료
```

`--reaction-handler-ref` 옵션을 사용하면 작업 트리를 되돌리지 않고 격리된 프론트 복사본에만 특정 Git ref의 `useReactionHandling.js`를 적용할 수 있다. 이를 통해 같은 계측 코드와 같은 메시지 조건으로 최적화 전후를 비교했다.

## 6. 재현 방법

### 사전 조건

- 백엔드 health: `http://127.0.0.1:5001/api/health`
- Socket.IO: `127.0.0.1:5002`
- 계측용 프론트 포트 `3100`이 비어 있어야 함
- 로컬 백엔드 DB에 임시 사용자·방·메시지가 생성될 수 있음
- 일반 E2E 실행과 성능 캡처는 동시에 실행하지 않음

### 최적화 전 캡처

```bash
cd apps/frontend

pnpm perf:capture-chat -- \
  --seed-messages 40 \
  --reaction-handler-ref 'c3296c4^' \
  --output /tmp/ktb-chat-render-profile-before-40-auto.json
```

### 최적화 후 캡처

```bash
pnpm perf:capture-chat -- \
  --seed-messages 40 \
  --output /tmp/ktb-chat-render-profile-after-40-auto.json
```

### 비교 보고서 생성

```bash
pnpm perf:profiler -- \
  --before /tmp/ktb-chat-render-profile-before-40-auto.json \
  --after /tmp/ktb-chat-render-profile-after-40-auto.json \
  --output /tmp/ktb-chat-render-profile-before-after-40-auto.md
```

## 7. 측정 조건

| 항목 | 조건 |
| --- | --- |
| 브라우저 | Chromium 149.0.7827.55 |
| 프론트 | 격리된 로컬 Next.js 개발 서버 |
| 프론트 주소 | `http://127.0.0.1:3100` |
| 백엔드 | `http://127.0.0.1:5001` |
| Socket.IO | `127.0.0.1:5002` |
| 초기 메시지 | 40개 |
| 측정 동작 | 41번째 메시지 한 건 전송 |
| 최적화 전 | `c3296c4^`의 `useReactionHandling.js` |
| 최적화 후 | 현재 작업 트리의 `useReactionHandling.js` |
| 계측 컴포넌트 | `ChatRoomPage`, `ChatMessages`, `UserMessage` |

## 8. 측정 결과

| 지표 | 최적화 전 | 최적화 후 | 개선율 |
| --- | ---: | ---: | ---: |
| 채팅 commit P50 | 17.4ms | 5.9ms | 66.09% |
| 채팅 commit P95 | 20.2ms | 9.2ms | 54.46% |
| 채팅 commit P99 | 20.2ms | 9.2ms | 54.46% |
| 채팅 commit 최대 | 33ms | 17.3ms | 47.58% |
| `UserMessage`/채팅 commit | 31.25개 | 1개 | 96.8% |
| Profiler raw entry | 134개 | 13개 | 약 90% 감소 |

최적화 전에는 새 메시지 한 건을 처리하는 과정에서 기존 `UserMessage`가 commit당 평균 31.25개 렌더됐다. 최적화 후에는 새로 추가된 메시지를 중심으로 commit당 1개만 렌더됐다.

```text
Before
messages 변경
→ reaction callback 변경
→ 기존 UserMessage 약 31개 재렌더

After
messages 변경
→ reaction callback 참조 유지
→ 새 UserMessage 1개 렌더
```

## 9. 검증 결과

- 프론트 단위 테스트: 30개 파일, 121개 테스트 통과
- ESLint: 오류 0개
- Next.js production build: 성공
- 자동 Profiler 스모크 캡처: 성공
- 메시지 40개 Before/After 자동 캡처: 성공
- 기존 `e2e/` 테스트·액션·시나리오 파일: 변경 없음

전체 E2E 통과 여부와 렌더링 성능 개선은 별개의 검증 항목이다. 이 문서에서는 전체 E2E 통과를 주장하지 않고, 프론트 단위 테스트·빌드와 격리된 채팅 렌더 시나리오의 성공만 검증 결과로 기록한다.

## 10. 결과 해석 시 주의사항

현재 표는 동일한 자동 시나리오로 실행한 1회 측정 결과다. 코드 변경으로 기존 메시지 재렌더가 제거된 사실은 렌더 횟수로 명확히 확인됐지만, commit 시간은 브라우저와 로컬 시스템 상태에 따라 변동될 수 있다.

최종 성능 수치로 사용하려면 다음 조건으로 최소 3회 반복한 뒤 중앙값과 범위를 함께 기록해야 한다.

- 같은 브라우저 버전
- 같은 빌드 모드
- 같은 메시지 수
- 같은 측정 동작
- 같은 로컬 백엔드 환경
- 다른 부하 작업이 없는 상태

이 개선은 브라우저의 렌더링 비용과 메시지 처리 응답성을 개선한다. 별도의 VUser·RPS 부하 테스트 없이 서버의 최대 RPS나 전체 서비스의 break point가 개선됐다고 주장하지 않는다.

## 11. 배운 점

1. `React.memo`가 적용돼 있어도 함수·객체 props의 참조가 계속 바뀌면 기존 하위 컴포넌트가 다시 렌더될 수 있다.
2. memoization을 먼저 추가하기보다 state 변경 지점과 props 전달 경로를 추적해야 정확한 원인을 찾을 수 있다.
3. 최신 state가 필요한 콜백은 무조건 state를 dependency로 넣기보다, ref와 함수형 state update가 동시성 및 롤백 요구사항에 맞는지 검토할 수 있다.
4. 성능 개선은 수동 체감이 아니라 같은 동작과 데이터 조건의 Before/After 측정으로 검증해야 한다.
5. 성능 지표와 기능 정확성 검증은 분리하고, 실제로 확인한 범위만 결과로 주장해야 한다.

## 12. 이력서 표현 예시

### 렌더링 개선 중심

> React/Socket.IO 채팅의 콜백 참조 불안정으로 발생한 기존 메시지 N개 재렌더를 추적하고, 상태 참조 구조를 개선해 메시지당 `UserMessage` 재렌더를 평균 31.25개에서 1개로 96.8% 감소시켰습니다.

### 측정 자동화 중심

> React Profiler와 Playwright 기반의 격리형 Before/After 측정 도구를 구축하고, 동일한 40개 메시지 조건에서 채팅 commit P95를 20.2ms에서 9.2ms로 단축했습니다.

### 문제 해결 과정 중심

> Socket.IO 이벤트부터 React state와 컴포넌트 렌더 경로를 추적해 메시지 증가에 비례하던 재렌더 병목을 식별하고, 기능 회귀 없이 콜백 참조를 안정화했습니다.

## 13. 면접 답변 순서

1. 메시지가 늘어날수록 새 메시지 처리 비용이 증가하는 문제를 발견했다.
2. React Profiler를 통해 새 메시지 한 건에 기존 메시지 컴포넌트까지 렌더되는 것을 확인했다.
3. `messages` 변경이 리액션 콜백을 재생성하고 `React.memo` 경계를 무효화하는 흐름을 코드로 추적했다.
4. 최신 메시지는 ref로 읽고 콜백 dependency를 primitive 값 중심으로 안정화했다.
5. 낙관적 업데이트와 실패 롤백 동작을 유지했다.
6. 이전 Git ref와 현재 코드를 같은 40개 메시지 조건으로 자동 측정했다.
7. `UserMessage` 재렌더가 commit당 평균 31.25개에서 1개로 감소한 것을 확인했다.
8. 단위 테스트 121개와 production build로 변경 범위의 기능 회귀를 확인했다.

핵심은 단순히 `useCallback`이나 `useRef`를 사용했다는 사실이 아니라, 가설 수립, 코드 흐름 추적, 계측, 최소 변경, 동일 조건 검증의 순서로 문제를 해결했다는 점이다.
