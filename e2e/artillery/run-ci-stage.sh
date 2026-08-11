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
failure_summary="${report_dir}/artillery-${stage_vus}-vu-failure-summary.txt"
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
  {
    echo "Result: FAIL"
    echo "Duration: ${elapsed_seconds}s"
    echo "Exit code: ${artillery_status}"
    echo

    node - "$full_log" <<'NODE'
const fs = require('fs');

const logPath = process.argv[2];
const lines = fs.readFileSync(logPath, 'utf8').split(/\r?\n/);
const metrics = new Map();

for (const line of lines) {
  const match = line.match(/^(vusers\.(?:created|completed|failed)|errors\.[^:]+):\s*\.*\s*([0-9.]+)\s*$/);
  if (match) {
    metrics.set(match[1], match[2]);
  }
}

console.log('Failure counters:');
const preferredMetrics = ['vusers.created', 'vusers.completed', 'vusers.failed'];
let printedMetric = false;

for (const key of preferredMetrics) {
  if (metrics.has(key)) {
    console.log(`  ${key}: ${metrics.get(key)}`);
    printedMetric = true;
  }
}

for (const [key, value] of [...metrics.entries()].filter(([key]) => key.startsWith('errors.')).sort()) {
  console.log(`  ${key}: ${value}`);
  printedMetric = true;
}

if (!printedMetric) {
  console.log('  No final Artillery counters were written before the process exited.');
}

const failurePatterns = [
  /scenario failed:/i,
  /worker error/i,
  /\b(?:Timeout|Expect)Error\b/i,
  /page\.waitForResponse/i,
  /^\s*Locator:/i,
  /^\s*Expected:/i,
  /^\s*Received:/i,
  /^\s*Call log:/i,
  /^\s*Error:/i,
  /^\s*at .*e2e\/artillery\/.*:\d+:\d+/,
];
const samples = [];
const seen = new Set();

for (const line of lines) {
  if (!failurePatterns.some((pattern) => pattern.test(line))) {
    continue;
  }

  const normalized = line.trim().replace(/\s+/g, ' ');
  if (normalized && !seen.has(normalized)) {
    seen.add(normalized);
    samples.push(normalized);
  }
}

console.log();
console.log('Failure samples (deduplicated):');
if (samples.length > 0) {
  for (const sample of samples.slice(0, 60)) {
    console.log(`  ${sample}`);
  }
} else {
  for (const line of lines.slice(-40)) {
    console.log(`  ${line}`);
  }
}
NODE
  } | tee "$failure_summary"

  echo "::error title=Artillery ${stage_vus} VU failed::Stage failed after ${elapsed_seconds}s with exit code ${artillery_status}. Failure counters and deduplicated errors are printed above; the complete log is in the Artifact."
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      printf '### %s VU: FAIL\n\n- Duration: %ss\n- Exit code: `%s`\n- Full log: `%s`\n\n' \
        "$stage_vus" "$elapsed_seconds" "$artillery_status" "$full_log"
      printf '```text\n'
      cat "$failure_summary"
      printf '```\n\n'
    } >>"$GITHUB_STEP_SUMMARY"
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
