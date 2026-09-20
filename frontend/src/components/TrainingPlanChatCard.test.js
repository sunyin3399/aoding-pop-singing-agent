import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const component = readFileSync(new URL('./TrainingPlanChatCard.vue', import.meta.url), 'utf8')

assert.match(component, /有轻微疼痛或嘶哑/)
assert.match(component, /较为不适/)
assert.match(component, /role="alertdialog"/)
assert.match(component, /:disabled="minutesLocked"/)
assert.match(component, />分钟<\/em>/)
assert.match(component, /建议先暂停练唱并充分休息/)
assert.match(component, /exercise\.instructions/)
assert.match(component, /exercise\.stopConditions/)
assert.match(component, /day\.checkpoint/)
assert.match(component, /phase\.acceptanceCriteria/)
assert.match(component, /getTrainingPlanReferences/)

console.log('✓ renders graded vocal-risk controls and the complete generated training plan')
