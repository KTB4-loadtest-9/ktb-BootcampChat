import fs from 'node:fs';
import path from 'node:path';

const TARGET_COMPONENTS = [
  'ChatRoomPage',
  'ChatRoomView',
  'ChatMessages',
  'Memo(ChatMessages)',
  'UserMessage',
  'Memo(UserMessage)',
  'MessageActions',
  'ReadStatus',
  'CustomAvatar',
  'ChatInput',
  'ChatRoomInfo',
  'ChatHeader',
];

const parseArguments = argv => {
  const options = {};

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--') continue;
    if (!argument.startsWith('--')) continue;

    const key = argument.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) {
      throw new Error(`--${key} 다음에 값을 입력해야 합니다.`);
    }

    options[key] = value;
    index += 1;
  }

  return options;
};

const percentile = (values, ratio) => {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.floor((sorted.length - 1) * ratio)];
};

const average = values => (
  values.length === 0
    ? 0
    : values.reduce((sum, value) => sum + value, 0) / values.length
);

const round = value => Math.round(value * 100) / 100;

const decodeStringTable = operations => {
  let index = 3;
  const strings = [null];
  const end = index + operations[2];

  while (index < end) {
    const length = operations[index];
    index += 1;
    strings.push(String.fromCodePoint(...operations.slice(index, index + length)));
    index += length;
  }

  return { index, strings };
};

const applyOperations = (operations, nodes, commitIndex) => {
  const decoded = decodeStringTable(operations);
  const strings = decoded.strings;
  let index = decoded.index;

  while (index < operations.length) {
    const operation = operations[index];

    if (operation === 1) {
      const id = operations[index + 1];
      const type = operations[index + 2];

      if (type === 11) {
        nodes.set(id, null);
        index += 7;
      } else {
        nodes.set(id, strings[operations[index + 5]]);
        index += 8;
      }
    } else if (operation === 2) {
      const removeCount = operations[index + 1];
      for (let offset = 0; offset < removeCount; offset += 1) {
        nodes.delete(operations[index + 2 + offset]);
      }
      index += 2 + removeCount;
    } else if (operation === 3 || operation === 10) {
      index += 3 + operations[index + 2];
    } else if (operation === 4 || operation === 7) {
      index += 3;
    } else if (operation === 5) {
      index += 4;
    } else if (operation === 6) {
      index += 1;
    } else if (operation === 8) {
      const rectangleCount = operations[index + 5];
      index += 6 + (rectangleCount === -1 ? 0 : 4 * rectangleCount);
    } else if (operation === 9) {
      index += 2 + operations[index + 1];
    } else if (operation === 11) {
      const rectangleCount = operations[index + 2];
      index += 3 + (rectangleCount === -1 ? 0 : 4 * rectangleCount);
    } else if (operation === 12) {
      index += 1;
      const changeCount = operations[index];
      index += 1;

      for (let changeIndex = 0; changeIndex < changeCount; changeIndex += 1) {
        index += 3;
        const environmentCount = operations[index];
        index += 1 + environmentCount;
      }
    } else {
      throw new Error(
        `지원하지 않는 React DevTools operation ${operation} ` +
        `(commit ${commitIndex}, offset ${index})`
      );
    }
  }
};

const summarizeCommits = commits => {
  const durations = commits.map(commit => commit.duration);
  const fiberCounts = commits.map(commit => commit.fiberCount);

  return {
    count: commits.length,
    durationAverage: round(average(durations)),
    durationP50: round(percentile(durations, 0.5)),
    durationP95: round(percentile(durations, 0.95)),
    durationP99: round(percentile(durations, 0.99)),
    durationMax: round(percentile(durations, 1)),
    over16ms: durations.filter(duration => duration > 16).length,
    over50ms: durations.filter(duration => duration > 50).length,
    fiberAverage: round(average(fiberCounts)),
    fiberP95: round(percentile(fiberCounts, 0.95)),
    fiberMax: round(percentile(fiberCounts, 1)),
  };
};

const analyzeRoot = root => {
  const nodes = new Map(
    root.snapshots.map(([id, snapshot]) => [id, snapshot.displayName])
  );
  const componentTotals = new Map();
  const commits = [];

  for (let commitIndex = 0; commitIndex < root.commitData.length; commitIndex += 1) {
    applyOperations(root.operations[commitIndex], nodes, commitIndex);

    const commitData = root.commitData[commitIndex];
    const selfDurations = new Map(commitData.fiberSelfDurations);
    const components = new Map();

    for (const [fiberId, actualDuration] of commitData.fiberActualDurations) {
      const componentName = nodes.get(fiberId) || '(filtered/anonymous)';
      const selfDuration = selfDurations.get(fiberId) || 0;
      const commitComponent = components.get(componentName) || {
        count: 0,
        actualDuration: 0,
        selfDuration: 0,
      };
      commitComponent.count += 1;
      commitComponent.actualDuration += actualDuration;
      commitComponent.selfDuration += selfDuration;
      components.set(componentName, commitComponent);

      const total = componentTotals.get(componentName) || {
        count: 0,
        actualDuration: 0,
        selfDuration: 0,
        peakPerCommit: 0,
      };
      total.count += 1;
      total.actualDuration += actualDuration;
      total.selfDuration += selfDuration;
      componentTotals.set(componentName, total);
    }

    for (const [componentName, component] of components) {
      const total = componentTotals.get(componentName);
      total.peakPerCommit = Math.max(total.peakPerCommit, component.count);
    }

    commits.push({
      index: commitIndex,
      timestamp: commitData.timestamp,
      duration: commitData.duration,
      fiberCount: commitData.fiberActualDurations.length,
      components,
    });
  }

  const chatCommits = commits.filter(commit => (
    commit.components.has('ChatMessages') ||
    commit.components.has('Memo(ChatMessages)')
  ));
  const firstTimestamp = commits[0]?.timestamp || 0;
  const lastTimestamp = commits.at(-1)?.timestamp || firstTimestamp;

  const componentMetrics = Object.fromEntries(
    TARGET_COMPONENTS.map(componentName => {
      const total = componentTotals.get(componentName) || {
        count: 0,
        actualDuration: 0,
        selfDuration: 0,
        peakPerCommit: 0,
      };
      const chatCounts = chatCommits.map(
        commit => commit.components.get(componentName)?.count || 0
      );

      return [componentName, {
        totalRenders: total.count,
        rendersPerChatCommit: round(average(chatCounts)),
        p95PerChatCommit: round(percentile(chatCounts, 0.95)),
        peakPerCommit: total.peakPerCommit,
        totalSelfDuration: round(total.selfDuration),
      }];
    })
  );

  return {
    rootName: root.displayName || `Root ${root.rootID}`,
    recordingDuration: round(lastTimestamp - firstTimestamp),
    whyRenderCommitCount: root.commitData.filter(
      commit => commit.changeDescriptions !== null
    ).length,
    all: summarizeCommits(commits),
    chat: summarizeCommits(chatCommits),
    nonChat: summarizeCommits(
      commits.filter(commit => !chatCommits.includes(commit))
    ),
    componentMetrics,
  };
};

const readProfile = filePath => {
  const absolutePath = path.resolve(filePath);
  const profile = JSON.parse(fs.readFileSync(absolutePath, 'utf8'));

  if (!Array.isArray(profile.dataForRoots) || profile.dataForRoots.length === 0) {
    throw new Error(`${absolutePath}에 React Profiler root 데이터가 없습니다.`);
  }

  const root = [...profile.dataForRoots].sort(
    (left, right) => right.commitData.length - left.commitData.length
  )[0];

  const captureMetadata = profile.ktbChatRenderProfile;

  return {
    file: absolutePath,
    exportVersion: profile.version,
    rootCount: profile.dataForRoots.length,
    captureType: captureMetadata?.captureType || 'react-devtools-export',
    partialFiberData: captureMetadata?.partialFiberData || false,
    profiledComponents: captureMetadata?.profiledComponents || TARGET_COMPONENTS,
    browserVersion: captureMetadata?.browserVersion || null,
    scenario: captureMetadata?.scenario || null,
    ...analyzeRoot(root),
  };
};

const formatNumber = value => new Intl.NumberFormat('ko-KR', {
  maximumFractionDigits: 2,
}).format(value);

const formatDelta = (before, after) => {
  if (before === 0) return formatNumber(after - before);
  const improvement = ((before - after) / before) * 100;
  const prefix = improvement > 0 ? '+' : '';
  return `${prefix}${formatNumber(improvement)}%`;
};

const renderProfile = (label, result) => {
  const lines = [
    `## ${label}`,
    '',
    `- 파일: \`${result.file}\``,
    `- Export version: ${result.exportVersion}`,
    `- 캡처 방식: ${result.captureType}`,
    `- 선택 root: ${result.rootName} (${result.rootCount}개 root 중 commit이 가장 많은 root)`,
    `- 기록 시간: ${formatNumber(result.recordingDuration)}ms`,
    `- render reason 기록 commit: ${result.whyRenderCommitCount}개`,
  ];

  if (result.scenario) {
    lines.push(
      `- 메시지 수: ${result.scenario.initialMessageCount} → ${result.scenario.finalMessageCount}`,
      `- 준비 메시지: ${result.scenario.seedMessageCount}개`,
      `- reaction handler: ${result.scenario.reactionHandlerRef}`,
      `- 브라우저: ${result.browserVersion}`
    );
  }

  lines.push(
    '',
    '| 구간 | Commit | 평균 | P50 | P95 | P99 | 최대 | Fiber 평균 | Fiber P95 | Fiber 최대 |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'
  );

  for (const [name, metrics] of [
    ['전체', result.all],
    ['채팅', result.chat],
    ['비채팅', result.nonChat],
  ]) {
    lines.push(
      `| ${name} | ${metrics.count} | ${formatNumber(metrics.durationAverage)}ms | ` +
      `${formatNumber(metrics.durationP50)}ms | ${formatNumber(metrics.durationP95)}ms | ` +
      `${formatNumber(metrics.durationP99)}ms | ${formatNumber(metrics.durationMax)}ms | ` +
      `${formatNumber(metrics.fiberAverage)} | ${formatNumber(metrics.fiberP95)} | ` +
      `${formatNumber(metrics.fiberMax)} |`
    );
  }

  if (result.partialFiberData) {
    lines.push(
      '',
      '> Fiber 수는 자동 계측 대상으로 지정한 컴포넌트만 포함한 부분 값이다. React DevTools 전체 Fiber 수와 직접 비교하지 않는다.'
    );
  }

  lines.push(
    '',
    '| 컴포넌트 | 전체 렌더 | 채팅 commit당 평균 | 채팅 commit당 P95 | commit당 최대 | Self time 합계 |',
    '| --- | ---: | ---: | ---: | ---: | ---: |'
  );

  for (const componentName of result.profiledComponents) {
    const metrics = result.componentMetrics[componentName];
    lines.push(
      `| ${componentName} | ${formatNumber(metrics.totalRenders)} | ` +
      `${formatNumber(metrics.rendersPerChatCommit)} | ` +
      `${formatNumber(metrics.p95PerChatCommit)} | ` +
      `${formatNumber(metrics.peakPerCommit)} | ` +
      `${formatNumber(metrics.totalSelfDuration)}ms |`
    );
  }

  return lines;
};

const renderComparison = (before, after) => {
  const metricRows = [
    ['채팅 commit P50', before.chat.durationP50, after.chat.durationP50, 'ms'],
    ['채팅 commit P95', before.chat.durationP95, after.chat.durationP95, 'ms'],
    ['채팅 commit P99', before.chat.durationP99, after.chat.durationP99, 'ms'],
    ['채팅 commit 최대', before.chat.durationMax, after.chat.durationMax, 'ms'],
  ];

  if (!before.partialFiberData && !after.partialFiberData) {
    metricRows.push(
      ['채팅 Fiber 평균', before.chat.fiberAverage, after.chat.fiberAverage, ''],
      ['채팅 Fiber P95', before.chat.fiberP95, after.chat.fiberP95, '']
    );
  }

  for (const componentName of [
    'UserMessage',
    'MessageActions',
    'ReadStatus',
    'CustomAvatar',
  ]) {
    if (!after.profiledComponents.includes(componentName)) continue;
    metricRows.push([
      `${componentName}/채팅 commit`,
      before.componentMetrics[componentName].rendersPerChatCommit,
      after.componentMetrics[componentName].rendersPerChatCommit,
      '',
    ]);
  }
  const lines = [
    '## Before / After 비교',
    '',
    '> 개선율은 낮을수록 좋은 지표를 기준으로 `(Before - After) / Before × 100`으로 계산한다.',
    '',
    '| 지표 | Before | After | 개선율 |',
    '| --- | ---: | ---: | ---: |',
  ];

  for (const [name, beforeValue, afterValue, unit] of metricRows) {
    lines.push(
      `| ${name} | ${formatNumber(beforeValue)}${unit} | ` +
      `${formatNumber(afterValue)}${unit} | ${formatDelta(beforeValue, afterValue)} |`
    );
  }

  const comparableAutomaticScenario = (
    before.captureType === 'automatic-chat-message' &&
    after.captureType === 'automatic-chat-message' &&
    before.browserVersion === after.browserVersion &&
    before.scenario?.baseUrl === after.scenario?.baseUrl &&
    before.scenario?.seedMessageCount === after.scenario?.seedMessageCount &&
    before.scenario?.initialMessageCount === after.scenario?.initialMessageCount &&
    before.scenario?.finalMessageCount === after.scenario?.finalMessageCount
  );

  lines.push('', comparableAutomaticScenario
    ? '> 자동 시나리오의 브라우저, 대상, 준비 메시지 수와 측정 동작이 일치한다. 현재 수치는 1회 실행 결과이므로 변동 범위를 확정하려면 같은 조건으로 반복 측정한다.'
    : '> 두 기록의 메시지 수, 실행 동작, 빌드 모드, 브라우저와 기록 길이가 같지 않으면 이 개선율을 성능 향상으로 확정할 수 없다.');

  if (before.partialFiberData || after.partialFiberData) {
    lines.push(
      '',
      '> 자동 캡처의 Fiber 값은 부분 계측이므로 Before/After Fiber 개선율에서 제외했다.'
    );
  }

  return lines;
};

const printUsage = () => {
  console.error(
    '사용법:\n' +
    '  pnpm perf:profiler -- --before <before.json>\n' +
    '  pnpm perf:profiler -- --before <before.json> --after <after.json> [--output <report.md>]'
  );
};

try {
  const options = parseArguments(process.argv.slice(2));
  if (!options.before) {
    printUsage();
    process.exitCode = 1;
  } else {
    const before = readProfile(options.before);
    const lines = [
      '# React Profiler 자동 분석',
      '',
      ...renderProfile('Before', before),
    ];

    if (options.after) {
      const after = readProfile(options.after);
      lines.push('', ...renderProfile('After', after));
      lines.push('', ...renderComparison(before, after));
    }

    const report = `${lines.join('\n')}\n`;
    if (options.output) {
      const outputPath = path.resolve(options.output);
      fs.writeFileSync(outputPath, report);
      console.log(`보고서를 저장했습니다: ${outputPath}`);
    } else {
      process.stdout.write(report);
    }
  }
} catch (error) {
  console.error(`Profiler 분석 실패: ${error.message}`);
  process.exitCode = 1;
}
