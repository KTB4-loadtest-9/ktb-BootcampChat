import { readFile, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import path from 'node:path';

const rootDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const env = {
  BASE_URL: process.env.BASE_URL || 'http://localhost:3000',
  BACKEND_URL: process.env.BACKEND_URL || 'http://localhost:5001',
  PROMETHEUS_URL: process.env.PROMETHEUS_URL || 'http://localhost:9090',
  GRAFANA_URL: process.env.GRAFANA_URL || 'http://localhost:9091',
  REQUIRED_MONITORING_JOBS: process.env.REQUIRED_MONITORING_JOBS || 'spring-boot-app,mongodb,redis',
  GRAFANA_USER: process.env.GRAFANA_ADMIN_USER || process.env.GRAFANA_USER || 'admin',
  GRAFANA_PASSWORD: process.env.GRAFANA_ADMIN_PASSWORD || process.env.GRAFANA_PASSWORD || 'admin'
};

const metricNames = [
  'http_server_requests_seconds_count',
  'mongodb_driver_commands_seconds_count',
  'mongodb_driver_pool_checkedout',
  'mongodb_driver_pool_waitqueuesize',
  'socketio_events_total',
  'socketio_messages_errors_total',
  'socketio_messages_processing_time_seconds_count',
  'socketio_concurrent_users'
];

const activityMetricNames = [
  'http_server_requests_seconds_count',
  'mongodb_driver_commands_seconds_count',
  'socketio_events_total',
  'socketio_messages_errors_total',
  'socketio_messages_processing_time_seconds_count',
  'socketio_messages_errors_total{error_type="banned_word"}'
];

function parseHttpUrl(name, value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} is not a valid URL`);
  }

  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error(`${name} must use http or https`);
  }
  if (url.username || url.password) {
    throw new Error(`${name} must not include URL credentials`);
  }
  return url;
}

function validateLoadTarget() {
  const target = parseHttpUrl('BASE_URL', env.BASE_URL);
  const localHosts = new Set(['localhost', '127.0.0.1', '[::1]']);

  if (!localHosts.has(target.hostname) && process.env.ALLOW_REMOTE_LOAD !== 'true') {
    throw new Error('Remote load is blocked. Set ALLOW_REMOTE_LOAD=true only after approval');
  }
}

function endpoint(base, pathname) {
  const baseUrl = parseHttpUrl('service URL', base).toString();
  return new URL(pathname, baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`);
}

async function request(url, options = {}) {
  let response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(5000), ...options });
  } catch (error) {
    throw new Error(`${url.origin} is unavailable: ${error.message}`);
  }
  if (!response.ok) {
    throw new Error(`${url.href} returned HTTP ${response.status}`);
  }
  return response;
}

async function json(url, options) {
  const response = await request(url, options);
  try {
    return await response.json();
  } catch {
    throw new Error(`${url.href} did not return JSON`);
  }
}

function basicAuth() {
  return `Basic ${Buffer.from(`${env.GRAFANA_USER}:${env.GRAFANA_PASSWORD}`).toString('base64')}`;
}

async function verifyServices() {
  await request(endpoint(env.BASE_URL, '/'));
  await request(endpoint(env.BACKEND_URL, '/api/health'));
  console.log('✅ Frontend and backend targets are available.');
}

function runCommand(command, args) {
  const result = spawnSync(command, args, { cwd: rootDir, stdio: 'inherit' });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} exited with status ${result.status}`);
  }
}

function commandOutput(command, args) {
  const result = spawnSync(command, args, { cwd: rootDir, encoding: 'utf8' });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} exited with status ${result.status}`);
  }
  return result.stdout.trim();
}

function verifyEnvironment() {
  runCommand('make', ['-C', 'e2e/artillery', 'verify-env']);
}

async function readDashboard() {
  const dashboardPath = path.join(
    rootDir,
    'apps/backend/monitoring/grafana/provisioning/dashboards/ktb-load-overview.json'
  );
  try {
    return JSON.parse(await readFile(dashboardPath, 'utf8'));
  } catch (error) {
    throw new Error(`Dashboard file is invalid: ${error.message}`);
  }
}

function dashboardQueries(dashboard) {
  const queries = new Map();
  for (const panel of dashboard.panels || []) {
    for (const target of panel.targets || []) {
      if (target.expr) queries.set(target.expr, { expr: target.expr, title: panel.title });
    }
  }
  return [...queries.values()];
}

async function prometheusQuery(expression) {
  const url = endpoint(env.PROMETHEUS_URL, '/api/v1/query');
  url.searchParams.set('query', expression);
  const body = await json(url);
  if (body.status !== 'success') {
    throw new Error(`PromQL query failed: ${expression}`);
  }
  return body;
}

async function verifyMonitoring({ requireData = false } = {}) {
  await request(endpoint(env.PROMETHEUS_URL, '/-/ready'));

  const grafanaHealth = await json(endpoint(env.GRAFANA_URL, '/api/health'));
  if (grafanaHealth.database !== 'ok') {
    throw new Error('Grafana database is not ready');
  }

  const dashboard = await readDashboard();
  const headers = { Authorization: basicAuth() };
  const datasource = await json(endpoint(env.GRAFANA_URL, '/api/datasources/name/Prometheus'), { headers });
  if (datasource.url !== 'http://prometheus:9090') {
    throw new Error(`Grafana Prometheus datasource URL mismatch: ${datasource.url}`);
  }

  const provisioned = await json(endpoint(env.GRAFANA_URL, '/api/dashboards/uid/ktb-load-overview'), { headers });
  if (provisioned.dashboard?.uid !== dashboard.uid) {
    throw new Error('Grafana load dashboard is not provisioned');
  }

  const targets = await json(endpoint(env.PROMETHEUS_URL, '/api/v1/targets?state=active'));
  const activeTargets = targets.data?.activeTargets || [];
  const requiredJobs = env.REQUIRED_MONITORING_JOBS.split(',').map(job => job.trim()).filter(Boolean);
  const missingJobs = requiredJobs.filter(job => !activeTargets.some(
    target => target.labels?.job === job && target.health === 'up'
  ));
  if (missingJobs.length) {
    throw new Error(`Prometheus target is not UP: ${missingJobs.join(', ')}`);
  }

  const queries = dashboardQueries(dashboard);
  for (const query of queries) {
    const body = await prometheusQuery(query.expr);
    const results = body.data?.result || [];
    if (requireData && !results.length) {
      throw new Error(`Dashboard query returned no series: ${query.title}`);
    }
    if (requireData && results.some(result => !Number.isFinite(Number(result.value?.[1])))) {
      throw new Error(`Dashboard query returned a non-finite value: ${query.title}`);
    }
    if (requireData && query.title === 'Service Status' && Number(results[0]?.value?.[1]) !== 1) {
      throw new Error('Dashboard service status is not UP');
    }
  }
  const resultState = requireData ? 'return runtime series' : 'are accepted by Prometheus';
  console.log(`✅ Monitoring targets, Grafana provisioning, and ${queries.length} dashboard queries ${resultState}.`);
}

async function waitForMonitoring(requireData) {
  const timeoutMs = Number(process.env.DASHBOARD_DATA_TIMEOUT_MS || 20000);
  const deadline = Date.now() + timeoutMs;
  let lastError;

  while (Date.now() < deadline) {
    try {
      await verifyMonitoring({ requireData });
      return;
    } catch (error) {
      lastError = error;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  }

  throw lastError;
}

async function waitForDashboardData() {
  return waitForMonitoring(true);
}

function sumMetricSamples(body) {
  return (body.data?.result || []).reduce((sum, sample) => {
    const value = Number(sample.value?.[1]);
    return Number.isFinite(value) ? sum + value : sum;
  }, 0);
}

async function metricSnapshot(names) {
  const snapshot = new Map();
  for (const metric of names) {
    snapshot.set(metric, sumMetricSamples(await prometheusQuery(metric)));
  }
  return snapshot;
}

async function waitForMetricActivity(before) {
  const timeoutMs = Number(process.env.METRIC_ACTIVITY_TIMEOUT_MS || 15000);
  const deadline = Date.now() + timeoutMs;
  let after = await metricSnapshot(activityMetricNames);
  let missing = activityMetricNames.filter(metric => after.get(metric) <= (before.get(metric) || 0));

  while (missing.length && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    after = await metricSnapshot(activityMetricNames);
    missing = activityMetricNames.filter(metric => after.get(metric) <= (before.get(metric) || 0));
  }

  if (missing.length) {
    throw new Error(`No activity delta after Artillery run: ${missing.join(', ')}`);
  }
}

async function verifyMetrics(before) {
  for (const metric of metricNames) {
    const body = await prometheusQuery(metric);
    if (!body.data?.result?.length) {
      throw new Error(`Metric is missing: ${metric}`);
    }
  }
  await waitForMetricActivity(before);
  console.log(`✅ ${metricNames.length} runtime metrics are available and load counters increased.`);
}

function runArtillery(mode) {
  const report = path.resolve(rootDir, process.env.ARTILLERY_REPORT || `/tmp/ktb-artillery-${mode}.json`);
  const result = spawnSync(
    'pnpm',
    ['--filter', 'e2e', 'exec', 'artillery', 'run', '--output', report, 'artillery/artillery-config.yaml'],
    {
      cwd: rootDir,
      env: {
        ...process.env,
        BASE_URL: env.BASE_URL,
        PHASE1_DURATION: process.env.PHASE1_DURATION || (mode === 'smoke' ? '5' : '60'),
        PHASE1_ARRIVAL_COUNT: process.env.PHASE1_ARRIVAL_COUNT || '1'
      },
      stdio: 'inherit'
    }
  );
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`Artillery exited with status ${result.status}`);
  }
  return report;
}

async function verifyArtilleryReport(reportPath, expectedVus = null) {
  let report;
  try {
    report = JSON.parse(await readFile(reportPath, 'utf8'));
  } catch (error) {
    throw new Error(`Artillery report is invalid: ${error.message}`);
  }

  const counters = report.aggregate?.counters || {};
  const created = Number(counters['vusers.created'] || 0);
  const completed = Number(counters['vusers.completed'] || 0);
  const failed = Number(counters['vusers.failed'] || 0);
  const errors = Object.entries(counters).filter(([name, value]) => name.startsWith('errors.') && Number(value) > 0);
  if (created < 1 || completed !== created || failed > 0 || errors.length || (expectedVus !== null && created !== expectedVus)) {
    const errorNames = errors.map(([name, value]) => `${name}=${value}`).join(', ');
    const expected = expectedVus === null ? '' : `, expected=${expectedVus}`;
    throw new Error(`Artillery acceptance failed: created=${created}, completed=${completed}, failed=${failed}${expected}${errorNames ? `, ${errorNames}` : ''}`);
  }

  const latency = report.aggregate?.summaries?.['vusers.session_length'];
  const percentiles = ['p50', 'p95', 'p99'];
  if (!latency || percentiles.some(percentile => !Number.isFinite(Number(latency[percentile])))) {
    throw new Error('Artillery report does not contain vusers.session_length p50/p95/p99');
  }
  const startTimestamps = [Number(report.aggregate?.firstMetricAt), Number(report.aggregate?.firstCounterAt)]
    .filter(Number.isFinite);
  const endTimestamps = [Number(report.aggregate?.lastMetricAt), Number(report.aggregate?.lastCounterAt)]
    .filter(Number.isFinite);
  const firstTimestamp = startTimestamps.sort((a, b) => a - b)[0];
  const lastTimestamp = endTimestamps.sort((a, b) => b - a)[0];
  const elapsedSeconds = firstTimestamp && lastTimestamp ? Number(((lastTimestamp - firstTimestamp) / 1000).toFixed(1)) : null;
  if (!elapsedSeconds || elapsedSeconds <= 0) {
    throw new Error('Artillery report does not contain measurable start/end timestamps');
  }
  console.log(`✅ Artillery report: created=${created}, completed=${completed}, p50=${latency.p50}, p95=${latency.p95}, p99=${latency.p99}`);
  return {
    startedAt: firstTimestamp ? new Date(firstTimestamp).toISOString() : null,
    completedAt: lastTimestamp ? new Date(lastTimestamp).toISOString() : null,
    elapsedSeconds,
    counters: { created, completed, failed },
    expectedVus,
    errors: Object.fromEntries(errors),
    errorRate: failed / created,
    latency: { p50: latency.p50, p95: latency.p95, p99: latency.p99 },
    throughput: {
      ...(report.aggregate?.rates || {}),
      vusersPerSecond: Number((created / elapsedSeconds).toFixed(4))
    }
  };
}

async function writeRunContext(mode, reportPath, reportSummary, startedAt, startedMs) {
  const targetUrl = parseHttpUrl('BASE_URL', env.BASE_URL).toString();
  const completedAt = reportSummary.completedAt || new Date().toISOString();
  const configuredDurationSeconds = Number(process.env.PHASE1_DURATION || (mode === 'smoke' ? 5 : 60));
  const arrivalCount = Number(process.env.PHASE1_ARRIVAL_COUNT || 1);
  const massMessageCount = Number(process.env.MASS_MESSAGE_COUNT || 10);
  const commit = commandOutput('git', ['rev-parse', 'HEAD']);
  const trackedStatus = commandOutput('git', ['status', '--porcelain', '--untracked-files=no']);
  const untracked = commandOutput('git', ['ls-files', '--others', '--exclude-standard', '--directory']);
  const status = [trackedStatus, untracked && `untracked:${untracked}`].filter(Boolean).join('\n');
  const diff = commandOutput('git', ['diff', 'HEAD', '--binary']);
  const treeFingerprint = createHash('sha256').update(`${status}\0${diff}`).digest('hex');
  const contextPath = path.resolve(
    rootDir,
    process.env.ARTILLERY_CONTEXT_REPORT || `/tmp/ktb-artillery-${mode}-context.json`
  );
  const context = {
    run: {
      mode,
      commit,
      sourceState: status ? 'dirty' : 'clean',
      sourceFingerprint: treeFingerprint,
      target: targetUrl,
      environment: process.env.LOAD_ENVIRONMENT || process.env.NODE_ENV || 'local',
      startedAt: reportSummary.startedAt || startedAt,
      completedAt,
      elapsedSeconds: reportSummary.elapsedSeconds ?? Number(((Date.now() - startedMs) / 1000).toFixed(1)),
      configuredDurationSeconds,
      arrivalCount
    },
    dataset: {
      description: process.env.LOAD_DATASET || 'generated per virtual user by the Artillery browser scenarios',
      users: reportSummary.counters.created,
      rooms: reportSummary.counters.created,
      messagesPerUser: massMessageCount + 1,
      fileUploadsPerUser: 1,
      forbiddenWordAttemptsPerUser: 1
    },
    artillery: reportSummary,
    comparison: {
      baselineId: process.env.BASELINE_ID || null,
      candidateId: process.env.CANDIDATE_ID || (status ? `${commit}+dirty-${treeFingerprint.slice(0, 12)}` : commit),
      status: process.env.BASELINE_ID ? 'ready-for-comparison' : 'baseline-pending'
    },
    reportPath
  };
  await writeFile(contextPath, `${JSON.stringify(context, null, 2)}\n`);
  console.log(`✅ Artillery context report: ${contextPath}`);
}

async function main() {
  const mode = process.argv[2] || 'verify';
  if (!['verify', 'smoke', 'artillery'].includes(mode)) {
    throw new Error('Usage: node scripts/verify-load-observability.mjs [verify|smoke|artillery]');
  }

  validateLoadTarget();
  verifyEnvironment();
  await verifyServices();
  await waitForMonitoring(false);
  if (mode !== 'verify') {
    const before = await metricSnapshot(activityMetricNames);
    const startedAt = new Date().toISOString();
    const startedMs = Date.now();
    const report = runArtillery(mode);
    const reportSummary = await verifyArtilleryReport(report, Number(process.env.PHASE1_ARRIVAL_COUNT || 1));
    await verifyMetrics(before);
    await waitForDashboardData();
    await writeRunContext(mode, report, reportSummary, startedAt, startedMs);
  }
}

main().catch(error => {
  console.error(`❌ ${error.message}`);
  process.exitCode = 1;
});
