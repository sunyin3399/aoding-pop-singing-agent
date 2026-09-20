import assert from 'node:assert/strict'

globalThis.localStorage = {
  getItem: () => null,
  setItem: () => {},
}

const { USER_OPTIONS, normalizeUserId } = await import('./useIdentity.js')

assert.deepEqual(USER_OPTIONS, ['user-001', 'user-002', 'user-003'])
assert.equal(normalizeUserId(null), 'user-001')
assert.equal(normalizeUserId('demo-user-001'), 'user-001')
assert.equal(normalizeUserId(' user-002 '), 'user-002')
assert.equal(normalizeUserId('custom-user'), 'user-001')

console.log('✓ normalizes the fixed test-user choices and migrates the legacy default')
