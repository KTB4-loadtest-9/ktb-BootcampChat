# Artillery·Prometheus·Grafana 검증 가이드

이 문서는 부하 테스트가 실제 애플리케이션과 관측성 스택을 함께 검증하도록
실행 조건과 측정 기준을 고정한다. 명령이 실행되지 않은 결과를 성능 통과로
해석하지 않는다.

## 로컬 구성

| 구성 요소 | 주소 | 검증 대상 |
| --- | --- | --- |
| Frontend | `http://localhost:3000` | Artillery `BASE_URL` |
| Backend HTTP | `http://localhost:5001` | `/api/health`, Actuator |
| Socket.IO | `http://localhost:5002` | E2E 브라우저 연결 |
| Prometheus | `http://localhost:9090` | scrape target·query |
| Grafana | `http://localhost:9091` | datasource·dashboard |
| MongoDB exporter | `http://localhost:9216` | MongoDB exporter target |
| Redis exporter | `http://localhost:9121` | Redis exporter target |

전체 로컬 스택은 다음으로 시작한다.

```bash
cd apps/backend
docker compose up -d
cd ../..
pnpm run dev
```

## 검증 순서

이번 변경이 검증하는 local 측정 진입점은 루트 `package.json`의 Artillery
명령이다. 기존 `.github/workflows/backend-pr-cloud-loadtest.yml`의 trusted
cloud Artillery 경로는 별도 운영 경로이며 이 local verifier/report contract의
범위에는 포함하지 않는다. `loadtest/`의 별도 Node.js Socket.IO
스크립트는 자체 실행 경로·옵션·출력 형식을 가진 진단 도구이며, 이 verifier나
Prometheus/Grafana report contract에 연결되어 있지 않으므로 이번 성능 근거에서
제외한다. 저장소에는 연결된 k6 진입점도 없다. `loadtest/`를 공식 측정 경로로
승격하려면 URL credential guard, 동일한 smoke 판정, runtime metric snapshot,
strict context report를 별도 작업으로 추가해야 한다.

`e2e/artillery`의 기존 `make artillery`는 호환성을 위해 보존된 low-level
진단 경로다. 이 명령의 raw output은 endpoint·monitoring·report 계약을 모두
통과한 공식 측정 근거로 인정하지 않으며, 공식 실행에는 루트의
`pnpm run test:artillery:verify`, `pnpm run test:artillery:smoke`,
`pnpm run test:artillery`를 사용한다.

```bash
# 패키지·브라우저·Frontend·Backend·Prometheus·Grafana 확인
pnpm run test:artillery:verify

# 1 VU / 5초 smoke 및 metric 존재 확인
pnpm run test:artillery:smoke

# 전체 Artillery 실행은 조건을 명시해서 별도로 수행
PHASE1_ARRIVAL_COUNT=1 PHASE1_DURATION=60 pnpm run test:artillery
```

원격 대상은 실수로 부하를 보내지 않도록 기본 차단한다. 승인된 경우에만
명시적으로 허용한다.

```bash
ALLOW_REMOTE_LOAD=true \
  BASE_URL=https://approved-frontend.example.com \
  BACKEND_URL=https://approved-backend.example.com \
  PROMETHEUS_URL=https://approved-prometheus.example.com \
  GRAFANA_URL=https://approved-grafana.example.com \
  PHASE1_ARRIVAL_COUNT=1 PHASE1_DURATION=10 pnpm run test:artillery:smoke
```

## 측정 기준

Artillery 결과에서 가상 사용자 완료·실패 수, throughput, p50/p95/p99와
실패 시나리오를 기록한다. Prometheus/Grafana에서는 다음 지표를 같은 실행
조건으로 확인한다.

- HTTP 요청·오류 요청 rate: `http_server_requests_seconds_count`
- HTTP 평균 응답시간: `http_server_requests_seconds_sum/count`
- MongoDB 명령 rate·소요시간: `mongodb_driver_commands_seconds_*`
- MongoDB pool 사용량·대기열: `mongodb_driver_pool_checkedout`,
  `mongodb_driver_pool_waitqueuesize`
- JVM heap 사용량: `jvm_memory_used_bytes`, `jvm_memory_max_bytes`
- JVM CPU 사용량: `process_cpu_usage`
- Socket.IO 동시 사용자·이벤트·메시지 오류·처리시간:
  `socketio_concurrent_users`, `socketio_events_total`,
  `socketio_messages_errors_total`, `socketio_messages_processing_time_seconds_*`

오류율 패널은 실행 중 관측된 4xx/5xx와 Socket.IO 오류를 보여주는 측정값이다.
현재 브라우저 시나리오에는 실패 로그인과 금칙어 rejection처럼 의도된 음수 경로가
포함되어 있으므로 오류율이 0인지로 smoke 통과를 판정하지 않는다. smoke의
성공·실패 판정은 Artillery의 완료/실패 VU와 예외 counter를 기준으로 한다.

현재 Spring Boot metric은 summary 기반이므로 Grafana 대시보드의 HTTP latency는
평균값이다. p95/p99는 Artillery 결과에서 가져오며, histogram/quantile을
추가하지 않은 상태에서 p95/p99라고 표현하지 않는다.

## 이 변경의 측정 상태

이 변경은 부하 테스트 진입점과 Prometheus/Grafana 측정 계약을 고정한다.
실제 애플리케이션에 대한 baseline·변경 후 성능 결과는 아직 포함하지 않으며,
아래 비교표는 실제 실행을 완료한 뒤에만 채운다. 구성 검증이 통과했더라도
Artillery를 실행하지 않았다면 성능 통과로 판정하지 않는다.

- 구성·JSON·PromQL 계약: `pnpm run test:artillery:verify`에서 확인
- 실제 브라우저·Socket.IO 인증/이벤트 흐름: `pnpm run test:artillery:smoke`에서 확인
- Socket.IO handshake에는 `token`과 `sessionId`가 필요하므로, 단순한 비인증 HTTP
  요청으로 해당 흐름을 대체하지 않는다.
- smoke/full 실행은 `/tmp/ktb-artillery-<mode>-context.json`에 commit SHA, 대상,
  환경, VU 수, 설정 duration, dataset marker, percentile, 오류 counter,
  baseline 비교 상태를 기록한다. 보관할 위치가 있으면
  `ARTILLERY_CONTEXT_REPORT=/path/to/report.json`으로 지정한다.
- `LOAD_ENVIRONMENT`, `LOAD_DATASET`, `BASELINE_ID`, `CANDIDATE_ID`를 지정하면
  실행 context에 비교 조건을 남길 수 있다. baseline이 없으면 report 상태는
  `baseline-pending`이며 성능 비교 통과로 해석하지 않는다.
- HTTP 계약: `/api/auth/register`, `/api/auth/login`, `/api/rooms`,
  `/api/files/upload`
- Socket.IO 이벤트 계약: `joinRoom`, `chatMessage`, `leaveRoom`,
  `fetchPreviousMessages`, `markMessagesAsRead`, `messageReaction`

## 비교 기록

| 항목 | Baseline | 변경 후 |
| --- | --- | --- |
| Commit SHA |  |  |
| Dataset / DB |  |  |
| VU / duration |  |  |
| Environment |  |  |
| p50 / p95 / p99 |  |  |
| failed VUs / error rate |  |  |
| Mongo pool checked-out / wait queue |  |  |
| Mongo operation latency |  |  |

부하 테스트가 실패하거나 모니터링 target·datasource·metric이 준비되지 않은
경우 해당 실행은 진단 자료로만 기록하고 성능 통과로 판정하지 않는다.
