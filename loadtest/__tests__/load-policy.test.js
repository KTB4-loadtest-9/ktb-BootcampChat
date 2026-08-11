const { assertLoadTargetsAllowed, getLoadFailures } = require('../load-policy');

describe('load execution policy', () => {
  test('remote targets require an explicit opt-in', () => {
    expect(() => assertLoadTargetsAllowed([
      'http://localhost:5001',
      'http://127.0.0.1:5002'
    ], false)).not.toThrow();

    expect(() => assertLoadTargetsAllowed([
      'https://api.example.com'
    ], false)).toThrow('ALLOW_REMOTE_LOAD=true');

    expect(() => assertLoadTargetsAllowed([
      'https://api.example.com'
    ], true)).not.toThrow();
  });

  test('incomplete smoke metrics fail the gate', () => {
    expect(getLoadFailures({
      connected: 1,
      messagesSent: 1,
      errorsAuth: 0,
      errorsConnection: 0,
      errorsMessage: 0
    }, { totalUsers: 1, messages: 1 })).toEqual([]);

    expect(getLoadFailures({
      connected: 0,
      messagesSent: 0,
      errorsAuth: 1,
      errorsConnection: 0,
      errorsMessage: 0
    }, { totalUsers: 1, messages: 1 })).toEqual([
      'connected 0/1',
      'messages 0/1',
      'errors 1'
    ]);
  });
});
