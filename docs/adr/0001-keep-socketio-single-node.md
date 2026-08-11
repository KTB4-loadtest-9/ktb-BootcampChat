# Socket.IO는 멀티 인스턴스 근거가 생길 때까지 단일 노드로 유지한다

현재 백엔드는 `MemoryStoreFactory`와 로컬 `ChatDataStore`를 사용한다. 애플리케이션 내부 이벤트 전파는 JVM 로컬 `ApplicationEventPublisher`에 의존하며, 인스턴스 간 broker는 없다. `ApplicationEventPublisher`가 처리하는 room·AI 관련 애플리케이션 이벤트와 Socket.IO room broadcast는 서로 다른 경로이므로, 전자는 publisher/listener 경로를 같은 JVM 안에서 검증하고 후자는 Socket.IO 클라이언트를 이용한 room 이벤트 전달로 별도 검증해야 한다. 실제 backend replica 수·로드밸런서·sticky session 구성이 확정되어 있지 않으므로 Redis Pub/Sub이나 분산 Socket.IO adapter를 도입하지 않고 단일 노드 구성을 유지한다. Redis 도입은 둘 이상의 backend 인스턴스에서 room broadcast 누락이 재현되거나 운영 배포가 확정될 때, Java netty-socketio 호환성과 장애 정책을 별도 검증한 뒤 진행한다.

## Status

accepted

## Considered options

- Redis Pub/Sub과 분산 Socket.IO adapter를 즉시 도입: 현재 배포 전제가 없어 인프라·세션·중복 이벤트 복잡성만 먼저 늘어난다.
- 단일 노드 유지: 현재 코드와 배포 문서에 맞고, 데이터 조회 최적화와 독립적으로 검증할 수 있다.

## Consequences

- 여러 backend 인스턴스에 클라이언트가 분산되면 서버 간 room broadcast가 공유되지 않을 수 있다.
- 멀티 인스턴스 운영이 확정되면 이 ADR을 갱신하고, adapter 호환성·sticky session·Redis 장애·중복/순서 정책을 포함한 별도 구현을 진행해야 한다.

## 멀티 인스턴스 전환 전 검증 기준

멀티 인스턴스 전환은 다음 검증을 모두 통과하고, adapter 호환성·sticky session·Redis 장애 정책이 운영 환경에 맞게 확정된 뒤 진행한다. 아래의 room 이벤트 검증은 Socket.IO 클라이언트 관점에서 수행하고, 애플리케이션 이벤트 검증은 JVM 로컬 publisher/listener 경로를 대상으로 수행한다.

| 항목 | 검증 방법 | 통과 기준 |
|---|---|---|
| adapter·sticky session 호환성 | 선택한 Java netty-socketio adapter와 실제 로드밸런서 설정으로 두 backend에 연결한다. | 연결이 의도한 backend에 유지되고, adapter가 room join/leave와 broadcast를 오류 없이 처리한다. |
| cross-node event delivery | 서로 다른 backend에 연결된 클라이언트를 같은 room에 넣고 room 이벤트를 발생시킨다. | 대상 room의 모든 연결이 이벤트를 잃지 않고 각각 한 번 수신한다. |
| room membership | 각 backend에서 join·leave를 반복하고 연결별 room 목록과 broadcast 대상을 확인한다. | join한 연결만 수신하고, 한 연결의 leave가 다른 연결의 membership을 제거하지 않는다. |
| connection/session state | 각 backend에서 인증 연결·재연결·동시 연결을 수행하고 사용자/session 식별자를 비교한다. | 모든 backend가 동일한 인증 사용자와 session 정책을 적용하고, 연결 상태가 교차해서 오염되지 않는다. |
| reconnects | 네트워크 단절과 backend 재시작 후 클라이언트를 재연결하고 기존 room 복귀를 확인한다. | 재연결이 정해진 backoff 정책 안에 성공하고, 필요한 room만 복구되며 stale membership이 남지 않는다. |
| session expiry | 짧은 TTL의 session을 만료시키고 기존 연결과 재연결을 각각 시험한다. | 만료된 session은 모든 backend에서 동일하게 거부 또는 종료되고, 만료 후 무단 갱신이 되지 않는다. |
| backpressure | 하나의 room에 burst 이벤트와 느린 소비자를 함께 두고 큐·지연·메모리를 측정한다. | 큐가 정한 상한 안에 머물고, OOM 없이 정의된 drop/retry 정책과 허용 지연을 지킨다. |
| authentication | 유효·무효·만료 token을 각 backend에 번갈아 전송한다. | 모든 backend가 동일한 검증 결과를 내며, 무효 인증이 room 접근이나 이벤트 수신으로 우회되지 않는다. |
| single-node regression | 같은 시나리오를 backend 1개로 실행하고 기존 기능 테스트와 기준 부하 결과를 비교한다. | 기존 단일 노드의 기능과 측정 기준을 유지하고 허용한 회귀 범위를 넘지 않는다. |
| duplicate delivery | 이벤트마다 correlation/event id를 부여하고 여러 backend·연결에서 수신 id를 집계한다. | 의도한 대상별 수신 횟수가 1회이며, 중복률이 0이고 재시도 시에도 중복 정책이 지켜진다. |
| ordering | 같은 room에 순번을 가진 이벤트를 빠르게 발행하고 수신 순서를 기록한다. | 동일 room의 이벤트 순서가 보존되며, 순서 보장이 불가능하면 sequence/gap 처리 정책이 명시되고 검증된다. |
| Redis failure behavior | Redis 단절·지연·재시작을 주입하고 연결, broadcast, session, 복구 과정을 관찰한다. | 장애 시 fail-open/closed 정책이 의도대로 동작하고 데이터 손실·무한 재시도 없이 health signal과 복구 결과가 확인된다. |
