# Backend PR Cloud Load Test CI Setup

Backend PR의 JAR를 현재 클라우드 Backend EC2에 임시 배포하고 공개 도메인에
Artillery + Playwright 부하를 준 뒤, 테스트 전 JAR로 자동 복구하는 CI입니다.

## 현재 구성

- Repository: `KTB4-loadtest-9/ktb-BootcampChat`
- 전체 인프라: EC2 `t3.small` 20대에 FE, BE, Redis, DB, Monitoring 역할 분산
- CI 제어 서버: Monitoring EC2 `10.0.101.245`
- 현재 Backend EC2: `10.0.101.102`
- 공개 테스트 URL: `https://goorm-ktb-009.goorm.team`
- Backend 서비스: `ktb-backend`
- Backend JAR: `/home/ubuntu/ktb-chat-backend/target/ktb-chat-backend-0.0.1-SNAPSHOT.jar`
- Self-hosted runner: `ktb-ci-01`
- Runner labels: `self-hosted`, `Linux`, `X64`, `ktb-ci`

Monitoring EC2 runner는 JAR 다운로드, Backend SSH 배포, Health Check, 롤백,
Grafana Annotation만 담당합니다. Maven 빌드와 Chromium 부하 생성은
GitHub-hosted runner에서 실행하므로 Monitoring EC2의 Prometheus/Grafana 자원을
부하 생성에 사용하지 않습니다.

```text
PR head commit JAR build (GitHub-hosted)
  -> 기존 JAR 백업 및 PR JAR 배포 (Monitoring EC2 runner)
  -> 15 / 30 / 60 VU 공개 도메인 테스트 (GitHub-hosted)
  -> 모든 Backend의 기존 JAR 복구 (Monitoring EC2 runner)
```

DB, Redis, 로드 밸런서와 공개 도메인은 공유 자원이므로 Workflow는 동시에 하나만
실행됩니다. 외부 Fork PR과 Draft PR은 배포하지 않습니다.

## 1. 이미 완료한 인프라 설정

현재 다음 항목은 구성 및 검증이 완료됐습니다.

- Monitoring EC2에 GitHub Actions runner v2.336.0 설치
- runner를 systemd 서비스로 등록하고 `Listening for Jobs` 확인
- Monitoring EC2에 Backend 배포 전용 ED25519 키 생성
- Backend의 `authorized_keys`에 배포 공개키 등록
- Monitoring의 SSH alias `ktb-backend` 구성
- Backend SG에 Monitoring SG를 소스로 하는 TCP 22 규칙 추가
- Monitoring에서 `ssh ktb-backend` 및 Backend Health Check 확인
- Repository Variables `BE_DEPLOY_HOSTS`, `GRAFANA_URL` 등록
- Repository Secret `GRAFANA_API_TOKEN` 등록 및 Grafana API 인증 확인

현재 등록값은 다음과 같습니다.

```text
BE_DEPLOY_HOSTS=ktb-backend
GRAFANA_URL=http://localhost:3000
GRAFANA_API_TOKEN=<GitHub encrypted secret>
```

Backend가 여러 대면 Monitoring의 `~/.ssh/config`에 모든 Backend alias와 키를
구성한 뒤, `BE_DEPLOY_HOSTS`를 공백 구분 목록으로 바꿉니다.

```text
BE_DEPLOY_HOSTS=ktb-backend-1 ktb-backend-2 ktb-backend-3
```

FE, Redis, DB, Monitoring EC2는 이 변수에 넣지 않습니다.

## 2. 저장소 파일 적용

다음 세 파일을 반드시 같은 commit으로 전달합니다.

```text
.github/workflows/backend-pr-cloud-loadtest.yml
e2e/artillery/artillery-config.ci.yaml
CI_CLOUD_LOADTEST_SETUP.md
```

작업 전 현재 상태를 확인합니다.

```bash
git status -sb
git switch main
git pull --ff-only origin main
git switch -c codex/backend-pr-cloud-loadtest-ci
```

파일과 기본 문법을 확인합니다.

```bash
test -f .github/workflows/backend-pr-cloud-loadtest.yml
test -f e2e/artillery/artillery-config.ci.yaml
test -f CI_CLOUD_LOADTEST_SETUP.md
git diff --check
```

관계없는 로컬 파일을 포함하지 않도록 세 파일만 Stage합니다.

```bash
git add \
  .github/workflows/backend-pr-cloud-loadtest.yml \
  e2e/artillery/artillery-config.ci.yaml \
  CI_CLOUD_LOADTEST_SETUP.md

git diff --cached --check
git diff --cached --stat
git status -sb
```

팀 리뷰를 받을 준비가 됐을 때만 commit하고 push합니다.

```bash
git commit -m "ci: add backend PR cloud load test"
git push -u origin codex/backend-pr-cloud-loadtest-ci
```

PR은 `codex/backend-pr-cloud-loadtest-ci`에서 `main`을 대상으로 생성합니다. 자동으로
Merge하지 않으며 팀 리뷰와 승인 후 담당자가 Merge합니다. 기본 브랜치에 Workflow가
들어가기 전에는 이 Workflow의 수동 실행과 이후 PR 자동 실행을 사용할 수 없습니다.

## 3. 보안 설계

저장소가 public이므로 외부 Fork PR에서 self-hosted runner를 실행하면 안 됩니다.
Workflow는 다음 조건을 적용합니다.

- `pull_request_target`을 사용해 실행 명령은 `main`의 Workflow 정의에서 읽음
- PR 코드는 GitHub-hosted runner에서만 checkout 및 build
- 같은 저장소 내부 브랜치의 PR만 배포
- Draft PR은 배포하지 않음
- `GITHUB_TOKEN`은 `actions: read`, `contents: read`만 허용
- checkout 단계에서 Git credential을 저장하지 않음

내부 PR의 Backend 코드는 실제 클라우드 환경에서 실행되므로, 저장소 쓰기 권한은
신뢰할 수 있는 팀원에게만 부여해야 합니다.

## 4. 최초 수동 실행

Workflow가 리뷰 후 `main`에 반영되면 GitHub에서 다음으로 이동합니다.

```text
Actions -> Backend PR Cloud Load Test -> Run workflow -> main
```

실행 순서는 다음과 같습니다.

1. `Build PR backend JAR`
2. `Deploy PR JAR from Monitoring EC2`
3. `Artillery 15 / 30 / 60 VU`
4. `Restore original backend JAR`

확인 기준:

- Java 25 JAR build 성공
- Monitoring runner의 JAR checksum 검증 성공
- Backend 백업, 원자적 JAR 교체, 재시작과 Health Check 성공
- 15, 30, 60 VU 단계가 순서대로 실행
- 단계 사이 20초 회복 시간 적용
- Artillery JSON과 로그 Artifact 생성
- 기존 JAR 복구와 Health Check 성공
- 해당 실행의 백업 JAR 삭제
- Grafana 시작/종료 Annotation 표시

Artillery 결과는 14일 보관합니다. 실패한 VU 비율이 1%를 초과하면 단계와 Workflow가
실패합니다. 테스트 사용자가 DB에 생성한 데이터는 자동 삭제하지 않습니다.

## 5. Backend PR 자동 실행

`main` 대상 내부 PR에서 다음 경로가 바뀌면 자동 실행합니다.

```text
apps/backend/**
e2e/**
.github/workflows/backend-pr-cloud-loadtest.yml
```

새 commit이 push되면 `synchronize` 이벤트로 다시 실행합니다. 공유 인프라에는 한 번에
한 Workflow만 배포합니다.

결과 확인 경로:

```text
PR -> Checks -> Backend PR Cloud Load Test
Actions -> 해당 Run -> Artillery 15 / 30 / 60 VU
Actions -> 해당 Run -> Artifacts
Grafana -> 테스트 시각 -> github-actions Annotation
```

## 6. 실패 구분

| Job | 의미 |
| --- | --- |
| `build` | Java build 또는 Maven 의존성 문제 |
| `deploy` | Artifact, SSH, JAR 경로, sudo, 서비스 시작 또는 Health Check 문제 |
| `load_test` | 1% 초과 VU 실패, Chromium/Artillery 또는 애플리케이션 문제 |
| `rollback` | 기존 JAR 복구 또는 서비스 재시작 문제. 즉시 수동 확인 필요 |

## 7. 수동 복구

Workflow를 강제로 취소하거나 Monitoring EC2가 중단되면 자동 롤백을 완료하지 못할 수
있습니다. Backend EC2에서 해당 실행의 백업을 찾습니다.

```bash
sudo find /home/ubuntu/ktb-chat-backend/target -maxdepth 1 \
  -name '*.ci-backup-*' -ls
```

복구할 Run ID와 Attempt의 파일을 정확히 선택합니다.

```bash
BACKUP_JAR=/home/ubuntu/ktb-chat-backend/target/ktb-chat-backend-0.0.1-SNAPSHOT.jar.ci-backup-<RUN_ID>-<RUN_ATTEMPT>
ACTIVE_JAR=/home/ubuntu/ktb-chat-backend/target/ktb-chat-backend-0.0.1-SNAPSHOT.jar
RESTORE_JAR=${ACTIVE_JAR}.manual-restore

sudo install -o ubuntu -g ubuntu -m 0644 "$BACKUP_JAR" "$RESTORE_JAR"
sudo mv -f "$RESTORE_JAR" "$ACTIVE_JAR"
sudo systemctl restart ktb-backend
curl -fsS http://localhost:5001/api/health
```

Health Check가 성공한 뒤에만 선택한 백업 파일을 삭제합니다.

## 8. 운영 주의사항

- Workflow 실행 중에는 같은 Backend에 수동 배포하지 않습니다.
- Workflow를 가능하면 강제 취소하지 않습니다.
- DB와 Redis 테스트 데이터는 자동 삭제되지 않습니다.
- 내부 PR이라도 신뢰하지 못하는 Backend 코드는 현재 환경에 배포하지 않습니다.
- GitHub-hosted runner의 Chromium OOM과 애플리케이션 장애를 구분합니다.
- 최종 대회 최대 부하는 별도 부하 시험 결과를 기준으로 판단합니다.

## 완료 체크리스트

- `ktb-ci-01` runner가 Idle
- runner에 `self-hosted`, `Linux`, `X64`, `ktb-ci` label 존재
- Monitoring에서 모든 Backend SSH와 Health Check 성공
- Variables와 Secret 등록
- 세 CI 파일이 동일 commit에 포함
- 최초 수동 실행에서 build, deploy, load_test, rollback 성공
- 원래 JAR 복구와 백업 정리 확인
- Artillery Artifact와 Grafana Annotation 확인
