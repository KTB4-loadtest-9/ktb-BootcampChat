###################################
# Environment Variables
###################################
# Artillery가 부하를 주는 대상 URL
export BASE_URL ?= http://localhost:3000
export BACKEND_URL ?= http://localhost:5001
export PROMETHEUS_URL ?= http://localhost:9090
export GRAFANA_URL ?= http://localhost:9091

# 대량 채팅발송 시, 발송되는 메시지 갯수
export MASS_MESSAGE_COUNT ?= 10

# 각 Scenario 중간의 Action에 대한 Timeout 설정 (밀리초 단위)
export ACTION_TIMEOUT ?= 1000
export ACTION_TIMEOUT_SHORT ?= 500
export ACTION_TIMEOUT_LONG ?= 2000

# 금칙어 목록 (쉼표로 구분)
export FORBIDDEN_WORDS ?= "b3sig78jv,9c0hej6x,lbl276sz"

# Artillery에서 부하테스트 설정
export PHASE1_DURATION ?= 5
export PHASE1_ARRIVAL_COUNT ?= 1

SMOKE_REPORT ?= /tmp/ktb-artillery-smoke.json
ARTILLERY_REPORT ?= /tmp/ktb-artillery-full.json
GRAFANA_USER ?= admin
GRAFANA_PASSWORD ?= admin
GIT_SHA := $(shell git -C ../.. rev-parse HEAD 2>/dev/null || echo unknown)

.PHONY: verify-env verify-target verify-services verify-monitoring smoke artillery

verify-env:
	# Check Node.js installed
	@command -v node 2>&1 || { echo >&2 "❌ Node.js is not installed. Please install Node.js to proceed."; exit 1; }

	# Check pnpm installed
	@command -v pnpm 2>&1 || { echo >&2 "❌ pnpm is not installed. Run 'corepack enable' or 'npm i -g pnpm' to install it."; exit 1; }
	@test "$$(pnpm --version)" = "$$(node -p 'require("../../package.json").packageManager.split("@")[1]')" || { echo >&2 "❌ pnpm version does not match packageManager in package.json."; exit 1; }

	# Check Artillery Package Installed
	@pnpm --filter e2e list artillery 2>&1 || { echo >&2 "❌ Artillery is not installed. Run 'pnpm install' from the repo root to install it."; exit 1; }

	# Check Chromium without downloading or changing the local environment
	@pnpm --filter e2e exec node -e "const fs=require('node:fs');const {chromium}=require('playwright');const p=chromium.executablePath();if(!fs.existsSync(p)){console.error(\"❌ Chromium is missing. Run 'pnpm --filter e2e exec playwright install chromium'.\");process.exit(1)}"

verify-target:
	@case "$(BASE_URL)" in \
		http://localhost:*|http://127.0.0.1:*) ;; \
		*) test "$(ALLOW_REMOTE_LOAD)" = "true" || { echo >&2 "❌ Remote load is blocked. Set ALLOW_REMOTE_LOAD=true only after approval: $(BASE_URL)"; exit 1; } ;; \
	esac

verify-services: verify-env verify-target
	@curl -fsS "$(BASE_URL)/" >/dev/null || { echo >&2 "❌ Frontend is unavailable: $(BASE_URL)"; exit 1; }
	@curl -fsS "$(BACKEND_URL)/actuator/health" | grep -q '"status":"UP"' || { echo >&2 "❌ Backend is unavailable: $(BACKEND_URL)"; exit 1; }

verify-monitoring:
	@curl -fsS "$(PROMETHEUS_URL)/-/ready" >/dev/null || { echo >&2 "❌ Prometheus is unavailable: $(PROMETHEUS_URL)"; exit 1; }
	@curl -fsS "$(GRAFANA_URL)/api/health" | grep -q '"database": "ok"' || { echo >&2 "❌ Grafana is unavailable: $(GRAFANA_URL)"; exit 1; }
	@curl -fsS -u "$(GRAFANA_USER):$(GRAFANA_PASSWORD)" "$(GRAFANA_URL)/api/datasources/name/Prometheus" | grep -q '"url":"http://prometheus:9090"' || { echo >&2 "❌ Grafana Prometheus datasource is not connected."; exit 1; }
	@curl -fsS -u "$(GRAFANA_USER):$(GRAFANA_PASSWORD)" "$(GRAFANA_URL)/api/dashboards/uid/ktb-load-overview" | grep -q '"uid":"PBFA97CFB590B2093"' || { echo >&2 "❌ Grafana dashboard is not connected to the provisioned Prometheus datasource."; exit 1; }
	@curl -fsS "$(PROMETHEUS_URL)/api/v1/targets?state=active" | node -e 'let b="";process.stdin.on("data",d=>b+=d).on("end",()=>{const expected=["prometheus","spring-boot-app","mongodb","redis"];const targets=JSON.parse(b).data.activeTargets;const health=new Map(targets.map(t=>[t.labels.job,t.health]));const bad=expected.filter(job=>health.get(job)!=="up");if(bad.length){console.error(`❌ Prometheus target is not UP: $${bad.join(", ")}`);process.exit(1)}})'
	@for metric in http_server_requests_seconds_count socketio_concurrent_users mongodb_ss_opcounters redis_commands_total; do \
		curl -fsSG --data-urlencode "query=$$metric" "$(PROMETHEUS_URL)/api/v1/query" | grep -q '"result":\[{' || { echo >&2 "❌ Dashboard metric is missing: $$metric"; exit 1; }; \
	done
	@echo "✅ Prometheus targets, Grafana datasource, and dashboard metrics are connected."

smoke: verify-services verify-monitoring
	@echo "🚀 smoke commit=$(GIT_SHA) target=$(BASE_URL) users=$(PHASE1_ARRIVAL_COUNT) duration=$(PHASE1_DURATION)s report=$(SMOKE_REPORT)"
	@pnpm --filter e2e exec artillery run --scenario-name smoke --output "$(SMOKE_REPORT)" artillery/artillery-config.yaml
	@for attempt in 1 2 3 4 5; do \
		curl -fsSG --data-urlencode 'query=socketio_messages_processing_time_seconds_count' "$(PROMETHEUS_URL)/api/v1/query" | grep -q '"result":\[{' && { echo "✅ Socket.IO message metrics were scraped."; exit 0; }; \
		sleep 2; \
	done; echo >&2 "❌ Socket.IO message metrics were not scraped after the smoke run."; exit 1

artillery: verify-services
	@echo "🚀 full commit=$(GIT_SHA) target=$(BASE_URL) users=$(PHASE1_ARRIVAL_COUNT) duration=$(PHASE1_DURATION)s report=$(ARTILLERY_REPORT)"
	@pnpm --filter e2e exec artillery run --scenario-name full --output "$(ARTILLERY_REPORT)" artillery/artillery-config.yaml
