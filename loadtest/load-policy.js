const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '0.0.0.0', '::1', '[::1]']);

function assertLoadTargetsAllowed(targets, allowRemote) {
  if (allowRemote) return;

  const remoteTargets = targets.filter((target) => !LOCAL_HOSTS.has(new URL(target).hostname));
  if (remoteTargets.length > 0) {
    throw new Error(
      `Remote load target blocked: ${remoteTargets.join(', ')}. ` +
      'Set ALLOW_REMOTE_LOAD=true only after infrastructure approval.'
    );
  }
}

function getLoadFailures(metrics, { totalUsers, messages }) {
  const expectedMessages = totalUsers * messages;
  const errors = metrics.errorsAuth + metrics.errorsConnection + metrics.errorsMessage;
  const failures = [];

  if (metrics.connected !== totalUsers) failures.push(`connected ${metrics.connected}/${totalUsers}`);
  if (metrics.messagesSent !== expectedMessages) failures.push(`messages ${metrics.messagesSent}/${expectedMessages}`);
  if (errors > 0) failures.push(`errors ${errors}`);

  return failures;
}

module.exports = { assertLoadTargetsAllowed, getLoadFailures };
