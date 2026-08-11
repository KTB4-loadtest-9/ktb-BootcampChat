import { execFileSync, spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const METRICS_KEY = '__KTB_CHAT_RESOURCE_METRICS__';
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendDirectory = path.resolve(scriptDirectory, '..');
const workspaceDirectory = path.resolve(frontendDirectory, '../..');
const requireFromE2E = createRequire(
  new URL('../../../e2e/package.json', import.meta.url)
);
const baselineFiles = [
  'apps/frontend/components/UserMessage.js',
  'apps/frontend/components/FileMessage.js',
  'apps/frontend/components/ReadStatus.js',
];

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
  pnpm perf:read-resources -- \\
    [--message-count 20] \\
    [--before-ref HEAD] \\
    [--output /tmp/ktb-chat-read-resources.json]

로컬 백엔드에서 개선 전 Git ref와 현재 작업 트리를 같은 2-user 채팅
시나리오로 실행해 listener, IntersectionObserver, 읽음 Socket 호출을 비교한다.

선택 옵션:
  --base-url       격리 프론트 주소 (기본: http://127.0.0.1:3100)
  --health-url     백엔드 health 주소 (기본: http://127.0.0.1:5001/api/health)
  --socket-host    Socket.IO 호스트 (기본: 127.0.0.1)
  --socket-port    Socket.IO 포트 (기본: 5002)
  --message-count  작성자가 준비할 메시지 수 (기본: 20, 최대: 30)
  --before-ref     개선 전 컴포넌트를 읽을 Git ref (기본: HEAD)
  --output         원본 JSON 경로 (Markdown은 같은 이름으로 생성)
  --timeout        각 주요 단계 제한 시간 ms (기본: 30000)
`);
};

const wait = milliseconds => new Promise(resolve => {
  setTimeout(resolve, milliseconds);
});

const logStep = message => {
  console.log(`[read-resources] ${message}`);
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

const isPortAvailable = ({ host, port }) => new Promise(resolve => {
  const server = net.createServer();
  server.unref();
  server.once('error', () => resolve(false));
  server.listen({ host, port: Number(port) }, () => {
    server.close(() => resolve(true));
  });
});

const readGitFile = (sourceRef, repositoryPath) => {
  try {
    return execFileSync('git', ['show', `${sourceRef}:${repositoryPath}`], {
      cwd: workspaceDirectory,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    const details = error.stderr?.trim() || error.message;
    throw new Error(`${sourceRef}:${repositoryPath}를 읽지 못했습니다: ${details}`);
  }
};

const resolveGitRef = sourceRef => {
  try {
    return execFileSync('git', ['rev-parse', sourceRef], {
      cwd: workspaceDirectory,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
  } catch (error) {
    const details = error.stderr?.trim() || error.message;
    throw new Error(`${sourceRef} Git ref를 확인하지 못했습니다: ${details}`);
  }
};

const instrumentSocketService = temporaryFrontend => {
  const socketServicePath = path.join(temporaryFrontend, 'services/socket.js');
  const socketService = fs.readFileSync(socketServicePath, 'utf8');
  const emitStatement = '    this.socket.emit(event, data);';
  const instrumentation = `    if (typeof window !== 'undefined' && event === 'markMessagesAsRead') {
      const metrics = window['${METRICS_KEY}'];
      if (metrics) {
        const messageIds = Array.isArray(data?.messageIds) ? data.messageIds : [];
        metrics.readReceiptSocketCalls += 1;
        metrics.readReceiptIdsSent += messageIds.length;
        metrics.readReceiptBatches.push([...messageIds]);
      }
    }

${emitStatement}`;

  if (!socketService.includes(emitStatement)) {
    throw new Error('임시 프론트의 Socket send 계측 위치를 찾지 못했습니다.');
  }

  fs.writeFileSync(
    socketServicePath,
    socketService.replace(emitStatement, instrumentation)
  );
};

const createIsolatedFrontend = ({ sourceRef }) => {
  const temporaryRoot = fs.mkdtempSync(
    path.join(workspaceDirectory, '.chat-resource-tmp-')
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

  if (sourceRef) {
    for (const repositoryPath of baselineFiles) {
      const relativePath = repositoryPath.replace('apps/frontend/', '');
      fs.writeFileSync(
        path.join(temporaryFrontend, relativePath),
        readGitFile(sourceRef, repositoryPath)
      );
    }
  }

  instrumentSocketService(temporaryFrontend);
  return { temporaryRoot, temporaryFrontend };
};

const removeIsolatedFrontend = async temporaryRoot => {
  const resolvedPath = path.resolve(temporaryRoot);
  const expectedPrefix = path.join(workspaceDirectory, '.chat-resource-tmp-');
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

const startFrontend = ({ host, port, sourceRef }) => {
  const logs = [];
  const isolatedFrontend = createIsolatedFrontend({ sourceRef });
  const child = spawn(
    'pnpm',
    ['exec', 'next', 'dev', '--hostname', host, '--port', String(port)],
    {
      cwd: isolatedFrontend.temporaryFrontend,
      env: process.env,
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
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
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
      throw new Error(`계측용 프론트가 종료됐습니다.\n${logs.slice(-20).join('')}`);
    }

    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
      if (response.ok) return;
    } catch {
      // 서버 준비 전 연결 실패는 재시도한다.
    }
    await wait(250);
  }

  throw new Error(`계측용 프론트가 ${timeout}ms 안에 준비되지 않았습니다.`);
};

const browserInstrumentation = metricsKey => {
  const metrics = {
    avatarListenerAdds: 0,
    avatarListenersActive: 0,
    avatarListenersPeak: 0,
    readObserverConstructors: 0,
    readObserversActive: 0,
    readObserversPeak: 0,
    readObserveCalls: 0,
    readTargetsActive: 0,
    readTargetsPeak: 0,
    readReceiptSocketCalls: 0,
    readReceiptIdsSent: 0,
    readReceiptBatches: [],
  };
  window[metricsKey] = metrics;

  const avatarListeners = new Set();
  const originalAddEventListener = window.addEventListener.bind(window);
  const originalRemoveEventListener = window.removeEventListener.bind(window);

  window.addEventListener = (type, listener, options) => {
    if (type === 'userProfileUpdate' && !avatarListeners.has(listener)) {
      avatarListeners.add(listener);
      metrics.avatarListenerAdds += 1;
      metrics.avatarListenersActive += 1;
      metrics.avatarListenersPeak = Math.max(
        metrics.avatarListenersPeak,
        metrics.avatarListenersActive
      );
    }
    return originalAddEventListener(type, listener, options);
  };

  window.removeEventListener = (type, listener, options) => {
    if (type === 'userProfileUpdate' && avatarListeners.delete(listener)) {
      metrics.avatarListenersActive -= 1;
    }
    return originalRemoveEventListener(type, listener, options);
  };

  const NativeIntersectionObserver = window.IntersectionObserver;
  if (!NativeIntersectionObserver) return;

  const InstrumentedIntersectionObserver = function (callback, options = {}) {
    const thresholds = Array.isArray(options.threshold)
      ? options.threshold
      : [options.threshold];
    const isReadObserver = thresholds.includes(0.5);
    const observer = new NativeIntersectionObserver(callback, options);

    if (!isReadObserver) return observer;

    metrics.readObserverConstructors += 1;
    metrics.readObserversActive += 1;
    metrics.readObserversPeak = Math.max(
      metrics.readObserversPeak,
      metrics.readObserversActive
    );

    let active = true;
    const targets = new Set();
    const originalObserve = observer.observe.bind(observer);
    const originalUnobserve = observer.unobserve.bind(observer);
    const originalDisconnect = observer.disconnect.bind(observer);

    observer.observe = target => {
      if (!targets.has(target)) {
        targets.add(target);
        metrics.readObserveCalls += 1;
        metrics.readTargetsActive += 1;
        metrics.readTargetsPeak = Math.max(
          metrics.readTargetsPeak,
          metrics.readTargetsActive
        );
      }
      return originalObserve(target);
    };

    observer.unobserve = target => {
      if (targets.delete(target)) metrics.readTargetsActive -= 1;
      return originalUnobserve(target);
    };

    observer.disconnect = () => {
      metrics.readTargetsActive -= targets.size;
      targets.clear();
      if (active) {
        active = false;
        metrics.readObserversActive -= 1;
      }
      return originalDisconnect();
    };

    return observer;
  };

  InstrumentedIntersectionObserver.prototype = NativeIntersectionObserver.prototype;
  Object.setPrototypeOf(InstrumentedIntersectionObserver, NativeIntersectionObserver);
  window.IntersectionObserver = InstrumentedIntersectionObserver;
};

const registerAndLogin = async ({ page, baseUrl, email, password, name, timeout }) => {
  await page.goto(new URL('/register', baseUrl).href, {
    waitUntil: 'domcontentloaded',
  });
  await page.getByTestId('register-name-input').fill(name);
  await page.getByTestId('register-email-input').fill(email);
  await page.getByTestId('register-password-input').fill(password);
  await page.getByTestId('register-password-confirm-input').fill(password);
  await page.getByTestId('register-submit-button').click();
  await page.waitForURL(url => url.pathname === '/', { timeout });

  await page.getByTestId('login-email-input').fill(email);
  await page.getByTestId('login-password-input').fill(password);
  await page.getByTestId('login-submit-button').click();
  await page.waitForURL(url => url.pathname === '/chat', { timeout });
};

const waitForMetricSettle = async (page, timeout = 3000) => {
  const startedAt = Date.now();
  let previousValue = '';
  let stableSince = Date.now();

  while (Date.now() - startedAt < timeout) {
    const value = await page.evaluate(metricsKey => {
      const metrics = window[metricsKey];
      return JSON.stringify([
        metrics.readReceiptSocketCalls,
        metrics.readReceiptIdsSent,
        metrics.readObserversActive,
        metrics.readTargetsActive,
      ]);
    }, METRICS_KEY);

    if (value !== previousValue) {
      previousValue = value;
      stableSince = Date.now();
    } else if (Date.now() - stableSince >= 500) {
      return;
    }
    await wait(50);
  }
};

const runScenario = async ({
  baseUrl,
  sourceRef,
  label,
  messageCount,
  timeout,
}) => {
  const frontendPort = Number(baseUrl.port || 80);
  if (!await isPortAvailable({ host: baseUrl.hostname, port: frontendPort })) {
    throw new Error(`${baseUrl.origin} 포트가 이미 사용 중입니다.`);
  }

  const frontend = startFrontend({
    host: baseUrl.hostname,
    port: frontendPort,
    sourceRef,
  });
  let browser;

  try {
    logStep(`${label}: 격리 프론트 시작 (${sourceRef || 'working-tree'})`);
    await waitForFrontend({
      url: new URL('/', baseUrl).href,
      child: frontend.child,
      logs: frontend.logs,
      timeout,
    });

    const { chromium } = requireFromE2E('@playwright/test');
    browser = await chromium.launch({ headless: true });
    const uniqueSuffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const password = 'Profile123!';

    const authorContext = await browser.newContext({
      viewport: { width: 1280, height: 10000 },
    });
    const authorPage = await authorContext.newPage();
    await registerAndLogin({
      page: authorPage,
      baseUrl,
      email: `resource-author-${uniqueSuffix}@example.com`,
      password,
      name: 'Resource Author',
      timeout,
    });

    await authorPage.goto(new URL('/chat/new', baseUrl).href, {
      waitUntil: 'domcontentloaded',
    });
    await authorPage.getByTestId('chat-room-name-input')
      .fill(`Resource Room ${uniqueSuffix}`);
    await authorPage.getByTestId('create-chat-room-button').click();
    await authorPage.waitForURL(
      url => url.pathname !== '/chat/new' && /^\/chat\/[^/]+$/.test(url.pathname),
      { timeout }
    );
    const roomUrl = new URL(authorPage.url());
    const authorInput = authorPage.getByTestId('chat-message-input');
    await authorInput.waitFor({ state: 'visible', timeout });

    for (let index = 0; index < messageCount; index += 1) {
      const message = `resource-message-${index + 1}-${uniqueSuffix}`;
      await authorInput.fill(message);
      await authorPage.getByTestId('chat-send-button').click();
      await authorPage.getByTestId('message-content')
        .filter({ hasText: message })
        .last()
        .waitFor({ state: 'visible', timeout });
    }
    logStep(`${label}: 작성자 메시지 ${messageCount}개 준비`);

    const readerContext = await browser.newContext({
      viewport: { width: 1280, height: 10000 },
    });
    await readerContext.addInitScript(browserInstrumentation, METRICS_KEY);
    const readerPage = await readerContext.newPage();
    await registerAndLogin({
      page: readerPage,
      baseUrl,
      email: `resource-reader-${uniqueSuffix}@example.com`,
      password,
      name: 'Resource Reader',
      timeout,
    });

    await readerPage.goto(roomUrl.href, { waitUntil: 'domcontentloaded' });
    await readerPage.getByTestId('chat-messages-container')
      .waitFor({ state: 'visible', timeout });
    await readerPage.waitForFunction(expectedCount => (
      document.querySelectorAll('[data-testid="message-container"]').length >= expectedCount
    ), messageCount, { timeout });
    await waitForMetricSettle(readerPage);

    const metrics = await readerPage.evaluate(metricsKey => ({
      ...window[metricsKey],
      renderedMessages: document.querySelectorAll(
        '[data-testid="message-container"]'
      ).length,
    }), METRICS_KEY);

    await readerContext.close();
    await authorContext.close();
    logStep(
      `${label}: listener peak ${metrics.avatarListenersPeak}, ` +
      `observer peak ${metrics.readObserversPeak}, ` +
      `read Socket ${metrics.readReceiptSocketCalls}회`
    );
    return {
      label,
      sourceRef: sourceRef || 'working-tree',
      roomPath: roomUrl.pathname,
      messageCount,
      browserVersion: browser.version(),
      metrics,
    };
  } finally {
    await browser?.close();
    await stopFrontend(frontend.child);
    await removeIsolatedFrontend(frontend.temporaryRoot);
  }
};

const improvement = (before, after) => {
  if (before === 0) return after === 0 ? 0 : null;
  return Math.round(((before - after) / before) * 10000) / 100;
};

const formatImprovement = value => (
  value === null ? 'n/a' : `${value >= 0 ? '+' : ''}${value}%`
);

const renderMarkdown = report => {
  const rows = [
    ['Avatar listener peak', 'avatarListenersPeak'],
    ['ReadStatus Observer 생성', 'readObserverConstructors'],
    ['ReadStatus Observer peak', 'readObserversPeak'],
    ['읽음 Socket 호출', 'readReceiptSocketCalls'],
    ['읽음 ID 전송', 'readReceiptIdsSent'],
  ];
  const lines = [
    '# 채팅 리소스·읽음 batching 자동 비교',
    '',
    `- 생성 시각: ${report.createdAt}`,
    `- Before: ${report.before.sourceRef}`,
    `- After: ${report.after.sourceRef}`,
    `- 브라우저: ${report.after.browserVersion}`,
    `- 준비 메시지: ${report.config.messageCount}개`,
    `- 실행 횟수: 1회 paired run`,
    '',
    '| 지표 | Before | After | 개선율 |',
    '| --- | ---: | ---: | ---: |',
  ];

  for (const [label, key] of rows) {
    const before = report.before.metrics[key];
    const after = report.after.metrics[key];
    lines.push(
      `| ${label} | ${before} | ${after} | ` +
      `${formatImprovement(improvement(before, after))} |`
    );
  }

  lines.push(
    '',
    `- Before batch 크기: \`${report.before.metrics.readReceiptBatches.map(batch => batch.length).join(', ')}\``,
    `- After batch 크기: \`${report.after.metrics.readReceiptBatches.map(batch => batch.length).join(', ')}\``,
    '',
    '> Next.js 개발 모드의 React Strict Mode에서는 effect lifecycle이 재실행되어 Observer 총 생성 횟수가 늘어날 수 있다. 실제 동시 보유 비용은 Observer peak를 기준으로 비교한다.',
    '',
    '> 이 비교는 브라우저 리소스 수와 Socket 호출 증폭을 측정한다. 서버 Max RPS나 VUser break point 개선을 의미하지 않는다.'
  );
  return `${lines.join('\n')}\n`;
};

const runComparison = async options => {
  const baseUrl = assertLocalUrl(
    options['base-url'] || 'http://127.0.0.1:3100',
    'base-url'
  );
  const healthUrl = assertLocalUrl(
    options['health-url'] || 'http://127.0.0.1:5001/api/health',
    'health-url'
  );
  const socketHost = options['socket-host'] || '127.0.0.1';
  const socketPort = Number(options['socket-port'] || 5002);
  const timeout = Number(options.timeout || 30000);
  const messageCount = Number(options['message-count'] || 20);
  const requestedBeforeRef = options['before-ref'] || 'HEAD';
  const beforeRef = resolveGitRef(requestedBeforeRef);

  if (!Number.isInteger(messageCount) || messageCount < 1 || messageCount > 30) {
    throw new Error('message-count는 1 이상 30 이하의 정수여야 합니다.');
  }

  logStep(`백엔드 health 확인: ${healthUrl.href}`);
  await checkBackendHealth(healthUrl, Math.min(timeout, 5000));
  await checkSocketPort({
    host: socketHost,
    port: socketPort,
    timeout: Math.min(timeout, 5000),
  });

  const before = await runScenario({
    baseUrl,
    sourceRef: beforeRef,
    label: 'Before',
    messageCount,
    timeout,
  });
  const after = await runScenario({
    baseUrl,
    sourceRef: null,
    label: 'After',
    messageCount,
    timeout,
  });

  const outputPath = path.resolve(
    options.output || path.join(
      os.tmpdir(),
      `ktb-chat-read-resources-${Date.now()}.json`
    )
  );
  const markdownPath = outputPath.endsWith('.json')
    ? outputPath.replace(/\.json$/, '.md')
    : `${outputPath}.md`;
  const report = {
    schemaVersion: 1,
    createdAt: new Date().toISOString(),
    config: {
      baseUrl: baseUrl.origin,
      healthUrl: healthUrl.href,
      socketHost,
      socketPort,
      messageCount,
      beforeRef,
      requestedBeforeRef,
      timeout,
    },
    before,
    after,
  };

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, {
    flag: 'wx',
  });
  fs.writeFileSync(markdownPath, renderMarkdown(report), { flag: 'wx' });

  console.log(`원본 JSON: ${outputPath}`);
  console.log(`비교 보고서: ${markdownPath}`);
  console.log(
    `읽음 Socket 호출: ${before.metrics.readReceiptSocketCalls} → ` +
    `${after.metrics.readReceiptSocketCalls}`
  );
  return report;
};

try {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    printUsage();
  } else {
    await runComparison(options);
  }
} catch (error) {
  console.error(`채팅 리소스 비교 실패: ${error.message}`);
  process.exitCode = 1;
}
