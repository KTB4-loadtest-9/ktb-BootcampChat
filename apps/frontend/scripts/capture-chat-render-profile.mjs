import { execFileSync, spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const COLLECTOR_KEY = '__KTB_CHAT_RENDER_PROFILER__';
const PROFILED_COMPONENTS = ['ChatRoomPage', 'ChatMessages', 'UserMessage'];
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendDirectory = path.resolve(scriptDirectory, '..');
const workspaceDirectory = path.resolve(frontendDirectory, '../..');
const requireFromE2E = createRequire(
  new URL('../../../e2e/package.json', import.meta.url)
);

const parseArguments = argv => {
  const options = {};

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--') continue;
    if (!argument.startsWith('--')) continue;

    const key = argument.slice(2);
    if (key === 'help') {
      options.help = true;
      continue;
    }

    const value = argv[index + 1];
    if (!value || value.startsWith('--')) {
      throw new Error(`--${key} 다음에 값을 입력해야 합니다.`);
    }

    options[key] = value;
    index += 1;
  }

  return options;
};

const printUsage = () => {
  console.log(`사용법:
  pnpm perf:capture-chat -- \\
    [--seed-messages 40] \\
    [--output "/path/to/after-profile.json"]

계정과 room을 생략하면 로컬 백엔드에 임시 사용자와 채팅방을 자동 생성한다.

선택 옵션:
  --base-url       계측용 프론트 주소 (기본: http://127.0.0.1:3100)
  --health-url     백엔드 health 주소 (기본: http://127.0.0.1:5001/api/health)
  --socket-host    Socket.IO 호스트 (기본: 127.0.0.1)
  --socket-port    Socket.IO 포트 (기본: 5002)
  --email          로그인 이메일 (환경변수 CHAT_PROFILE_EMAIL 대체 가능)
  --password       로그인 비밀번호 (환경변수 CHAT_PROFILE_PASSWORD 대체 가능)
  --room           기존 방을 사용할 때 같은 origin의 /chat/<room-id>
  --seed-messages  측정 전에 전송할 메시지 수 (자동 방: 40, 기존 방: 0)
  --message        전송할 메시지 (기본: 실행 시각 기반 고유 문자열)
  --output         결과 JSON 경로 (기본: OS 임시 디렉터리)
  --timeout        각 주요 단계 제한 시간 ms (기본: 30000)
  --reaction-handler-ref
                   비교용 useReactionHandling을 읽을 Git ref
`);
};

const isLoopbackHost = hostname => (
  hostname === '127.0.0.1' || hostname === 'localhost' || hostname === '::1'
);

const assertLocalUrl = (value, label) => {
  const url = new URL(value);
  if (!isLoopbackHost(url.hostname)) {
    throw new Error(`${label}은 로컬 주소만 허용합니다: ${url.origin}`);
  }
  return url;
};

const wait = milliseconds => new Promise(resolve => {
  setTimeout(resolve, milliseconds);
});

const logStep = message => {
  console.log(`[chat-profiler] ${message}`);
};

const checkSocketPort = ({ host, port, timeout }) => new Promise((resolve, reject) => {
  const socket = net.createConnection({ host, port: Number(port) });
  const timer = setTimeout(() => {
    socket.destroy();
    reject(new Error(`Socket.IO ${host}:${port} 연결 시간이 초과되었습니다.`));
  }, timeout);

  socket.once('connect', () => {
    clearTimeout(timer);
    socket.end();
    resolve();
  });
  socket.once('error', error => {
    clearTimeout(timer);
    reject(new Error(`Socket.IO ${host}:${port}에 연결할 수 없습니다: ${error.message}`));
  });
});

const checkBackendHealth = async (healthUrl, timeout) => {
  let response;
  try {
    response = await fetch(healthUrl, { signal: AbortSignal.timeout(timeout) });
  } catch (error) {
    throw new Error(`백엔드 health 요청에 실패했습니다: ${error.message}`);
  }

  if (!response.ok) {
    throw new Error(`백엔드 health가 HTTP ${response.status}를 반환했습니다.`);
  }
};

const isPortAvailable = ({ host, port }) => new Promise(resolve => {
  const server = net.createServer();
  server.unref();
  server.once('error', () => resolve(false));
  server.listen({ host, port: Number(port) }, () => {
    server.close(() => resolve(true));
  });
});

const createIsolatedFrontend = ({ reactionHandlerRef }) => {
  const temporaryRoot = fs.mkdtempSync(
    path.join(workspaceDirectory, '.chat-profiler-tmp-')
  );
  const temporaryFrontend = path.join(temporaryRoot, 'apps', 'frontend');
  const excludedDirectories = new Set(['.next', 'node_modules', 'coverage', 'out']);

  fs.mkdirSync(path.dirname(temporaryFrontend), { recursive: true });
  fs.cpSync(frontendDirectory, temporaryFrontend, {
    recursive: true,
    filter: source => {
      const relativePath = path.relative(frontendDirectory, source);
      const topLevelDirectory = relativePath.split(path.sep)[0];
      return !excludedDirectories.has(topLevelDirectory);
    },
  });
  fs.symlinkSync(
    path.join(frontendDirectory, 'node_modules'),
    path.join(temporaryFrontend, 'node_modules'),
    'dir'
  );

  const nextConfigPath = path.join(temporaryFrontend, 'next.config.js');
  const nextConfig = fs.readFileSync(nextConfigPath, 'utf8');
  const isolatedConfig = nextConfig
    .replace(
      'root: workspaceRoot',
      `root: ${JSON.stringify(workspaceDirectory)}`
    )
    .replace(
      'outputFileTracingRoot: workspaceRoot',
      `outputFileTracingRoot: ${JSON.stringify(workspaceDirectory)}`
    );
  if (isolatedConfig === nextConfig) {
    throw new Error('임시 프론트의 Turbopack root를 설정하지 못했습니다.');
  }
  fs.writeFileSync(nextConfigPath, isolatedConfig);

  if (reactionHandlerRef) {
    const repositoryPath = 'apps/frontend/features/chat/room/useReactionHandling.js';
    let reactionHandlerSource;
    try {
      reactionHandlerSource = execFileSync(
        'git',
        ['show', `${reactionHandlerRef}:${repositoryPath}`],
        {
          cwd: workspaceDirectory,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        }
      );
    } catch (error) {
      const details = error.stderr?.trim() || error.message;
      throw new Error(
        `${reactionHandlerRef}의 useReactionHandling을 읽지 못했습니다: ${details}`
      );
    }

    fs.writeFileSync(
      path.join(temporaryFrontend, 'features/chat/room/useReactionHandling.js'),
      reactionHandlerSource
    );
  }

  return { temporaryRoot, temporaryFrontend };
};

const removeIsolatedFrontend = async temporaryRoot => {
  const resolvedPath = path.resolve(temporaryRoot);
  const expectedPrefix = path.join(workspaceDirectory, '.chat-profiler-tmp-');
  if (!resolvedPath.startsWith(expectedPrefix)) {
    throw new Error(`임시 프론트 경로가 안전하지 않습니다: ${resolvedPath}`);
  }

  for (let attempt = 0; attempt < 10; attempt += 1) {
    try {
      fs.rmSync(resolvedPath, { recursive: true, force: true });
      return;
    } catch (error) {
      const retryable = error.code === 'EBUSY' || error.code === 'ENOTEMPTY';
      if (!retryable || attempt === 9) throw error;
      await wait(100 * (attempt + 1));
    }
  }
};

const startFrontend = ({ host, port, reactionHandlerRef }) => {
  const logs = [];
  const isolatedFrontend = createIsolatedFrontend({ reactionHandlerRef });
  const child = spawn(
    'pnpm',
    ['exec', 'next', 'dev', '--hostname', host, '--port', String(port)],
    {
      cwd: isolatedFrontend.temporaryFrontend,
      env: {
        ...process.env,
        NEXT_PUBLIC_CHAT_RENDER_PROFILING: 'true',
      },
      detached: process.platform !== 'win32',
      stdio: ['ignore', 'pipe', 'pipe'],
    }
  );

  const collectLog = chunk => {
    logs.push(chunk.toString());
    if (logs.length > 100) logs.shift();
  };
  child.stdout.on('data', collectLog);
  child.stderr.on('data', collectLog);

  return { child, logs, ...isolatedFrontend };
};

const stopFrontend = async child => {
  if (!child) return;

  const signalProcessTree = signal => {
    try {
      if (process.platform === 'win32') {
        child.kill(signal);
      } else {
        process.kill(-child.pid, signal);
      }
      return true;
    } catch (error) {
      if (error.code === 'ESRCH') return false;
      throw error;
    }
  };

  signalProcessTree('SIGTERM');
  await Promise.race([
    child.exitCode === null
      ? new Promise(resolve => child.once('exit', resolve))
      : Promise.resolve(),
    wait(2000),
  ]);

  signalProcessTree('SIGKILL');
  await wait(100);
};

const waitForFrontend = async ({ url, child, logs, timeout }) => {
  const startedAt = Date.now();

  while (Date.now() - startedAt < timeout) {
    if (child.exitCode !== null) {
      throw new Error(
        `계측용 프론트가 종료됐습니다.\n${logs.slice(-20).join('')}`
      );
    }

    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
      if (response.ok) return;
    } catch {
      // 서버가 준비될 때까지 재시도한다.
    }

    await wait(250);
  }

  throw new Error(`계측용 프론트가 ${timeout}ms 안에 준비되지 않았습니다.`);
};

const waitForCollectorToSettle = async (page, timeout = 5000) => {
  const startedAt = Date.now();
  let previousCount = -1;
  let stableSince = Date.now();

  while (Date.now() - startedAt < timeout) {
    const count = await page.evaluate(key => (
      window[key]?.entries?.length || 0
    ), COLLECTOR_KEY);

    if (count !== previousCount) {
      previousCount = count;
      stableSince = Date.now();
    } else if (Date.now() - stableSince >= 600) {
      return;
    }

    await wait(100);
  }
};

const buildProfilerExport = ({ entries, metadata }) => {
  const instances = new Map();
  let nextFiberId = 2;

  for (const entry of entries) {
    const instanceKey = `${entry.component}::${entry.instanceId || 'singleton'}`;
    if (!instances.has(instanceKey)) {
      instances.set(instanceKey, {
        id: nextFiberId,
        component: entry.component,
        instanceId: entry.instanceId,
        baseDuration: entry.baseDuration,
      });
      nextFiberId += 1;
    }
  }

  const commits = new Map();
  for (const entry of entries) {
    const commitKey = String(entry.commitTime);
    if (!commits.has(commitKey)) commits.set(commitKey, []);
    commits.get(commitKey).push(entry);
  }

  const orderedCommits = [...commits.entries()]
    .sort((left, right) => Number(left[0]) - Number(right[0]));
  const snapshots = [[1, {
    id: 1,
    children: [...instances.values()].map(instance => instance.id),
    displayName: null,
    hocDisplayNames: null,
    key: null,
    type: 11,
    compiledWithForget: false,
  }]];

  for (const instance of instances.values()) {
    snapshots.push([instance.id, {
      id: instance.id,
      children: [],
      displayName: instance.component,
      hocDisplayNames: null,
      key: instance.instanceId,
      type: 5,
      compiledWithForget: false,
    }]);
  }

  const commitData = orderedCommits.map(([commitTime, commitEntries]) => {
    const pageEntry = commitEntries.find(entry => entry.component === 'ChatRoomPage');
    const messagesEntry = commitEntries.find(entry => entry.component === 'ChatMessages');
    const durationEntry = pageEntry || messagesEntry;
    const fiberActualDurations = commitEntries.map(entry => {
      const instanceKey = `${entry.component}::${entry.instanceId || 'singleton'}`;
      return [instances.get(instanceKey).id, entry.actualDuration];
    });

    return {
      changeDescriptions: null,
      duration: durationEntry?.actualDuration || 0,
      effectDuration: 0,
      fiberActualDurations,
      fiberSelfDurations: fiberActualDurations.map(([id]) => [id, 0]),
      passiveEffectDuration: 0,
      priorityLevel: null,
      timestamp: Number(commitTime),
      updaters: [],
    };
  });

  return {
    version: 5,
    ktbChatRenderProfile: {
      schemaVersion: 1,
      captureType: 'automatic-chat-message',
      partialFiberData: true,
      profiledComponents: PROFILED_COMPONENTS,
      ...metadata,
      rawEntries: entries,
    },
    dataForRoots: [{
      commitData,
      displayName: 'AutomatedChatRenderProfile',
      initialTreeBaseDurations: [
        [1, 0],
        ...[...instances.values()].map(instance => [
          instance.id,
          instance.baseDuration || 0,
        ]),
      ],
      operations: commitData.map(() => [1, 1, 0]),
      rootID: 1,
      snapshots,
    }],
  };
};

const runCapture = async options => {
  const baseUrl = assertLocalUrl(
    options['base-url'] || 'http://127.0.0.1:3100',
    'base-url'
  );
  const healthUrl = assertLocalUrl(
    options['health-url'] || 'http://127.0.0.1:5001/api/health',
    'health-url'
  );
  const requestedEmail = options.email || process.env.CHAT_PROFILE_EMAIL;
  const requestedPassword = options.password || process.env.CHAT_PROFILE_PASSWORD;
  const requestedRoom = options.room || process.env.CHAT_PROFILE_ROOM;
  const shouldProvisionUser = !requestedEmail && !requestedPassword;
  const uniqueSuffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const email = requestedEmail || `profiler-${uniqueSuffix}@example.com`;
  const password = requestedPassword || 'Profile123!';
  const timeout = Number(options.timeout || 30000);
  const socketHost = options['socket-host'] || '127.0.0.1';
  const socketPort = Number(options['socket-port'] || 5002);
  const reactionHandlerRef = options['reaction-handler-ref'];

  if (Boolean(requestedEmail) !== Boolean(requestedPassword)) {
    throw new Error('기존 계정을 사용할 때 email과 password를 함께 제공해야 합니다.');
  }

  let roomUrl = requestedRoom ? new URL(requestedRoom, baseUrl) : null;
  if (
    roomUrl &&
    (roomUrl.origin !== baseUrl.origin || !/^\/chat\/[^/]+$/.test(roomUrl.pathname))
  ) {
    throw new Error('room은 base-url과 같은 origin의 /chat/<room-id>여야 합니다.');
  }
  const seedMessageCount = Number(
    options['seed-messages'] ?? (roomUrl ? 0 : 40)
  );
  if (!Number.isInteger(seedMessageCount) || seedMessageCount < 0) {
    throw new Error('seed-messages는 0 이상의 정수여야 합니다.');
  }

  const frontendPort = Number(baseUrl.port || 80);
  if (!await isPortAvailable({ host: baseUrl.hostname, port: frontendPort })) {
    throw new Error(
      `${baseUrl.origin} 포트가 이미 사용 중입니다. 계측 전용 포트를 지정해주세요.`
    );
  }

  logStep(`백엔드 health 확인: ${healthUrl.origin}${healthUrl.pathname}`);
  await checkBackendHealth(healthUrl, Math.min(timeout, 5000));
  logStep(`Socket.IO 포트 확인: ${socketHost}:${socketPort}`);
  await checkSocketPort({
    host: socketHost,
    port: socketPort,
    timeout: Math.min(timeout, 5000),
  });

  const { chromium } = requireFromE2E('@playwright/test');
  const frontend = startFrontend({
    host: baseUrl.hostname,
    port: frontendPort,
    reactionHandlerRef,
  });
  let browser;

  try {
    logStep(`격리된 계측용 프론트 시작: ${baseUrl.origin}`);
    await waitForFrontend({
      url: new URL('/', baseUrl).href,
      child: frontend.child,
      logs: frontend.logs,
      timeout,
    });
    logStep('계측용 프론트 준비 완료');

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    page.on('console', message => {
      if (message.type() === 'error') {
        console.error(`[browser] ${message.text()}`);
      }
    });

    if (shouldProvisionUser) {
      logStep('임시 사용자 생성');
      await page.goto(new URL('/register', baseUrl).href, {
        waitUntil: 'domcontentloaded',
      });
      await page.getByTestId('register-name-input').fill('Profiler User');
      await page.getByTestId('register-email-input').fill(email);
      await page.getByTestId('register-password-input').fill(password);
      await page.getByTestId('register-password-confirm-input').fill(password);
      await page.getByTestId('register-submit-button').click();
      await page.waitForURL(url => url.pathname === '/', { timeout });
    }

    logStep('로그인');
    await page.goto(new URL('/', baseUrl).href, { waitUntil: 'domcontentloaded' });
    await page.getByTestId('login-email-input').fill(email);
    await page.getByTestId('login-password-input').fill(password);
    await page.getByTestId('login-submit-button').click();
    await page.waitForURL(url => url.pathname === '/chat', { timeout });

    if (roomUrl) {
      logStep(`기존 채팅방 입장: ${roomUrl.pathname}`);
      await page.goto(roomUrl.href, { waitUntil: 'domcontentloaded' });
    } else {
      logStep('임시 채팅방 생성');
      await page.goto(new URL('/chat/new', baseUrl).href, {
        waitUntil: 'domcontentloaded',
      });
      await page.getByTestId('chat-room-name-input').fill(`Profiler Room ${uniqueSuffix}`);
      await page.getByTestId('create-chat-room-button').click();
      await page.waitForURL(
        url => (
          url.pathname !== '/chat/new' &&
          /^\/chat\/[^/]+$/.test(url.pathname)
        ),
        { timeout }
      );
      roomUrl = new URL(page.url());
    }

    const messageInput = page.getByTestId('chat-message-input');
    await messageInput.waitFor({ state: 'visible', timeout });
    await page.getByTestId('chat-messages-container').waitFor({
      state: 'visible',
      timeout,
    });
    logStep('채팅방 UI와 Socket.IO 준비 완료');

    if (seedMessageCount > 0) {
      logStep(`측정 전 메시지 ${seedMessageCount}개 준비`);
    }
    for (let index = 0; index < seedMessageCount; index += 1) {
      const seedMessage = `profile-seed-${index + 1}-${uniqueSuffix}`;
      await messageInput.fill(seedMessage);
      await page.getByTestId('chat-send-button').click();
      await page.getByTestId('message-content')
        .filter({ hasText: seedMessage })
        .last()
        .waitFor({ state: 'visible', timeout });
      if ((index + 1) % 10 === 0 || index + 1 === seedMessageCount) {
        logStep(`준비 메시지 ${index + 1}/${seedMessageCount}`);
      }
    }

    const initialMessageCount = await page.getByTestId('message-container').count();
    const message = options.message || `profile-${Date.now().toString(36)}`;
    await messageInput.fill(message);

    logStep('Profiler 측정 시작');
    const startedAt = await page.evaluate(key => {
      const collector = window[key];
      if (!collector) {
        throw new Error('React Profiler collector가 초기화되지 않았습니다.');
      }
      collector.entries = [];
      collector.startedAt = performance.now();
      collector.stoppedAt = null;
      collector.active = true;
      return collector.startedAt;
    }, COLLECTOR_KEY);

    await page.getByTestId('chat-send-button').click();
    await page.getByTestId('message-content')
      .filter({ hasText: message })
      .last()
      .waitFor({ state: 'visible', timeout });
    await waitForCollectorToSettle(page);

    const collector = await page.evaluate(key => {
      const value = window[key];
      value.active = false;
      value.stoppedAt = performance.now();
      return {
        entries: value.entries,
        startedAt: value.startedAt,
        stoppedAt: value.stoppedAt,
      };
    }, COLLECTOR_KEY);
    const finalMessageCount = await page.getByTestId('message-container').count();
    const browserVersion = browser.version();
    const outputPath = path.resolve(
      options.output || path.join(
        os.tmpdir(),
        `ktb-chat-render-profile-${Date.now()}.json`
      )
    );
    const profile = buildProfilerExport({
      entries: collector.entries,
      metadata: {
        createdAt: new Date().toISOString(),
        browserVersion,
        scenario: {
          baseUrl: baseUrl.origin,
          roomPath: roomUrl.pathname,
          message,
          initialMessageCount,
          finalMessageCount,
          seedMessageCount,
          provisionedUser: shouldProvisionUser,
          provisionedRoom: !requestedRoom,
          reactionHandlerRef: reactionHandlerRef || 'working-tree',
          startedAt,
          stoppedAt: collector.stoppedAt,
          duration: collector.stoppedAt - startedAt,
        },
      },
    });

    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, `${JSON.stringify(profile, null, 2)}\n`, {
      flag: 'wx',
    });

    logStep('Profiler 측정 종료');
    console.log(`자동 Profiler JSON을 저장했습니다: ${outputPath}`);
    console.log(`메시지 수: ${initialMessageCount} → ${finalMessageCount}`);
    console.log(`Profiler entry: ${collector.entries.length}개`);
    return outputPath;
  } finally {
    await browser?.close();
    await stopFrontend(frontend.child);
    await removeIsolatedFrontend(frontend.temporaryRoot);
  }
};

try {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    printUsage();
  } else {
    await runCapture(options);
  }
} catch (error) {
  console.error(`자동 Profiler 캡처 실패: ${error.message}`);
  process.exitCode = 1;
}
