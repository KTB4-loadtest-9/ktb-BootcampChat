# Issue #16: readers 배열을 읽음 커서로 전환할지에 대한 결정

작성일: 2026-08-11 (Asia/Seoul)

## 결론

참여자 수 상한이 없는 현재 제품 계약에서는 **사용자·채팅방별 읽음 커서로 전환한다**. readers 배열은 단기적으로 #5의 bulk update로 쓰기 왕복을 줄일 수 있지만, 메시지 수와 참여자 수의 곱에 비례해 저장 공간과 읽음 표시 계산량이 계속 증가한다.

이번 이슈에서는 생산 코드와 공개 이벤트를 바꾸지 않았다. 현재 계약을 보존한 채 전환 계약, 마이그레이션, 롤백 조건을 기록하고, 다음 구현 이슈에서 한 vertical slice로 진행한다. #5 PR #13의 bulk update를 다시 구현하지 않는다.

## 조사 범위

- 기준 코드: `8d2ab50db481abe7241f78e308d9764ac0e646a4`
- MongoDB: 로컬 Docker `mongo:8.3.4`
- 데이터베이스: 임시 `issue16_benchmark_20260811` 사용 후 삭제
- 조합: 메시지 30·300·1,000개 × 참여자 10·50·100명
- 반복: 조합별 5회
- 메시지: 현재 `Message` 필드와 같은 `room`, `sender`, `content`, `type`, `timestamp`, `readers` 구조를 사용했다. `content`는 128바이트로 고정했다.
- 측정: MongoDB profiler를 50MiB로 확장해 명령 수를 세고, `mongosh` round trip 시간을 측정했다. 애플리케이션 전체 응답 시간이나 원격 네트워크 지연은 포함하지 않는다.

## 현재 계약과 병목

`Message`는 각 문서에 `{ userId, readAt }` readers 하위 문서를 저장한다. `MessageReadStatusService`는 현재 각 ID마다 `findById` → Java readers 검사 → `save`를 반복한다. `MessageLoader`도 메시지 이력 30개를 읽은 뒤 같은 서비스를 호출한다.

- [Message.java](../../apps/backend/src/main/java/com/ktb/chatapp/model/Message.java#L28-L78)
- [MessageReadStatusService.java](../../apps/backend/src/main/java/com/ktb/chatapp/service/MessageReadStatusService.java#L28-L59)
- [MessageLoader.java](../../apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java#L59-L75)

방 모델과 입장 경로에는 참여자 상한 검사가 없다. 따라서 readers 배열을 유지하려면 별도의 제품 상한과 초과 시 오류 계약을 먼저 추가해야 한다.

- [Room.java](../../apps/backend/src/main/java/com/ktb/chatapp/model/Room.java#L38-L80)
- [RoomService.java](../../apps/backend/src/main/java/com/ktb/chatapp/service/RoomService.java#L203-L207)

프런트엔드는 메시지별 readers를 직접 사용한다. `ReadStatus`는 참여자 목록에서 readers에 없는 사용자를 세고, `messagesRead` 이벤트는 `{ userId, messageIds }`로 클라이언트 상태를 갱신한다.

- [MessageResponse.java](../../apps/backend/src/main/java/com/ktb/chatapp/dto/MessageResponse.java#L22-L47)
- [ReadStatus.js](../../apps/frontend/components/ReadStatus.js#L19-L95)
- [roomEventHandlers.js](../../apps/frontend/features/chat/room/roomEventHandlers.js#L37-L54)
- [socketClient.js](../../apps/frontend/lib/socket/socketClient.js#L151-L157)
- [asyncapi.yaml](../../apps/backend/src/main/resources/static/api/docs/socketio/asyncapi.yaml#L851-L860)

## 측정 결과

`readerFieldTotalBefore`는 메시지 문서 전체가 아니라 `{ readers: [...] }` 필드의 BSON 크기 합계다. 문서 본문 크기에는 메시지 내용과 나머지 필드가 포함된다. `current`는 현재 HEAD의 메시지별 `findOne + updateOne` 경로, `bulk`는 #5 PR #13의 단일 `updateMany`, `cursor`는 사용자·방별 단일 upsert를 모델링했다.

| 메시지 | 참여자 | 문서 평균(B) | readers 필드 합계(B) | current 명령 | bulk 명령 | cursor 저장량(B) | current 중앙/p95(ms) | bulk 중앙/p95(ms) | cursor 중앙/p95(ms) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 30 | 10 | 726 | 12,720 | 60 | 1 | 1,540 | 69.774 / 135.220 | 1.574 / 2.995 | 1.414 / 8.533 |
| 30 | 50 | 2,605 | 69,090 | 60 | 1 | 7,780 | 52.186 / 59.376 | 2.740 / 3.749 | 1.165 / 9.534 |
| 30 | 100 | 4,956 | 139,590 | 60 | 1 | 15,680 | 32.999 / 43.362 | 2.283 / 2.796 | 0.841 / 6.374 |
| 300 | 10 | 728 | 127,200 | 600 | 1 | 1,560 | 259.412 / 328.097 | 5.351 / 5.585 | 0.869 / 6.284 |
| 300 | 50 | 2,607 | 690,900 | 600 | 1 | 7,880 | 290.591 / 363.619 | 9.970 / 11.657 | 0.955 / 8.690 |
| 300 | 100 | 4,958 | 1,395,900 | 600 | 1 | 15,880 | 299.875 / 365.932 | 20.245 / 28.715 | 1.356 / 7.323 |
| 1,000 | 10 | 729 | 424,000 | 2,000 | 1 | 1,570 | 967.873 / 1,163.838 | 18.589 / 21.963 | 1.403 / 8.072 |
| 1,000 | 50 | 2,608 | 2,303,000 | 2,000 | 1 | 7,930 | 1,602.867 / 2,746.540 | 34.318 / 47.490 | 1.741 / 13.097 |
| 1,000 | 100 | 4,959 | 4,653,000 | 2,000 | 1 | 15,980 | 2,260.943 / 4,190.452 | 73.494 / 172.797 | 1.799 / 10.134 |

### 해석

- 읽음 쓰기는 current에서 메시지 수에 따라 `2 × 메시지 수`개의 Mongo 명령을 발생시켰다. #5 bulk와 cursor는 요청당 Mongo write 명령 1개다.
- `1,000개 메시지·100명`에서는 readers 필드만 약 4.65MB였고, 같은 방의 cursor 100개는 약 15.98KB였다. readers 저장량은 메시지 수 × 참여자 수에 비례하고 cursor 저장량은 참여자 수에 비례한다.
- bulk update는 읽음 쓰기 왕복을 이미 해결하지만 readers 데이터의 증가를 해결하지 않는다. 따라서 #5는 단기 최적화이고 #16의 모델 전환 판단을 대체하지 않는다.
- 위 latency는 로컬 MongoDB와 `mongosh`의 관찰값이다. 생산 트래픽의 p95 또는 cursor 전환의 최종 성능으로 해석하지 않는다.

## 커서 계약

다음 구현 slice는 `message_read_cursors` 컬렉션을 추가한다.

```json
{
  "_id": "<roomId>:<userId>",
  "roomId": "<roomId>",
  "userId": "<userId>",
  "lastReadMessageId": "<messageId>",
  "lastReadAt": "<message.timestamp>",
  "updatedAt": "<now>"
}
```

- `(roomId, userId)`를 유일하게 보장한다. 방의 현재 참여자 cursor를 읽을 수 있도록 `{ roomId: 1, userId: 1 }` 인덱스를 둔다.
- 읽음 위치는 `(lastReadAt, lastReadMessageId)`의 정렬 순서로 정의한다. 같은 `timestamp`의 메시지를 구분할 때 `lastReadMessageId`를 tie-breaker로 사용한다.
- 읽음 처리는 `(roomId, userId)` 조건의 원자적 upsert 한 번으로 끝낸다. 더 오래된 요청이 뒤늦게 도착해도 현재 위치를 뒤로 이동시키지 않도록 Mongo update pipeline에서 위치를 비교한다.
- 메시지 응답의 `readers` 필드는 당장 제거하지 않는다. 서버가 방 참여자의 cursor와 메시지 위치를 비교해 기존 응답 형태로 계산한다. 이 방식으로 `ReadStatus`와 `messagesRead` 공개 계약을 유지한다.
- `messagesRead` 이벤트 이름과 payload `{ userId, messageIds }`는 변경하지 않는다. 이벤트 수신 직후 프런트가 로컬 readers를 병합하는 현재 동작도 유지한다.

## 마이그레이션과 롤백

1. 사전 점검에서 `timestamp`가 없는 메시지, 방이 없는 메시지, 잘못된 reader를 별도로 집계한다.
2. 기존 `messages.readers`를 `$unwind`하고 `(room, readers.userId)`별로 가장 최신 메시지 위치를 집계해 cursor를 backfill한다. backfill은 idempotent하게 재실행할 수 있어야 한다.
3. 초기 전환 기간에는 cursor와 기존 readers를 함께 기록한다. 기존 readers를 삭제하지 않아 즉시 롤백할 수 있게 한다.
4. cursor로 계산한 readers와 기존 readers를 동일 메시지 표본에서 비교한다. 누락 사용자, 동률 timestamp, 재접속 케이스를 확인한다.
5. 검증 후 읽기 경로를 cursor 기준으로 전환한다. 일정 기간 오류율과 문서 크기를 관찰한 뒤 readers 쓰기 중단과 정리는 별도 변경으로 승인한다.
6. 불일치나 장애가 발생하면 읽기 경로를 기존 readers로 되돌리고, cursor 컬렉션은 삭제하지 않고 재검증 자료로 보존한다.

## 테스트 계획

- cursor repository: 신규 upsert, 같은 요청 재시도, 오래된 요청과 최신 요청의 경쟁, 방·사용자 격리, unique index.
- 응답 adapter: 여러 참여자의 cursor로 현재 `readers` 응답을 재구성, cursor 누락 사용자, 탈퇴한 사용자, 동일 timestamp, timestamp 누락.
- handler: 권한 확인, 다른 방 ID 혼합, `messagesRead`의 기존 `userId`·`messageIds` payload 유지.
- 재접속: 놓친 이벤트가 있어도 이력 재조회에서 cursor 기준 readers가 복원되는지 확인.
- migration: 같은 데이터에 두 번 실행해도 결과가 변하지 않는지, backfill 이후 기존 readers와 위치가 일치하는지 확인.
- frontend: `ReadStatus`, `applyReadReceipts`, `messagesRead` listener의 기존 테스트를 유지하고 cursor 내부 구현을 테스트하지 않는다.
- workload: 작은 방과 큰 방에서 Mongo document size, `find`·`update` command count, update latency를 같은 데이터 조건으로 다시 비교한다. full browser load가 green이 아니면 성능 통과로 기록하지 않는다.

## 검증 상태와 한계

- `make -C e2e/artillery verify-env`: 통과
- `make -C e2e/artillery verify-monitoring`: Prometheus, Spring Boot, MongoDB, Redis target과 Grafana datasource/dashboard 연결 확인
- `SMOKE_REPORT=/tmp/ktb-issue16-smoke.json PHASE1_DURATION=5 PHASE1_ARRIVAL_COUNT=1 make -C e2e/artillery smoke`: `vusers.completed=1`, `vusers.failed=0`
- smoke 전후 Prometheus `socketio_events_total{event_type="markMessagesAsRead"}`: `3 → 4`. 읽음 이벤트가 실제 브라우저 경로를 통과한 근거지만, 성능 지표는 아니다.
- #5 PR [#13](https://github.com/KTB4-loadtest-9/ktb-BootcampChat/pull/13)은 아직 open이다. bulk 수치는 PR의 updateMany 형태를 임시 Mongo 실험으로 모델링했다.
- #10 PR [#23](https://github.com/KTB4-loadtest-9/ktb-BootcampChat/pull/23)은 smoke와 관측성 연결을 제공하지만, 대규모 browser load의 green 결과를 이 문서의 근거로 사용하지 않았다.

## 후속 작업 범위

이 문서는 Issue #16의 측정과 모델 선택을 완료한다. 다음 구현 이슈는 아래 한 vertical slice만 다룬다.

1. `message_read_cursors` 모델·repository와 unique index 추가
2. 현재 `markMessagesAsRead` 경로를 cursor atomic upsert로 연결
3. 이력 응답에서 cursor를 기존 `readers` DTO로 변환
4. 기존 Socket.IO와 프런트 계약을 검증하는 테스트 추가

마이그레이션 실행, readers 제거, Redis 지연 큐, Socket.IO 이벤트 변경은 별도 이슈로 분리한다.
