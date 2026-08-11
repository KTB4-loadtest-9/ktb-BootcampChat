# Socket.IO는 멀티 인스턴스 근거가 생길 때까지 단일 노드로 유지한다

현재 백엔드는 `MemoryStoreFactory`와 로컬 `ChatDataStore`를 사용하고, 실제 backend replica 수·로드밸런서·sticky session 구성이 확정되어 있지 않다. 따라서 Redis Pub/Sub이나 분산 Socket.IO adapter를 도입하지 않고 단일 노드 구성을 유지한다. Redis 도입은 둘 이상의 backend 인스턴스에서 room broadcast 누락이 재현되거나 운영 배포가 확정될 때, Java netty-socketio 호환성과 장애 정책을 별도 검증한 뒤 진행한다.

## Status

accepted

## Considered options

- Redis Pub/Sub과 분산 Socket.IO adapter를 즉시 도입: 현재 배포 전제가 없어 인프라·세션·중복 이벤트 복잡성만 먼저 늘어난다.
- 단일 노드 유지: 현재 코드와 배포 문서에 맞고, 데이터 조회 최적화와 독립적으로 검증할 수 있다.

## Consequences

- 여러 backend 인스턴스에 클라이언트가 분산되면 서버 간 room broadcast가 공유되지 않을 수 있다.
- 멀티 인스턴스 운영이 확정되면 이 ADR을 갱신하고, adapter 호환성·sticky session·Redis 장애·중복/순서 정책을 포함한 별도 구현을 진행해야 한다.
