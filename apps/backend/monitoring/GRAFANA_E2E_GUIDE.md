# Grafana로 KTB Chat E2E 모니터링하기

이 문서는 로컬 KTB Chat을 실행하면서 Prometheus와 Grafana로 애플리케이션·MongoDB·Redis 지표를 확인하는 방법을 설명합니다.

로컬 실행 흐름은 다음과 같습니다.

~~~text
Artillery + Playwright
        ↓
Frontend :3000 → Backend HTTP :5001 / Socket.IO :5002
                          ↓ /actuator/prometheus
                    Prometheus :9090
                          ↓
                     Grafana :9091
~~~

## 1. 사전 준비

- Node.js 20 이상
- 저장소가 요구하는 pnpm `10.28.2`
- Docker와 Docker Compose v2

pnpm이 설치되어 있지 않다면 다음 명령을 먼저 실행합니다.

~~~bash
npm install --global pnpm@10.28.2
hash -r
pnpm --version
~~~

저장소 루트에서 의존성을 설치합니다.

~~~bash
pnpm install --frozen-lockfile
~~~

`docker compose version`이 동작하지 않으면 Docker Desktop 또는 Docker Compose v2를 먼저 설치해야 합니다.

## 2. 로컬 모니터링 스택 실행

저장소 루트에서 다음 명령을 실행합니다. 로컬 개발용 Compose는 MongoDB, Redis, 각 exporter, Prometheus, Grafana를 함께 실행합니다.

~~~bash
cd apps/backend
docker compose up -d
docker compose ps
~~~

로컬 포트는 다음과 같습니다.

| 서비스 | 주소 | 용도 |
|---|---|---|
| Frontend | `http://localhost:3000` | E2E 테스트 대상 |
| Backend HTTP | `http://localhost:5001` | REST API와 Actuator |
| Backend Socket.IO | `http://localhost:5002` | 실시간 채팅 |
| Prometheus | `http://localhost:9090` | 메트릭 수집·쿼리 |
| Grafana | `http://localhost:9091` | 대시보드 |
| MongoDB exporter | `http://localhost:9216/metrics` | MongoDB 메트릭 |
| Redis exporter | `http://localhost:9121/metrics` | Redis 메트릭 |

로컬 E2E에서는 `docker-compose.yaml`을 사용합니다. `docker-compose.o11y.yaml`은 Grafana를 `3000` 포트에 노출하므로 Frontend와 충돌합니다.

## 3. 애플리케이션 실행과 수집 상태 확인

다른 터미널에서 저장소 루트로 이동한 뒤 애플리케이션을 실행합니다.

~~~bash
pnpm run dev
~~~

백엔드가 실행된 뒤 다음 엔드포인트를 확인합니다.

~~~bash
curl http://localhost:5001/actuator/health
curl http://localhost:5001/actuator/prometheus | head
~~~

Prometheus의 [Targets 화면](http://localhost:9090/targets)에서 다음 상태를 확인합니다.

| Job | 기대 상태 |
|---|---|
| `spring-boot-app` | `UP` |
| `mongodb` | `UP` |
| `redis` | `UP` |
| `prometheus` | `UP` |

`spring-boot-app`이 `DOWN`이면 백엔드가 `5001` 포트에서 실행 중인지 확인합니다. 개발 Prometheus는 Docker 컨테이너에서 `host.docker.internal:5001/actuator/prometheus`를 5초마다 수집합니다.

## 4. Grafana 접속

[Grafana](http://localhost:9091)에 접속합니다.

- 사용자: `admin`
- 비밀번호: `admin`

기본 계정은 로컬 개발용입니다. 공유 환경이나 운영 환경에서는 반드시 비밀번호를 변경합니다.

Prometheus 데이터소스는 Compose 프로비저닝 설정으로 자동 등록됩니다.

- 데이터소스 이름: `Prometheus`
- Grafana 내부 주소: `http://prometheus:9090`
- 설정 파일: `grafana/provisioning/datasources/prometheus.dev.yml`

## 5. 프로비저닝된 대시보드

Compose를 실행하면 다음 대시보드가 파일에서 자동 등록됩니다.

| 대시보드 | UID | 용도 |
|---|---|---|
| KTB Chat Load Overview | `ktb-load-overview` | HTTP, JVM, MongoDB, Redis 부하 전후 비교 |

이 대시보드는 현재 `Prometheus` 데이터소스와 실제 노출 메트릭만 사용합니다. Node Exporter 대시보드는 운영용
`node-exporters.prod.yml`을 위한 파일이므로 로컬 Compose의 측정 범위에는 포함하지 않습니다.

## 6. Artillery E2E 실행

대시보드가 준비되고 `spring-boot-app`이 `UP`이면 Artillery 테스트를 실행합니다.

먼저 한 명이 핵심 경로를 완주하는 스모크 테스트를 실행합니다. 이 명령은 프론트엔드·백엔드 상태,
Prometheus 타깃, Grafana 데이터소스와 핵심 대시보드 메트릭까지 함께 검증합니다.

저장소 루트에서 실행합니다.

~~~bash
make -C e2e/artillery smoke
# 또는
pnpm run test:artillery:smoke
~~~

성공 기준은 `vusers.completed=1`, `vusers.failed=0`이며 결과는 기본적으로
`/tmp/ktb-artillery-smoke.json`에 저장됩니다. 스모크는 회원가입·로그인, 방 목록, 방 생성,
`joinRoom`과 초기 메시지 로드, 메시지 전송을 실제 브라우저로 검증합니다.

전체 시나리오는 다음과 같이 실행합니다. Spring Boot 스크랩 간격이 5초이므로 그래프 비교에는 60초 이상을 권장합니다.

~~~bash
BASE_URL=http://localhost:3000 PHASE1_ARRIVAL_COUNT=1 PHASE1_DURATION=60 make -C e2e/artillery artillery
~~~

Artillery 한 가상 사용자는 회원가입·로그인, 채팅방 생성, 메시지 전송, 파일 업로드, 금칙어 처리, 프로필 수정을 순서대로 수행합니다. 테스트 데이터는 자동으로 삭제되지 않으므로 반복 실행 시 MongoDB 데이터가 증가합니다.

비교 실행은 같은 `BASE_URL`, 가상 사용자 수, 생성 구간, MongoDB 초기 상태를 사용하고 출력된 commit SHA와
JSON 원본 경로를 함께 기록합니다.

## 7. Grafana에서 확인할 지표

### HTTP 요청량

~~~promql
sum(rate(http_server_requests_seconds_count{application="ktb-chat-backend"}[1m]))
~~~

### HTTP 오류율

~~~promql
sum(rate(http_server_requests_seconds_count{application="ktb-chat-backend",status=~"4..|5.."}[1m]))
~~~

### 평균 HTTP 응답시간

~~~promql
sum(rate(http_server_requests_seconds_sum{application="ktb-chat-backend"}[1m])) / sum(rate(http_server_requests_seconds_count{application="ktb-chat-backend"}[1m])) * 1000
~~~

### JVM 메모리

~~~promql
sum by (area) (jvm_memory_used_bytes{application="ktb-chat-backend"})
~~~

### Socket.IO 메시지 처리량과 평균 처리시간

~~~promql
sum(rate(socketio_messages_total{status="success"}[1m]))
sum(rate(socketio_messages_processing_time_seconds_sum{status="success"}[1m]))
  / sum(rate(socketio_messages_processing_time_seconds_count{status="success"}[1m])) * 1000
~~~

### MongoDB·Redis 처리량

~~~promql
sum(rate(mongodb_ss_opcounters[1m]))
sum(rate(redis_commands_total[1m]))
~~~

Grafana의 `Explore` 화면에서 위 쿼리를 직접 실행할 수도 있습니다.

## 8. 문제 해결

### `spring-boot-app`이 `DOWN`인 경우

~~~bash
curl http://localhost:5001/actuator/prometheus
docker logs prometheus-ktb --tail 100
~~~

백엔드가 실행 중인지, Prometheus가 `host.docker.internal:5001`에 접근할 수 있는지 확인합니다.

### Grafana 대시보드가 비어 있는 경우

1. Grafana 데이터소스가 `Prometheus`인지 확인합니다.
2. [Prometheus Targets](http://localhost:9090/targets)에서 해당 job이 `UP`인지 확인합니다.
3. 시간 범위를 `Last 15 minutes`로 설정합니다.
4. E2E를 60초 이상 실행해 새 메트릭을 생성합니다.

### Node Exporter 대시보드에 데이터가 없는 경우

현재 로컬 Compose는 Node Exporter를 실행하지 않습니다. 애플리케이션·MongoDB·Redis 대시보드를 사용하거나, 호스트 리소스 모니터링이 필요할 때만 Node Exporter를 별도로 추가합니다.

## 관련 설정 파일

- `apps/backend/docker-compose.yaml`
- `apps/backend/monitoring/prometheus/prometheus.dev.yml`
- `apps/backend/monitoring/grafana/provisioning/datasources/prometheus.dev.yml`
- `apps/backend/monitoring/grafana/provisioning/dashboards/dashboard.yml`
- `apps/backend/monitoring/grafana/provisioning/dashboards/ktb-load-overview.json`
- `e2e/artillery/Makefile`
