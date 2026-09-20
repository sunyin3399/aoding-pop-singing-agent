import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('./TrainingPlanView.vue', import.meta.url), 'utf8')
const router = readFileSync(new URL('../router/index.js', import.meta.url), 'utf8')
const home = readFileSync(new URL('./HomeView.vue', import.meta.url), 'utf8')

assert.match(router, /path:\s*['"]\/training-plan['"]/)
assert.doesNotMatch(home, /route:\s*['"]\/training-plan['"]/)
assert.match(view, /createTrainingPlan/)
assert.match(view, /exportTrainingPlanPdf/)
assert.match(view, /validateTrainingPlanForm/)
assert.match(view, /参考资料/)
assert.match(view, /内部知识库/)
assert.match(view, /导出 PDF/)
assert.match(view, /status === 'NEEDS_CLARIFICATION'/)
assert.match(view, /status === 'SAFETY_BLOCKED'/)
assert.match(view, /status === 'VALIDATION_FAILED'/)

console.log('✓ exposes the training plan form, result states, and clickable references')
