#!/usr/bin/env node

const axios = require('axios');
const yargs = require('yargs/yargs');
const { hideBin } = require('yargs/helpers');
const { assertLoadTargetsAllowed } = require('./load-policy');

const cliArgs = hideBin(process.argv);
if (cliArgs[0] === '--') cliArgs.shift();

const argv = yargs(cliArgs)
  .option('api-url', {
    description: 'Backend REST API URL',
    type: 'string',
    default: process.env.LOAD_API_URL || 'http://localhost:5001'
  })
  .option('users', {
    alias: 'u',
    description: 'Number of 5 MiB upload flows',
    type: 'number',
    default: 1
  })
  .option('concurrency', {
    alias: 'c',
    description: 'Number of concurrent upload workers',
    type: 'number',
    default: 1
  })
  .option('setup-concurrency', {
    description: 'Concurrency used while creating users',
    type: 'number',
    default: 5
  })
  .option('size-mib', {
    description: 'Generated image size in MiB',
    type: 'number',
    default: 5
  })
  .option('timeout', {
    description: 'Request timeout in milliseconds',
    type: 'number',
    default: 30000
  })
  .option('cleanup-files', {
    description: 'Delete uploaded files after the run',
    type: 'boolean',
    default: true
  })
  .strict()
  .help()
  .parse();

const config = {
  apiUrl: argv.apiUrl.replace(/\/+$/, ''),
  users: Math.max(1, Math.floor(argv.users)),
  concurrency: Math.max(1, Math.floor(argv.concurrency)),
  setupConcurrency: Math.max(1, Math.floor(argv.setupConcurrency)),
  sizeBytes: Math.max(1, Math.floor(argv.sizeMib * 1024 * 1024)),
  timeout: Math.max(1, Math.floor(argv.timeout)),
  cleanupFiles: argv.cleanupFiles
};

assertLoadTargetsAllowed(
  [config.apiUrl],
  process.env.ALLOW_REMOTE_LOAD === 'true'
);

const password = 'LoadTest1234!';
const runId = `${Date.now()}-${process.pid}`;
const image = Buffer.alloc(config.sizeBytes);

function authHeaders(user) {
  return {
    Authorization: `Bearer ${user.token}`,
    'x-session-id': user.sessionId
  };
}

function elapsedMs(startedAt) {
  return Number(process.hrtime.bigint() - startedAt) / 1e6;
}

function percentile(values, ratio) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)];
}

async function runWorkers(items, concurrency, task) {
  let nextIndex = 0;
  async function worker() {
    while (nextIndex < items.length) {
      const index = nextIndex++;
      await task(items[index], index);
    }
  }
  await Promise.all(Array.from(
    { length: Math.min(concurrency, items.length) },
    () => worker()
  ));
}

async function prepareUsers() {
  const users = Array.from({ length: config.users }, (_, index) => ({
    email: `file-upload-${runId}-${index}@loadtest.local`,
    password,
    name: `File Upload VU ${index}`
  }));

  process.stdout.write(`Preparing ${users.length} users (excluded from metrics)... `);
  await runWorkers(users, config.setupConcurrency, async (user) => {
    await axios.post(`${config.apiUrl}/api/auth/register`, user, { timeout: config.timeout });
    const response = await axios.post(`${config.apiUrl}/api/auth/login`, {
      email: user.email,
      password: user.password
    }, { timeout: config.timeout });
    user.token = response.data.token;
    user.sessionId = response.data.sessionId;
    if (!user.token || !user.sessionId) throw new Error(`Missing auth data for ${user.email}`);
  });
  console.log('done');
  return users;
}

async function runUploadLoad(users) {
  const latencies = [];
  const completedFiles = [];
  const failures = [];
  const startedAt = process.hrtime.bigint();

  await runWorkers(users, config.concurrency, async (user, index) => {
    const flowStartedAt = process.hrtime.bigint();
    try {
      const form = new FormData();
      form.append(
        'file',
        new Blob([image], { type: 'image/jpeg' }),
        `loadtest-${runId}-${index}.jpg`
      );
      const upload = await axios.post(`${config.apiUrl}/api/files/upload`, form, {
        headers: authHeaders(user),
        timeout: config.timeout,
        maxBodyLength: Infinity
      });
      const fileId = upload.data?.file?._id;
      if (!upload.data?.success || !fileId) throw new Error('Invalid upload response');

      latencies.push(elapsedMs(flowStartedAt));
      completedFiles.push({ user, fileId });
    } catch (error) {
      failures.push(error.code || error.message);
    }
  });

  const durationSeconds = Number(process.hrtime.bigint() - startedAt) / 1e9;
  console.log('\n5 MiB multipart upload load result');
  console.log(`Target       : ${config.apiUrl}/api/files/upload`);
  console.log(`Users        : ${config.users}`);
  console.log(`Concurrency  : ${Math.min(config.concurrency, config.users)}`);
  console.log(`File size    : ${image.length} bytes`);
  console.log(`Completed    : ${latencies.length}/${config.users}`);
  console.log(`Errors       : ${failures.length}${failures.length ? ` (${failures.slice(0, 5).join(', ')})` : ''}`);
  console.log(`Duration     : ${durationSeconds.toFixed(2)} s`);
  console.log(`Throughput   : ${(config.users / durationSeconds).toFixed(2)} flows/s`);
  console.log(`Latency p50  : ${percentile(latencies, 0.50).toFixed(1)} ms`);
  console.log(`Latency p95  : ${percentile(latencies, 0.95).toFixed(1)} ms`);
  console.log(`Latency p99  : ${percentile(latencies, 0.99).toFixed(1)} ms`);

  if (config.cleanupFiles && completedFiles.length > 0) {
    process.stdout.write(`Cleaning ${completedFiles.length} files... `);
    await runWorkers(completedFiles, config.setupConcurrency, ({ user, fileId }) =>
      axios.delete(`${config.apiUrl}/api/files/${fileId}`, {
        headers: authHeaders(user),
        timeout: config.timeout
      })
    );
    console.log('done');
  }

  if (latencies.length !== config.users) process.exitCode = 1;
}

async function main() {
  console.log('Browser/Next.js/Socket.IO excluded: measuring POST /api/files/upload.');
  const users = await prepareUsers();
  await runUploadLoad(users);
}

main().catch((error) => {
  const detail = error.response
    ? `HTTP ${error.response.status}: ${JSON.stringify(error.response.data)}`
    : error.message;
  console.error(`File upload load test failed: ${detail}`);
  process.exitCode = 1;
});
