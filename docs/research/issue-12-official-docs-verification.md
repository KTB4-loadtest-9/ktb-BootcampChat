# Issue #12: 성능 최적화 근거 공식 문서 교차검증

조사일: 2026-08-11 (Asia/Seoul)
기준 코드: `origin/main` / `2058a6a9c1c0154da96fbba9e48542d892522693`
범위: [Issue #12](https://github.com/KTB4-loadtest-9/ktb-BootcampChat/issues/12)의 공식 문서 semantics와 이 저장소의 Java/MongoDB/Redis/Socket.IO/Artillery 적용·검증 결과를 분리한다.

## 결론

- 공식 문서는 `lean`, `populate`, compound index, `updateMany`, Redis keyspace iteration, Socket.IO Redis adapter, multiple nodes, Artillery의 동작과 제약을 설명한다.
- 이 저장소는 Mongoose 애플리케이션이 아니다. Spring Data MongoDB를 사용하며, `RoomService`의 Java `findAllById`·Mongo aggregation은 Mongoose `populate`·`lean`과 같은 구현이 아니다.
- 이 저장소는 Mongo compound index를 선언하고 Redis cache/session을 사용하지만, index planner 사용과 성능 개선은 이 보고서에서 실측하지 않았다.
- Socket.IO Redis adapter는 Node 패키지와 Redis Pub/Sub 기반 inter-server broadcast 계약이다. 현재 Java `netty-socketio`의 `MemoryStoreFactory`·`LocalChatDataStore`가 이를 지원하거나 호환한다고 추정하지 않는다.
- Artillery 설정과 시나리오의 존재·문법은 확인했지만, 부하 실행 결과는 만들지 않았다. 따라서 공식 문서나 설정 파일만으로 성능 개선을 단정하지 않는다.

## 공식 문서 semantics

| 주제 | 공식 문서에서 확인한 사실 | 이 저장소에 바로 적용할 수 없는 해석 |
| --- | --- | --- |
| Mongoose `lean` | hydration을 건너뛰어 POJO를 반환하고 빠르고 메모리 사용이 적을 수 있다. 대신 change tracking, validation, getters/setters, virtuals, `save()` 등이 없다. `populate()`와 함께 쓰면 populated 문서에도 lean이 전파된다. [공식 문서](https://mongoosejs.com/docs/7.x/docs/tutorials/lean.html) | Java DTO 매핑이나 Spring Data MongoDB 조회를 `lean` 적용으로 기록할 수 없다. |
| Mongoose `populate` | 참조 경로를 다른 collection의 문서로 치환하며, populated 값을 얻기 전에 별도 DB query를 수행한다. field selection으로 반환 필드를 제한할 수 있다. [공식 문서](https://mongoosejs.com/docs/populate.html) | Java `findAllById` batch 조회는 같은 결과 목표를 가질 수 있어도 Mongoose `populate` semantics나 query 수를 증명하지 않는다. |
| MongoDB compound index | 여러 field를 포함하고, 전체 field 또는 index prefix를 사용하는 query를 지원한다. 선언만으로 query planner가 실제 사용했다는 증거가 되지는 않는다. [공식 문서](https://www.mongodb.com/docs/manual/core/indexes/index-types/index-compound/create-compound-index/) | `ensureIndex`와 성능 향상을 같은 사실로 취급하지 않는다. 실제 filter/sort에 대한 `explain`이 필요하다. |
| MongoDB `updateMany` | 매칭 문서를 각각 수정한다. 각 문서 쓰기는 원자적이지만 `updateMany` 전체는 원자적이지 않으며, 다중 문서 원자성이 필요하면 transaction을 사용해야 한다. 멱등 작업에 사용해야 한다. [공식 문서](https://www.mongodb.com/docs/manual/reference/method/db.collection.updateMany/) | 왕복 감소와 다중 문서 all-or-nothing 보장은 별개다. |
| Redis `KEYS` | keyspace의 key 수를 N이라 할 때 O(N)이고, 큰 DB에서 성능을 망칠 수 있으므로 일반 애플리케이션 코드에 사용하지 말고 `SCAN` 또는 set을 고려하라고 명시한다. [공식 문서](https://redis.io/docs/latest/commands/keys/) | “키 패턴 삭제가 빠르다”거나 `KEYS`를 `SCAN`이라고 부를 근거가 없다. |
| Redis `SCAN` | cursor 기반 incremental iteration이며 호출당 O(1), 전체 순회는 O(N)이다. 한 호출의 결과 수를 보장하지 않고 iteration 중 같은 element가 여러 번 반환될 수 있다. [공식 문서](https://redis.io/docs/latest/commands/scan/) | `SCAN`을 쓰더라도 cursor 종료, 중복, concurrent mutation을 처리해야 한다. |
| Socket.IO Redis adapter | Redis Pub/Sub을 이용해 현재 서버의 broadcast와 다른 Socket.IO 서버로의 packet 전달을 연결한다. adapter 자체는 Redis key를 저장하지 않고, Redis 장애 시 현재 서버의 client에만 전송된다. [공식 문서](https://socket.io/docs/v4/redis-adapter/) | Redis session/cache와 Socket.IO adapter는 다른 역할이다. Java `netty-socketio` 호환은 이 문서로 증명되지 않는다. |
| Socket.IO multiple nodes | multiple server에는 load balancing과 server 간 message forwarding이 필요하다. Redis adapter는 forwarding 선택지 중 하나이며, polling을 쓰는 경우 session affinity가 필요하다. [공식 문서](https://socket.io/docs/v4/using-multiple-nodes/) | Redis가 dependency에 있다는 사실만으로 multi-node Socket.IO가 완성되지 않는다. |
| Artillery | API부터 full browser experience까지 부하를 모델링하는 load-testing platform이며, Playwright, arrivals/ramps, monitoring 연동을 제공한다. [공식 문서](https://www.artillery.io/docs/get-started/load-testing) | 도구 사용법과 우리 애플리케이션의 latency/error/throughput 결과는 별도 evidence다. |

## Project-specific evidence

### MongoDB와 Java 적용

- [backend `pom.xml`](../../apps/backend/pom.xml#L31-L43)은 Spring Data MongoDB, Spring Data Redis, Spring Cache를 사용한다. Mongoose dependency는 없다.
- [MongoConfig](../../apps/backend/src/main/java/com/ktb/chatapp/config/MongoConfig.java#L16-L22)는 `Message`에 `{room: 1, timestamp: 1}` index를 `ensureIndex`한다. 이 파일만으로 planner 사용·latency 개선은 확인할 수 없다.
- [RoomService](../../apps/backend/src/main/java/com/ktb/chatapp/service/RoomService.java#L46-L80)는 Mongo page 조회 후 user ID를 모아 `findAllById`로 가져오고, [RecentMessageCounter](../../apps/backend/src/main/java/com/ktb/chatapp/service/RecentMessageCounter.java#L34-L55)는 room ID 목록을 Mongo aggregation으로 묶는다. 이것은 Java batch/aggregation evidence이며 Mongoose `populate`·`lean` evidence가 아니다.
- [MessageReadStatusService](../../apps/backend/src/main/java/com/ktb/chatapp/service/MessageReadStatusService.java#L28-L58)는 message ID마다 `findById` 후 Java readers 검사와 `save`를 반복한다. 현재 기준선에 Mongo `updateMany` 또는 `bulkWrite` 구현은 없다.
- [Message](../../apps/backend/src/main/java/com/ktb/chatapp/model/Message.java#L28-L78)의 `readers`는 `{userId, readAt}` 하위 문서다. 따라서 Mongo `updateMany` 도입 시에도 중복·동시성·transaction 요구를 별도로 검증해야 한다.

### Redis, cache, session

- [CacheConfig](../../apps/backend/src/main/java/com/ktb/chatapp/config/CacheConfig.java#L24-L56)는 Redis cache manager와 room response의 10초 TTL JSON serializer를 구성한다. [RoomService](../../apps/backend/src/main/java/com/ktb/chatapp/service/RoomService.java#L46-L47)는 room 목록 cache를 사용하고, [RoomService](../../apps/backend/src/main/java/com/ktb/chatapp/service/RoomService.java#L153-L186)는 생성·입장 시 cache를 evict한다.
- [SessionRedisStore](../../apps/backend/src/main/java/com/ktb/chatapp/service/session/SessionRedisStore.java#L16-L21)는 Redis hash를 session store로 사용하고, [동 파일](../../apps/backend/src/main/java/com/ktb/chatapp/service/session/SessionRedisStore.java#L31-L45)의 Lua script에서 session ID 일치 조건을 확인해 삭제·touch한다.
- 저장소 검색에서 Redis `KEYS`/`SCAN` command 호출은 확인되지 않았다. Lua의 `KEYS[1]`는 Redis script에 전달된 key 배열 placeholder이지 `KEYS` 명령 실행 evidence가 아니다. Redis Pub/Sub publish/subscribe도 현재 Java 애플리케이션 경로에서 확인되지 않았다.
- `apps/backend/monitoring/prometheus.txt`는 추적된 snapshot이지만 수집 시각·명령·부하 조건의 provenance가 없다. 그 안의 수치를 성능 결과로 사용하지 않았다.

### Socket.IO

- [pom.xml](../../apps/backend/pom.xml#L87-L96)은 `com.corundumstudio:netty-socketio:2.0.13`과 Redisson을 선언한다. Socket.IO 공식 Redis adapter의 Node 패키지 설치·버전 표는 이 Java dependency의 호환성을 말하지 않는다.
- [SocketIOConfig](../../apps/backend/src/main/java/com/ktb/chatapp/config/SocketIOConfig.java#L40-L63)는 `MemoryStoreFactory`를 사용하고 “단일노드 전용”으로 주석 처리되어 있다.
- [SocketIOConfig](../../apps/backend/src/main/java/com/ktb/chatapp/config/SocketIOConfig.java#L92-L97)는 `LocalChatDataStore`를 등록하며, [LocalChatDataStore](../../apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/LocalChatDataStore.java#L10-L40)는 `ConcurrentHashMap` 기반이다.
- 결론적으로 현재 코드에서 확인되는 것은 단일 JVM 메모리 저장소다. Redis adapter 도입, inter-server broadcast, multi-node session affinity는 구현·측정되지 않았으며 이 PR의 범위도 아니다.

### Artillery 적용과 측정 경계

- [e2e/package.json](../../e2e/package.json#L10-L14)은 Artillery `^2.0.25`와 Playwright `1.61.0`을 선언한다.
- [artillery-config.yaml](../../e2e/artillery/artillery-config.yaml#L1-L18)은 `BASE_URL`, `duration`, `arrivalCount`, Playwright engine, headless Chromium을 설정한다.
- [Makefile](../../e2e/artillery/Makefile#L22-L40)은 Node/pnpm/Artillery를 확인하고 Chromium을 설치한 뒤 `artillery run artillery/artillery-config.yaml`을 실행한다. [통합 시나리오](../../e2e/artillery/all-scenarios.js#L26-L65)는 인증·방·대량 메시지·파일·프로필 흐름을 순차 실행한다.
- 이 브랜치에서는 서버 기동, `make artillery`, browser smoke, Prometheus 수집, latency/error/throughput 비교를 실행하지 않았다. 따라서 Artillery 설정이 존재한다는 사실만 확인되며 project-specific performance improvement는 `unverifiable`이다.

## Verification

실행 결과는 성능 수치가 아니라 기준선의 적용·문법·테스트 경계를 확인한 것이다.

| 명령 | 결과 | 의미와 제한 |
| --- | --- | --- |
| `cd apps/backend && ./mvnw -q -Punit-tests test` | 통과; Surefire 37 suites, 182 tests, failures/errors 0, skipped 22 | Java 기준선 테스트 통과. 성능 개선 증거는 아님. |
| `for f in e2e/artillery/*.js e2e/artillery/scenarios/*.js; do node --check "$f"; done` 및 Ruby YAML parse | 통과 | Artillery JS/YAML 문법만 확인. 서버·브라우저·부하 결과는 아님. |
| `git grep`로 `apps/backend apps/frontend e2e loadtest package.json pnpm-workspace.yaml` 안의 `mongoose`, `@socket.io/redis-adapter`, `createAdapter`, `updateMany`, `bulkWrite`, `lean`, `populate` 검색 | 해당 API 매치 없음 | 현재 Java 기준선이 해당 Mongoose/Node 구현을 포함하지 않는다는 정적 evidence. `KEYS[1]` Lua placeholder는 별도 확인. |
| `pnpm --dir loadtest test -- --runInBand` | 실행하지 못함: Jest binary unavailable | loadtest contract suite는 이 worktree에 의존성이 없어 미실행. 해당 suite 결과를 주장하지 않음. |
| Artillery browser load / Mongo `explain` / Redis command or Pub/Sub metrics | 실행하지 않음 | 재현 가능한 project-specific performance evidence 없음. |

## Acceptance criteria 판정

- 각 기술 주장에 지정된 공식 primary source를 연결했다.
- 공식 semantics와 프로젝트 evidence를 분리했다.
- 공식 문서만으로 성능 개선을 단정하지 않았다.
- Redis `KEYS`, `SCAN`, Pub/Sub, Socket.IO adapter의 역할을 분리했다.
- Mongoose reference와 Java implementation의 차이를 명시했다.
- [Issue #12](https://github.com/KTB4-loadtest-9/ktb-BootcampChat/issues/12)와 기준 코드 파일을 연결했다. `origin/main`에는 추적된 별도 설계 문서가 없으므로 존재하지 않는 설계 문서 링크를 만들지 않았다.
- provenance 없는 snapshot 수치와 실행되지 않은 load result는 근거에서 제외했다.

이번 변경은 문서 한 파일만 추가하며 애플리케이션 코드·Redis infrastructure·Socket.IO infrastructure는 변경하지 않는다.
