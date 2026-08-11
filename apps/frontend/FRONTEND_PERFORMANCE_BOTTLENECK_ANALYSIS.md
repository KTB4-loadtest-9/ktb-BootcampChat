# 프론트엔드 실시간 채팅 성능 병목 분석 보고서

- 분석 대상: `apps/frontend`
- 분석 방식: 정적 코드 추적 + 최적화 전 React Profiler export 분석
- 기준 시점: 첫 번째 렌더 최적화 적용 전
- 최초 분석 범위: 애플리케이션 및 테스트 코드를 수정하지 않고 분석만 수행

> 아래 병목 분석과 Profiler 수치는 첫 번째 최적화를 적용하기 전 상태를 기준으로 한다. 이후 코드와 비교할 때 이 값을 Before 기준값으로 사용한다.

## 1. 요약

현재 실시간 메시지 처리 흐름은 다음과 같다. 별도의 `MessageList` 컴포넌트는 없으며 `ChatMessages`가 메시지 목록 역할을 한다.

```text
Socket.IO message
→ onMessage
→ setMessages()
→ useReducer state 변경
→ ChatRoomView 재렌더
   ├─ ChatRoomInfo 재렌더
   ├─ ChatMessages 재렌더
   │   ├─ 전체 메시지 정렬
   │   ├─ 전체 메시지 map
   │   └─ UserMessage/FileMessage/SystemMessage × N 재렌더 가능
   │       ├─ CustomAvatar
   │       ├─ ReadStatus
   │       ├─ MessageActions
   │       └─ MessageContent
   └─ ChatInput 재렌더
→ useAutoScroll effect
```

가장 중요한 점은 기존 메시지 행에 전달되는 리액션 콜백이 메시지가 추가될 때마다 새로 만들어진다는 것이다. 이 때문에 `UserMessage`, `FileMessage`, `SystemMessage`에 적용된 `React.memo`가 기존 메시지 행의 재렌더를 막지 못할 가능성이 높다.

또한 메시지마다 `ReadStatus`, `IntersectionObserver`, persistent `CustomAvatar`가 만들어질 수 있고, 불러온 메시지를 DOM에서 제거하지 않기 때문에 메시지 수와 함께 DOM·listener·observer·메모리 사용량이 선형으로 증가한다.

### 1.1 최적화 전 React Profiler 기준값

원본 기록:

- 파일: 로컬에서 수집한 최적화 전 React Profiler export
- 기록 일자: 2026-08-12
- React Profiler export version: 5
- 기록 구간: 약 22.7초
- 전체 React commit: 616개
- 기록 범위: 채팅방 렌더와 방 목록 이동이 함께 포함된 장시간 기록

전체 commit과 채팅 관련 commit을 분리한 결과는 다음과 같다.

| 구분 | Commit 수 | 평균 | P50 | P95 | P99 | 최대 | 16ms 초과 | 50ms 초과 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 전체 | 616 | 7.50ms | 2.2ms | 35.3ms | 81.4ms | 122.6ms | 88 | 20 |
| 채팅 렌더 포함 | 126 | 26.06ms | 17.1ms | 81.4ms | 89.4ms | 122.6ms | 77 | 15 |
| 채팅 렌더 미포함 | 490 | 2.73ms | 0.7ms | 5.5ms | - | 73.7ms | 11 | 5 |

채팅 관련 126개 commit에서 확인된 실제 렌더 범위:

- `ChatRoomView`: 126/126 commit에서 렌더
- `ChatRoomInfo`: 126/126 commit에서 렌더
- `ChatInput`: 126/126 commit에서 렌더
- 기존 `UserMessage`: 126/126 commit에서 현재 존재하는 행 전체가 렌더
- `ChatHeader`: 0/126 commit으로 memo 경계가 정상적으로 작동

메시지가 증가할 때 한 번의 넓은 채팅 commit에 포함된 Fiber 수도 함께 증가했다.

| 사용자 메시지 수 | 함께 존재한 시스템 메시지 | 렌더된 Fiber 수 |
| ---: | ---: | ---: |
| 10 | 17 | 633~678 |
| 20 | 17 | 1,104 |
| 30 | 17 | 1,529 |
| 36 | 17 | 1,887~1,905 |

사용자 메시지 36개 구간의 일반 commit은 약 17~19ms였고, 같은 범위에서 81.4ms와 122.6ms의 긴 commit도 발생했다. 마지막 최악 commit에서는 기존 `UserMessage` 36개가 모두 렌더됐으며 `UserMessage`, `MessageActions`, `ReadStatus`, `CustomAvatar` 하위 트리가 함께 실행됐다.

따라서 다음 경로는 정적 코드상의 가능성이 아니라 런타임에서도 확인됐다.

```text
messages 변경
→ ChatRoomView 렌더
→ ChatMessages 렌더
→ 기존 UserMessage N개 렌더
→ MessageActions / ReadStatus / Avatar 하위 렌더
```

다만 이 export는 React DevTools의 `Record why each component rendered while profiling` 정보가 포함되지 않아 모든 `changeDescriptions`가 `null`이다. 기존 메시지 전체가 렌더됐다는 사실은 확인됐지만, 변경된 prop의 이름은 코드 dependency 추적으로 판정했다.

첫 번째 최적화 이후 동일 조건에서 확인할 기준은 다음과 같다.

- 새 메시지 한 건 수신 시 기존 `UserMessage` 본체의 render count가 0인지 확인
- 새로 추가된 `UserMessage`만 1회 렌더되는지 확인
- `Memo(UserMessage)`에서 기존 행의 하위 트리가 중단되는지 확인
- 동일 메시지 수에서 commit duration과 렌더 Fiber 수를 Before 값과 비교
- `ChatRoomInfo`, `ChatInput`의 부모 연쇄 재렌더는 별도 병목으로 분리

저장된 Profiler JSON은 다음 명령으로 자동 비교한다.

```bash
cd apps/frontend
pnpm perf:capture-chat -- \
  --seed-messages 40 \
  --reaction-handler-ref 'c3296c4^' \
  --output "/tmp/ktb-chat-render-profile-before.json"

pnpm perf:capture-chat -- \
  --seed-messages 40 \
  --output "/tmp/ktb-chat-render-profile-after.json"

pnpm perf:profiler -- \
  --before "/tmp/ktb-chat-render-profile-before.json" \
  --after "/tmp/ktb-chat-render-profile-after.json" \
  --output "/path/to/react-profiler-comparison.md"
```

`perf:capture-chat`은 로컬 백엔드의 health와 Socket.IO 포트를 확인하고, 3100 포트에 격리된 계측용 프론트를 실행한 뒤 임시 사용자와 방을 만든다. 지정한 수만큼 메시지를 준비하고 다음 메시지 한 건의 React 렌더만 JSON으로 저장한다. `--reaction-handler-ref`를 지정한 실행은 격리 복사본에만 해당 Git ref의 `useReactionHandling`을 넣으므로 작업 트리를 되돌리지 않고 최적화 전 조건을 재현한다. 기존 `e2e/` 테스트 파일은 실행하거나 수정하지 않는다.

`perf:profiler`는 commit duration, 렌더 Fiber 수, `UserMessage`, `MessageActions`, `ReadStatus`, `CustomAvatar`의 채팅 commit당 렌더 횟수와 개선율을 같은 계산식으로 출력한다. 자동 캡처는 지정한 컴포넌트만 계측하므로 전체 Fiber 수는 React DevTools 수동 export와 직접 비교하지 않는다.

## 2. 가설 검증 결과

| 가설                                                | 판정                                        | 핵심 근거                                                                                                                   |
| --------------------------------------------------- | ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 1. 새 메시지 수신 시 넓은 범위 재렌더               | 확인됨                                      | 메시지 변경으로 ChatRoomView, ChatRoomInfo, ChatInput, 전체 메시지 행까지 재렌더 가능                                       |
| 2. Socket.IO listener 중복 또는 과도한 state update | 부분 확인                                   | 정상 메시지 렌더에서 listener가 계속 누적되지는 않지만, 초기 메시지 응답은 두 경로에서 처리되고 연결 listener 역할이 중첩됨 |
| 3. Zustand 또는 전역 상태 구독 범위가 넓음          | Zustand는 해당 없음, 로컬 state는 부분 확인 | Zustand를 사용하지 않지만 단일 `useReducer` 때문에 채팅방 화면의 로컬 재렌더 범위가 넓음                                    |
| 4. 메시지 목록이 커질수록 DOM 비용 증가             | 확인됨                                      | 30개 단위 pagination은 있으나 기존 메시지를 제거하지 않고 모두 DOM에 유지하며 virtualization 없음                           |
| 5. 자동 스크롤과 DOM 조작이 과도함                  | 확인됨                                      | 메시지 추가 때 `scrollHeight`를 읽고 smooth scroll과 timer를 실행할 수 있음                                                 |
| 6. 렌더 경로에 전체 메시지 계산 포함                | 확인됨                                      | 전체 정렬, map, 중복 검사, 읽음 사용자 계산이 메시지 수에 비례해 증가                                                       |

## 3. Critical

### 3.1 새 메시지 한 건이 기존 메시지 N개 재렌더로 확장됨

관련 파일:

- `features/chat/room/roomEventHandlers.js:114`
- `features/chat/room/useChatRoomState.js:54`
- `features/chat/room/useReactionHandling.js:8`
- `features/chat/room/ChatRoomView.js:134`
- `components/ChatMessages.js:66`
- `components/ChatMessages.js:145`

현재 동작:

1. `onMessage`가 `setMessages()`를 호출한다.
2. `messages` 배열 참조가 바뀐다.
3. `useReactionHandling`의 `handleReactionAdd`, `handleReactionRemove`는 `messages`를 dependency로 가지므로 두 콜백도 새로 생성된다.
4. 새 콜백이 `ChatMessages`를 거쳐 모든 메시지 행에 전달된다.
5. `UserMessage`, `FileMessage`, `SystemMessage`가 `React.memo`여도 콜백 prop이 달라졌다고 판단한다.

`SystemMessage`는 리액션 콜백을 사용하지 않지만 `commonProps`로 전달받기 때문에 함께 영향을 받는다.

메시지 한 건당 발생 가능한 비용은 다음과 같다.

```text
중복 확인                   O(N)
새 배열 생성                O(N)
ChatMessages 전체 정렬       O(N log N)
전체 메시지 map             O(N)
기존 메시지 컴포넌트 재렌더  O(N)
```

메시지 수가 N이고 K건이 연속 수신되면 `K × N` 이상의 렌더 비용이 발생할 수 있으며, N도 계속 증가한다.

- 판정: 코드상 확인된 사실
- 런타임 확인: 메시지 30/300/1,000개에서 한 건 수신 후 React Profiler의 render count와 commit duration 비교

### 3.2 읽음 처리가 메시지 수와 사용자 수를 동시에 따라 증가함

관련 파일:

- `components/ReadStatus.js:19`
- `components/ReadStatus.js:57`
- `components/ReadStatus.js:87`
- `features/chat/room/roomEventHandlers.js:37`
- `features/chat/room/roomEventHandlers.js:110`

각 일반 메시지와 파일 메시지에는 `ReadStatus`가 존재한다. 각 `ReadStatus`는 다음 작업을 수행한다.

- `participants.filter()` 안에서 `readers.some()`을 실행해 읽지 않은 참여자를 계산
- 읽지 않은 메시지마다 `IntersectionObserver` 생성 가능
- 메시지가 화면에 들어오면 메시지별로 `markMessagesAsRead([messageId])` 발행
- `messagesRead` 수신 시 전체 메시지 배열을 `map`
- 대상 판별 시 메시지마다 `messageIds.includes()` 실행

비용은 다음과 같이 확장될 수 있다.

```text
메시지 M개 × 참여자 P명 × reader 비교
+
화면에 보이는 메시지 수만큼 개별 read 이벤트
+
messagesRead 이벤트마다 전체 메시지 M개 map
```

`messagesRead`로 메시지 배열 참조가 바뀌면 리액션 콜백도 다시 생성되어 전체 메시지 행 재렌더로 이어질 수 있다.

서버가 읽음 이벤트를 몇 명에게 fan-out하는지는 프론트 코드만으로 확정할 수 없다. 다만 클라이언트가 메시지별 읽음 이벤트를 전송하고, 수신 이벤트마다 전체 배열을 순회하는 구조는 확인됐다.

- 판정: 프론트 비용은 확인된 사실, 서버 fan-out 배수는 런타임 확인 필요
- 런타임 확인: `message`, `markMessagesAsRead`, `messagesRead`, reducer dispatch, React commit 횟수 동시 기록

### 3.3 메시지 DOM, Observer, 전역 listener가 선형 증가함

관련 파일:

- `components/ChatMessages.js:145`
- `components/UserMessage.js:39`
- `components/CustomAvatar.js:68`
- `components/FileMessage.js:175`

이전 메시지는 30개씩 불러오지만, 한번 불러온 메시지는 배열과 DOM에서 제거되지 않는다. Virtualization이나 메시지 상한도 없다.

따라서 100/500/1,000/5,000개의 메시지를 불러오면 모두 React 트리와 DOM에 남는다.

각 텍스트·파일 메시지의 `CustomAvatar`는 `persistent` 모드이므로 메시지마다 다음 전역 listener를 등록한다.

```js
window.addEventListener('userProfileUpdate', handleProfileUpdate);
```

메시지 1,000개에 아바타가 1,000개라면 `userProfileUpdate` listener도 최대 그 수준까지 증가할 수 있다. 읽지 않은 메시지에는 메시지별 `IntersectionObserver`도 존재할 수 있다.

이미지의 `loading="lazy"`와 메시지 wrapper의 `contentVisibility: auto`는 화면 밖 layout/paint 부담 일부를 줄인다. 하지만 DOM, React 인스턴스, hook state, listener, observer 자체는 제거하지 않는다.

- 판정: DOM과 listener 증가 구조는 코드상 확인된 사실
- 런타임 확인: 메시지 30/300/1,000개에서 DOM Nodes, Event Listeners, JS Heap, Observer 수 비교

## 4. High

### 4.1 초기 메시지 응답이 두 경로에서 중복 처리됨

관련 파일:

- `features/chat/room/useRoomHandling.js:80`
- `features/chat/room/useRoomHandling.js:248`
- `features/chat/room/useRoomHandling.js:258`
- `lib/socket/socketClient.js:130`
- `features/chat/room/roomEventHandlers.js:85`
- `features/chat/messages/useMessageList.js:1`

`previousMessagesLoaded`는 다음 두 경로에서 동시에 처리된다.

```text
상시 room listener
→ onPreviousMessagesLoaded
→ processMessages()

일회성 fetchPreviousMessagesAndWait listener
→ Promise resolve
→ loadInitialMessages
→ processMessages()
```

`processLoadedRoomMessages` 내부에서도 동일 메시지 묶음에 대해 `deriveUniqueSortedMessages()`를 두 번 호출한다.

초기 응답 한 번에 다음 작업이 발생한다.

- 중복 제거용 derive/sort
- 실제 `setMessages`용 derive/sort
- 상시 listener와 일회성 listener에서 위 과정 반복
- 이후 `ChatMessages`에서 전체 정렬 반복

React batching으로 실제 commit 수는 줄 수 있지만 배열 생성과 정렬 계산 자체는 이미 수행된다.

- 판정: 코드상 확인된 사실
- 런타임 확인: `previousMessagesLoaded` 한 건당 `processMessages`, `deriveUniqueSortedMessages`, React commit 호출 횟수 측정

### 4.2 참여자 업데이트가 전체 메시지와 읽음 계산을 무효화함

관련 파일:

- `features/chat/room/roomEventHandlers.js:106`
- `components/ChatMessages.js:78`
- `components/ChatRoomInfo.js:114`

`participantsUpdate`가 들어오면 새로운 `room` 객체와 `participants` 배열이 만들어진다. `room` 전체가 모든 메시지 행에 전달되므로 다음 경로로 확장된다.

```text
participantsUpdate
→ setRoom(new object)
→ ChatRoomView
→ ChatRoomInfo
→ ChatMessages
→ MessageItem × N
→ ReadStatus × N
```

`ReadStatus`의 participants dependency도 바뀌므로 모든 메시지에서 참여자/reader 비교가 다시 실행되고, 읽지 않은 메시지의 Observer가 정리 후 다시 등록될 수 있다.

`ChatRoomInfo`는 접혀 있는 참여자 패널의 `participants.map()`도 render 단계에서 실행한다.

- 판정: 재렌더 경로는 확인됨, 서버의 `participantsUpdate` 발생 빈도는 런타임 확인 필요
- 런타임 확인: 사용자 5/50/100명 순차 입장 중 commit 횟수와 `ReadStatus` self time 측정

### 4.3 메시지마다 자동 smooth scroll과 layout read가 실행될 수 있음

관련 파일:

- `hooks/useAutoScroll.js:36`
- `hooks/useAutoScroll.js:49`
- `hooks/useAutoScroll.js:122`

사용자가 하단에 있으면 새 메시지마다 다음 작업이 실행될 수 있다.

```text
DOM 변경
→ useEffect
→ container.scrollHeight 읽기
→ scrollTo({ behavior: 'smooth' })
→ 300ms timer 생성
```

연속 메시지를 합쳐 처리하거나 기존 smooth scroll을 취소하는 로직은 없다. 짧은 간격으로 메시지가 들어오면 여러 smooth scroll과 timer가 겹칠 수 있다.

검색 결과 메시지 경로에서는 다음 항목이 발견되지 않았다.

- `scrollIntoView`
- `useLayoutEffect`
- `ResizeObserver`

- 판정: layout read/write 구조는 확인됨, 실제 reflow 시간은 런타임 확인 필요
- 런타임 확인: Chrome Performance에서 10개 메시지 burst 중 Scripting, Layout, Long Task, animation 구간 측정

### 4.4 입력창 노출이 초기 메시지 로딩 완료까지 막혀 있음

관련 파일:

- `features/chat/room/useRoomHandling.js:310`
- `features/chat/room/useRoomHandling.js:354`
- `features/chat/room/ChatRoomView.js:152`

초기화 순서는 다음과 같다.

```text
Socket 연결
→ 방 HTTP 조회
→ listener 등록
→ joinRoom 완료
→ 이전 메시지 로딩 완료
→ setupSucceeded
→ loading=false
→ ChatInput 렌더
```

따라서 URL 이동, HTTP join, WebSocket 연결이 성공했더라도 `previousMessagesLoaded`가 늦으면 `chat-message-input`은 DOM에 나타나지 않는다.

이는 URL과 join API, WebSocket은 성공했지만 입력창 locator가 발견되지 않았던 부하 테스트 결과와 직접 연결되는 초기 UI 준비 경로다. CPU·메모리 병목이라기보다는 perceived latency의 critical path 문제다.

## 5. Medium

### 5.1 단일 로컬 reducer 때문에 비관련 영역도 재렌더됨

`features/chat/room/useChatRoomState.js:20`의 단일 reducer에 room, messages, connection, loading 상태가 함께 있다.

메시지만 변경되어도 `ChatRoomView`가 다시 실행되므로 다음 영역도 재렌더된다.

- `ChatRoomInfo`
- `ChatInput`
- 중간 layout 컴포넌트

반면 `ChatHeader`는 `app/chat/[room]/page.js:41`에서 `ChatRoomView`의 형제이므로 메시지 로컬 state 변경으로는 재렌더되지 않는다. 현재 방 상세 트리에 별도 Sidebar는 없다.

### 5.2 ChatInput document listener가 재등록될 수 있음

`components/ChatInput.js:15`에서 생략된 `onFileSelect`의 기본값 `() => {}`가 render마다 새로 만들어진다.

그 결과 `handleFileValidationAndPreview` 참조가 바뀌고, `components/ChatInput.js:138`의 effect가 다시 실행되어 `mousedown`, `paste` listener를 제거하고 재등록한다.

listener가 누적되지는 않지만 메시지 burst 중 listener churn이 발생한다.

### 5.3 무한 스크롤 Observer가 메시지마다 재생성될 수 있음

`hooks/useInfiniteScroll.js:12`의 기본 `options = {}`는 호출마다 새 객체다. `onLoadMore`도 messages dependency 때문에 새로 생성된다.

따라서 `hooks/useInfiniteScroll.js:32`의 sentinel Observer effect가 메시지 변경 때마다 정리·재생성될 수 있다. Observer는 한 개라 메시지별 ReadStatus Observer보다 영향이 작다.

### 5.4 파일 메시지의 반복 계산과 미디어 유지

`components/FileMessage.js:47`에서는 render마다 시간 포맷, 파일명 decode, 크기 계산, 파일 유형 분기가 실행된다. 기존 파일 메시지도 콜백 변경 때문에 다시 렌더될 수 있다.

- 이미지는 lazy loading 적용
- PDF는 내장 viewer 없이 파일 정보와 버튼만 표시
- video/audio는 `preload="metadata"`

PDF 자체는 무거운 렌더러가 아니지만 이미지·비디오·오디오 DOM과 metadata는 메시지가 유지되는 동안 남는다.

### 5.5 연결 상태 listener 역할이 중첩됨

활성 소켓에는 다음 계층이 연결 상태를 함께 구독한다.

- `services/socket.js:104`: 서비스 내부 상태
- `features/chat/room/useSocketHandling.js:22`: `connected` state
- `features/chat/room/useChatRoomLifecycle.js:98`: room lifecycle state

`connect`에서 `setConnected(true)`가 두 React 경로에서 호출될 수 있다. 하지만 cleanup은 동일 handler 참조를 사용하며 정상 렌더마다 listener가 계속 누적되는 구조는 아니다.

## 6. 문제 없음 또는 영향이 제한적인 부분

### 6.1 Zustand 전체 구독 문제는 없음

프로젝트에는 Zustand 및 `useChatStore()` 형태의 사용이 없다. 채팅 상태는 로컬 `useReducer`, 인증은 React Context로 관리한다.

`contexts/AuthContext.js:244`의 Context value는 새 객체이므로 인증 상태 변경 시 모든 `useAuth()` 소비자가 재렌더될 수 있다. 하지만 채팅 메시지 로컬 state 변경이 AuthProvider를 재렌더시키지는 않으므로 실시간 메시지 병목의 직접 원인은 아니다.

`lib/socket/SocketProvider.js:7`의 SocketContext 값은 memoized되어 있다.

### 6.2 일반 메시지 listener는 메시지 state 변경으로 중복 등록되지 않음

`features/chat/room/useRoomHandling.js:72`는 새 room listener 구독 전에 기존 unsubscribe를 호출한다.

`lib/socket/socketClient.js:93`의 구독 helper도 등록한 handler와 동일한 참조로 `off`한다. 메시지 state는 listener effect dependency가 아니므로 새 메시지마다 listener가 추가되는 구조는 아니다.

### 6.3 Socket 객체는 render마다 재생성되지 않음

`services/socket.js:22`는 연결 중 Promise와 이미 연결된 socket을 재사용한다.

방 목록에서 상세 방으로 이동할 때도 목록 훅은 자신의 listener만 제거하고 연결은 공유한다. 렌더마다 새 Socket.IO 연결이 생기는 구조는 아니다.

### 6.4 MessageContent 문자열 파싱은 기존 행에서 대체로 방어됨

`components/MessageContent.js:4`는 줄바꿈과 mention regex만 처리하며 Markdown parser는 없다.

`React.memo`이고 content가 문자열 primitive이므로 기존 메시지의 content가 같으면 상위 `UserMessage`가 재렌더되어도 `MessageContent` 자체는 건너뛸 가능성이 높다.

## 7. Socket.IO listener lifecycle

```text
ChatRoom mount
→ setupRoom()
→ socketClient.connect()
   └─ SocketService 내부 connect/disconnect/error listener 등록
→ attachSocket(socket)
   ├─ useSocketHandling connect/disconnect 등록
   └─ useChatRoomLifecycle 연결/reconnect listener 등록
→ fetchRoomData()
→ setupEventListeners()
   ├─ participantsUpdate
   ├─ messagesRead
   ├─ message
   ├─ previousMessagesLoaded
   ├─ messageReactionUpdate
   ├─ session_ended
   └─ error
→ joinRoom
→ fetchPreviousMessages
```

메시지 state 변경 시 lifecycle은 다음과 같다.

```text
messages 변경
→ room listener 재등록 안 함
→ connection listener 재등록 안 함
→ 리액션 add/remove 콜백은 재생성
→ 메시지 행 memo 무효화
```

연결 상태나 `isInitialized`가 변경되면 lifecycle 연결 effect는 다시 실행되지만 기존 handler를 정확히 제거한 뒤 등록한다.

Unmount 시 room listener와 연결 listener는 대체로 정확히 제거된다. 다만 `features/chat/room/useChatRoom.js:131`의 `off('message')` 형태는 해당 이벤트의 모든 listener를 제거하므로 listener 누적보다 다른 소유자의 listener까지 지울 수 있는 과도한 cleanup 위험에 가깝다.

Typing 관련 Socket.IO 이벤트는 현재 프론트 코드에서 발견되지 않았다.

## 8. 전체 이벤트·렌더 흐름

```text
Socket.IO "message"
      ↓
createRoomEventHandlers.onMessage
      ↓
processedMessageIds Set 확인
      ↓
messages.some() 중복 확인
      ↓
setMessages([...messages, incoming])
      ↓
chatRoomReducer "messages/changed"
      ↓
ChatRoomView render
 ├─ ChatHeader
 │    └─ 영향 없음: ChatRoomView의 형제
 │
 ├─ ChatRoomInfo
 │    ├─ 상태/참여자 UI 재렌더
 │    └─ participants.map()
 │
 ├─ ChatMessages
 │    ├─ 전체 messages 복사·정렬
 │    ├─ 전체 messages.map()
 │    ├─ SystemMessage × N       ← 콜백 prop 변경으로 재렌더 가능
 │    ├─ UserMessage × N         ← 콜백 prop 변경으로 재렌더 가능
 │    │    ├─ CustomAvatar       ← 메시지별 window listener
 │    │    ├─ MessageContent     ← 동일 content면 memo 가능
 │    │    ├─ ReadStatus         ← 메시지별 Observer/읽음 계산
 │    │    └─ MessageActions
 │    └─ FileMessage × N         ← 콜백 prop 변경으로 재렌더 가능
 │         ├─ 이미지/video/audio
 │         ├─ CustomAvatar
 │         ├─ ReadStatus
 │         └─ MessageActions
 │
 └─ ChatInput
      └─ render 및 document listener effect 재실행 가능
      ↓
useAutoScroll
      ↓
scrollHeight 측정 + smooth scroll
      ↓
ReadStatus가 화면 노출 감지
      ↓
markMessagesAsRead(messageId)
      ↓
Socket.IO "messagesRead"
      ↓
전체 messages.map()
      ↓
다시 ChatRoomView 렌더 경로
```

## 9. 최종 우선순위

| 순위 | 병목 후보                                  | 근거                                                            | 위험도   | 측정 방법                               | 개선 필요성 |
| ---: | ------------------------------------------ | --------------------------------------------------------------- | -------- | --------------------------------------- | ----------- |
|    1 | 메시지 1건당 기존 행 N개 재렌더            | messages dependency가 리액션 콜백 참조를 변경                   | Critical | React Profiler render count/commit time | 즉시 측정   |
|    2 | 메시지별 읽음 Observer·이벤트·전체 map     | `ReadStatus`가 각 행에 존재하고 `messagesRead`가 전체 배열 순회 | Critical | Socket 이벤트 수와 Profiler             | 즉시 측정   |
|    3 | DOM·Avatar listener·Observer 무제한 증가   | 로딩한 메시지를 제거하지 않고 모두 map                          | Critical | Heap snapshot, DOM/Event Listener 수    | 즉시 측정   |
|    4 | 참여자 업데이트가 전체 메시지 무효화       | `room` 전체를 모든 행에 전달                                    | High     | 참여자 입장 중 Profiler                 | 높음        |
|    5 | 초기 메시지 응답 중복 처리                 | 상시 listener와 one-shot listener가 모두 process                | High     | processMessages 호출 횟수와 CPU profile | 높음        |
|    6 | 전체 메시지 반복 정렬                      | 로드 처리와 render 양쪽에서 sort                                | High     | CPU flame chart                         | 높음        |
|    7 | 메시지마다 smooth scroll/layout read       | scrollHeight 측정과 겹치는 scroll timer                         | High     | Chrome Performance Layout/Long Task     | 높음        |
|    8 | ChatRoomInfo·ChatInput 비관련 재렌더       | 단일 reducer를 ChatRoomView가 직접 구독                         | Medium   | Profiler render reason                  | 중간        |
|    9 | ChatInput/sentinel listener·Observer churn | 기본 함수/object 참조와 messages 의존성                         | Medium   | Event listener/Observer 생성 횟수       | 중간        |
|   10 | AuthContext 전체 value                     | 인증 변경 시 소비자 전체 갱신, 메시지와는 무관                  | Medium   | 인증 갱신 시 Profiler                   | 낮음        |

## 10. 가장 먼저 측정할 항목 3개

### 10.1 메시지 한 건당 실제 재렌더 개수

메시지 30/300/1,000개 상태에서 메시지 하나를 수신하고 다음 항목을 측정한다.

- `ChatMessages` render count
- 기존 `UserMessage`, `FileMessage`, `SystemMessage` render count
- `ReadStatus`, `ChatInput`, `ChatRoomInfo` render count
- 전체 commit duration
- 각 컴포넌트의 render reason

### 10.2 읽음 이벤트 증폭률

같은 시간 구간에서 다음 수치를 함께 기록한다.

- `message` 수신 수
- `markMessagesAsRead` 발행 수
- `messagesRead` 수신 수
- `messages/changed` reducer dispatch 수
- React commit 수

사용자 수가 증가할 때 어느 단계부터 이벤트와 렌더가 증폭되는지 확인한다.

### 10.3 메시지 수에 따른 DOM·heap·layout 증가율

메시지 30/300/1,000개에서 다음 지표를 비교한다.

- DOM Nodes
- JS Heap
- `userProfileUpdate` listener 수
- `IntersectionObserver` 수
- 10개 메시지 burst의 Scripting 시간
- Layout/Recalculate Style 시간
- Long Task 수와 최대 duration

## 11. 최종 판단

현재 코드에서 가장 먼저 검증해야 하는 경로는 `useRoomHandling` 자체보다 다음 두 가지다.

```text
messages 변경
→ useReactionHandling 콜백 변경
→ ChatMessages 전체 행 재렌더
```

```text
메시지별 ReadStatus
→ 개별 IntersectionObserver
→ 개별 markMessagesAsRead
→ messagesRead마다 전체 messages map
```

두 경로가 메시지 수와 사용자 수 증가에 따른 CPU 사용량 증가 및 브라우저 응답성 저하와 가장 직접적으로 연결될 가능성이 높다. 다만 최종 병목 확정과 개선 순서는 React Profiler, Socket 이벤트 카운트, Chrome Performance·Memory 결과를 함께 수집한 뒤 결정해야 한다.
