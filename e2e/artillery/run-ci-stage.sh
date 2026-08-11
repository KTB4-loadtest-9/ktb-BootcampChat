#!/usr/bin/env bash

set -Eeuo pipefail

stage_vus="${1:?Usage: run-ci-stage.sh <virtual-users>}"
report_dir="${2:-../artillery-reports}"
config_file="${ARTILLERY_CONFIG:-artillery/artillery-config.ci.yaml}"

if [[ ! "$stage_vus" =~ ^[0-9]+$ ]]; then
  echo "Invalid virtual-user count: ${stage_vus}" >&2
  exit 2
fi

mkdir -p "$report_dir"

json_report="${report_dir}/artillery-${stage_vus}-vu.json"
full_log="${report_dir}/artillery-${stage_vus}-vu.log"
export STAGE_VUS="$stage_vus"

echo "========================================"
echo "Artillery stage: ${stage_vus} VU"
echo "Target: ${BASE_URL}"
echo "Full output: ${full_log}"
echo "========================================"

started_at=$(date +%s)
set +e
pnpm exec artillery run \
  --output "$json_report" \
  "$config_file" \
  >"$full_log" 2>&1
artillery_status=$?
set -e
elapsed_seconds=$(( $(date +%s) - started_at ))

if (( artillery_status != 0 )); then
  echo "::error title=Artillery ${stage_vus} VU failed::Stage failed after ${elapsed_seconds}s. Full output is available in the Artillery artifact."
  echo "Relevant failure output:"
  if ! grep -E -i \
    'worker error|timeout|assert|failed|error:' \
    "$full_log" | tail -n 60; then
    tail -n 60 "$full_log"
  fi
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '### %s VU: FAIL\n\n- Duration: %ss\n- Full log: `%s`\n' \
      "$stage_vus" "$elapsed_seconds" "$full_log" >>"$GITHUB_STEP_SUMMARY"
  fi
  exit "$artillery_status"
fi

echo "Result: PASS"
echo "Duration: ${elapsed_seconds}s"

node - "$json_report" <<'NODE' || true
const fs = require('fs');

const reportPath = process.argv[2];
const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
const counters = report.aggregate?.counters || {};
const values = [
  ['VUs created', 'vusers.created'],
  ['VUs completed', 'vusers.completed'],
  ['VUs failed', 'vusers.failed'],
];

for (const [label, key] of values) {
  console.log(`${label}: ${Number(counters[key] || 0)}`);
}
NODE

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf '### %s VU: PASS\n\n- Duration: %ss\n- Full log: `%s`\n' \
    "$stage_vus" "$elapsed_seconds" "$full_log" >>"$GITHUB_STEP_SUMMARY"
fi
