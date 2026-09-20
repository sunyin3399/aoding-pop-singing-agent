import assert from 'node:assert/strict'
import { isNearBottom } from './scroll.js'

assert.equal(isNearBottom(null), true)
assert.equal(isNearBottom({ scrollHeight: 1000, scrollTop: 500, clientHeight: 400 }), false)
assert.equal(isNearBottom({ scrollHeight: 1000, scrollTop: 530, clientHeight: 400 }), true)
assert.equal(isNearBottom({ scrollHeight: 1000, scrollTop: 600, clientHeight: 400 }), true)

console.log('✓ pauses auto-scroll away from the bottom and resumes near it')
