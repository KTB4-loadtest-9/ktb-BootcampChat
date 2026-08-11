export const CHAT_RENDER_PROFILER_KEY = '__KTB_CHAT_RENDER_PROFILER__';

export const isChatRenderProfilingEnabled = (
  process.env.NEXT_PUBLIC_CHAT_RENDER_PROFILING === 'true'
);

const splitProfilerId = profilerId => {
  const separatorIndex = profilerId.indexOf('::');
  if (separatorIndex === -1) {
    return { component: profilerId, instanceId: null };
  }

  return {
    component: profilerId.slice(0, separatorIndex),
    instanceId: profilerId.slice(separatorIndex + 2),
  };
};

const getCollector = () => {
  if (typeof window === 'undefined') return null;

  if (!window[CHAT_RENDER_PROFILER_KEY]) {
    window[CHAT_RENDER_PROFILER_KEY] = {
      schemaVersion: 1,
      active: false,
      entries: [],
      startedAt: null,
      stoppedAt: null,
    };
  }

  return window[CHAT_RENDER_PROFILER_KEY];
};

export const createChatProfilerId = (component, instanceId) => (
  instanceId ? `${component}::${instanceId}` : component
);

export const recordChatRender = (
  profilerId,
  phase,
  actualDuration,
  baseDuration,
  startTime,
  commitTime,
) => {
  if (!isChatRenderProfilingEnabled) return;

  const collector = getCollector();
  if (!collector?.active) return;

  const { component, instanceId } = splitProfilerId(profilerId);
  collector.entries.push({
    sequence: collector.entries.length + 1,
    component,
    instanceId,
    phase,
    actualDuration,
    baseDuration,
    startTime,
    commitTime,
    recordedAt: performance.now(),
  });
};
