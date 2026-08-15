# Issue #11 evidence audit

작성일: 2026-08-11 (Asia/Seoul)

## 판정 요약

이 문서는 작업 트리의 문서·HTML·측정 산출물을 `origin/main`의 실제 소스와 대조한 결과다. 기존 산출물은 수정하지 않았다.

| 주장/산출물 | 판정 | 근거 |
| --- | --- | --- |
| `/api/rooms`의 방 목록 페이지네이션·사용자 일괄 조회·최근 메시지 aggregation | **implemented** | `origin/main` `2058a6a9c`; `RoomService.java:46-104`, `RoomService.java:272-286`, `RecentMessageCounter.java:34-55`; 관련 구현 커밋 `f2d339536`, `a4af94ed4`, `f538722bc`, `13f1b3646` |
| `room_timestamp_idx` 보장 | **implemented** | `origin/main` `2058a6a9c`; `MongoConfig.java:16-22`; 도입 커밋 `f2d339536` |
| 위 최적화의 단위 테스트 근거 | **implemented** | `RoomServiceTest.java:41-80`, `100-109`; `RecentMessageCounterTest.java:29-50`; 기준 worktree에서 targeted test 통과 |
| Redis 방 목록 캐시 설계 문서 | **planned** | `docs/superpowers/specs/2026-08-10-room-list-redis-cache-design.md:3-22`, `docs/superpowers/plans/2026-08-10-room-list-redis-cache.md:21-171`은 설계/실행 계획이며 체크박스가 미완료다. 실제 캐시 코드는 별도 커밋으로 존재하지만 이 문서들은 그 구현·커밋을 연결하지 않는다. |
| 1/25/100 VU 결과표와 `/api/rooms` latency | **unverifiable** | `apps/backend/monitoring/api-rooms-optimization-report.md:18-30` 및 `apps/backend/monitoring/api-rooms-paar-report.html:1071-1083`에 조건·요약은 있으나 실행별 raw Artillery 결과, tool config, dataset 식별자, 대상 commit SHA가 없다. |
| `COUNT_SCAN(room_timestamp_idx)`, `totalDocsExamined=0`, 2ms | **unverifiable** | Markdown `:5-16`, HTML `:956-964`가 수치를 주장하지만 explain JSON/raw output과 실행 DB·commit 연결이 없다. 소스에 인덱스 선언이 있다는 사실은 위 구현 근거로만 인정한다. |
| 100 VU에서 시스템 CPU 96.8%, backend CPU 5%, heap 352MB, 5xx 0 | **unverifiable** | Markdown `:28-30`, `:48-58`, HTML `:900-902`, `:1053-1062`에 요약만 있다. `apps/backend/monitoring/prometheus.txt:1-12`는 `/home/ubuntu/ktb-chat-backend`의 누적 scrape이며 해당 100 VU 구간의 시간 범위·raw snapshot이 아니다. |
| 인증 11/11, 최종 smoke 1/1/0, 단일 VU 15.2s | **unverifiable** | Markdown `:32-46`, `:67-74`는 명령과 결과를 적었지만 Playwright/Artillery raw report나 CI artifact가 없다. |
| 부하 실패 원인이 로컬 부하 발생기라는 진단 | **unverifiable** | Markdown `:48-65`의 시스템 관찰값은 raw run과 연결되지 않고, 서버와 generator를 분리한 재현 실행도 없다. 결론은 가설/제한으로만 기록한다. |
| `reference-optimization-audit.md`의 현재 구현 비교표 | **stale** | `docs/research/reference-optimization-audit.md:36-47`은 현재 방 목록을 “전체 방·방별 조회”로 분류하지만 `origin/main`에는 위 bulk/page/aggregation 구현이 이미 있다. 문서의 조사일·기준 SHA가 없어 어느 시점의 상태인지 확정할 수 없다. |
| Issue #16 결정 문서의 “이번 이슈에서 생산 코드를 바꾸지 않았다” | **implemented / planned 혼재** | `docs/research/issue-16-read-cursor-decision.md:5-19`의 비변경 결정과 측정 조건은 문서의 결정/계획으로 유효하다. 다만 문서가 기준으로 적은 `8d2ab50db`는 `origin/main`이 아니므로 현재 main의 증거로 재사용하지 않는다. |
| chat feature sequence HTML | **unverifiable** | `apps/backend/chat-feature-sequence-slider.html:642-799`와 동일 복제본인 `apps/backend/docs/chat-feature-sequence-slider.html`은 source-line 이름만 제공하고 source path/line, commit, test를 제공하지 않는다. 생성 HTML은 source of truth가 아니다. |

## 기준 상태와 변경 경계

- Issue: `#11`, open, labels 없음, assignee 없음, blocked-by 0. 본문 acceptance criteria를 기준으로 audit했다.
- 기준 브랜치: `origin/main` = `2058a6a9c1c0154da96fbba9e48542d892522693`.
- audit worktree: `agent/issue-11-evidence-audit`, 기준 브랜치에서 생성. 시작 시 tracked diff 없음.
- 원래 작업 트리 snapshot: `agent/issue-4-message-history-n-plus-one` at `2058a6a9c`; `apps/backend/src/test/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoaderTest.java` 수정 1개와 `.env`, `target/`, `node_modules/`, 문서·HTML 등 미추적 산출물이 있었다. 이 파일들은 읽기만 했고 이 브랜치에 추가하지 않았다.
- 원래 작업 트리의 `git diff --stat`는 tracked diff만 보여 주며 문서·HTML은 untracked라 diff에 포함되지 않는다. 따라서 “작업 트리의 전체 변경”을 commit diff로 재현할 수 없다.

## 주장별 evidence 연결

### 구현

`origin/main`의 `RoomService`는 `PageRequest`로 방 목록을 제한하고(`:46-55`), 빈 결과를 조기 반환하며(`:56-70`), 사용자 ID를 모아 `findUsersById`로 조회하고 최근 메시지 수를 room ID 모음으로 집계한다(`:72-81`). `RecentMessageCounter`는 room ID 목록을 하나의 aggregation으로 처리한다(`:34-55`). `MongoConfig`는 애플리케이션 시작 시 `room_timestamp_idx`를 보장한다(`:16-22`). 이 구현과 테스트는 source/test evidence로 인정한다.

방 목록 Redis 캐시도 현재 main 소스에는 `RoomService.java:24-25,46,153,185`와 `CacheConfig.java`에 존재하지만, 작업 트리의 설계 문서에는 실제 구현 커밋·테스트 결과가 연결되어 있지 않다. 따라서 설계 문서 자체는 planned/stale로 분리한다.

### 측정

보고서 표는 concurrency와 duration을 일부 제공한다. 그러나 각 실행의 dataset size, 환경, tool config, 대상 commit SHA, raw output 위치가 완전하게 연결되지 않는다. 특히 `prometheus.txt`는 다음 특성 때문에 보고서의 100 VU 단일 실행 evidence가 아니다.

- application path가 `/home/ubuntu/ktb-chat-backend`다(`:9-12`).
- `/api/rooms` 수치는 200 응답 336회와 누적 합 14.869초(`:54-59`)이며 보고서의 특정 60초 실행과 시간 범위가 없다.
- 같은 파일에는 `/api/auth/register` 1,130회와 Mongo `messages.find` 402,030회(`:64-67`, `:208-209`)가 있어 보고서 표의 단일 실행 raw 결과로 식별할 수 없다.

따라서 보고서의 성능 수치는 “측정했다고 서술된 값”이지 acceptance evidence로서 재현 가능한 measured 결과가 아니다. 실제 구현이 없는 수치를 완료 근거로 사용하지 않았고, 구현·측정·계획을 분리했다.

## Acceptance criteria 점검

- [x] 주요 구현 주장에 source/test 근거를 연결했다.
- [x] 계획과 실제 구현을 분리했다.
- [ ] benchmark 수치에 대상 commit SHA가 있다 — 보고서에 없어 미충족.
- [x] 재현 불가능한 수치를 `unverifiable`로 표시했다.
- [x] untracked/generated artifact와 소스 변경을 분리했다.
- [x] 현재 기준 branch와 보고서의 대상 commit을 대조했다 — 보고서 자체의 대상 SHA는 누락됐다.
- [x] audit 중 코드·브랜치·사용자 산출물을 임의 변경하지 않았다.

## 실행 검증

기준 worktree `/private/tmp/ktb-BootcampChat-issue11`에서 실행했다.

```text
./mvnw -q -Dtest=RoomServiceTest,RecentMessageCounterTest test  PASS (exit 0)
./mvnw -q -Punit-tests test                                  PASS (exit 0)
git diff --check                                              PASS (exit 0)
```

Maven/Mockito/JDK의 deprecation·dynamic-agent·CORS 경고는 테스트 실패가 아니며, 결과 판정과 분리했다. MongoDB가 localhost:27017에서 실행 중이어서 전체 unit profile도 완료됐다. 부하 테스트와 explain 재실행은 원래 사용자 산출물·외부 환경을 변경할 수 있고 raw dataset/환경을 복원할 수 없어 실행하지 않았다.

## Review

### Standards

저장소에 별도 `CONTRIBUTING.md` 또는 coding-standard 문서는 없었다. 새 변경은 단일 Markdown report이며 코드 추상화·의존성·생성 파일을 추가하지 않는다. 문서에는 의도적인 성능 수치 재작성이나 산출물 정리가 없다. 기준선 smell도 확인되지 않았다.

### Spec

Issue #11의 핵심 요구인 주장 분류, source/test/command 연결, benchmark 조건 점검, generated/untracked 분리, branch/commit 대조, 사용자 파일 보존을 이 보고서에 반영했다. acceptance에서 외부 raw 결과와 commit SHA가 없는 항목은 완료로 포장하지 않고 `unverifiable` 또는 `stale`로 남겼다. 기존 문서·HTML·target·node_modules는 diff에 포함하지 않았다.

## 결론 및 후속 조건

구현 근거는 `origin/main`에 존재하지만, 작업 트리의 부하 측정 수치와 HTML 요약은 현재 기준 commit 및 raw 실행 결과로 재현할 수 없다. 따라서 이 audit이 승인할 수 있는 것은 구현·단위 테스트·정적 상태까지이며, 100 VU 성능 목표나 backend bottleneck 결론은 승인하지 않는다. 해당 수치를 `measured`로 승격하려면 동일 commit SHA, dataset seed/크기, concurrency·duration, 환경, Artillery config, Prometheus/host raw snapshot을 한 실행 단위로 보관해야 한다.
