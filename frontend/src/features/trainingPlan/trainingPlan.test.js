import assert from 'node:assert/strict'
import {
  buildTrainingPlanDraft,
  buildTrainingPlanRequest,
  flattenTrainingDays,
  getTrainingPlanReferences,
  validateTrainingPlanForm,
  isTrainingPlanIntent,
  isTrainingPlanShortcut,
  shouldOpenTrainingPlanForm,
} from './trainingPlan.js'

const completeForm = {
  goal: '稳定完成一首流行歌曲',
  level: 'BEGINNER',
  durationDays: 7,
  minutesPerDay: 30,
  currentProblems: '高音紧张，换气不稳定',
  targetSong: '小幸运',
  comfortableRange: 'C3-G4',
  hasPainOrHoarseness: false,
}

assert.deepEqual(validateTrainingPlanForm(completeForm), {})
assert.deepEqual(validateTrainingPlanForm({ ...completeForm, goal: '', durationDays: 31 }), {
  goal: '请填写训练目标',
  durationDays: '训练周期需在 1-30 天之间',
})

assert.deepEqual(buildTrainingPlanRequest(completeForm, 'user-001'), {
  ...completeForm,
  currentProblems: ['高音紧张', '换气不稳定'],
  conversationId: 'user-001',
  outputFormat: 'PAGE',
})

const discomfortRequest = buildTrainingPlanRequest({
  ...completeForm,
  hasPainOrHoarseness: undefined,
  vocalCondition: 'SIGNIFICANT_DISCOMFORT',
  proceedDespiteDiscomfort: true,
}, 'conversation-1')
assert.equal(discomfortRequest.vocalCondition, 'SIGNIFICANT_DISCOMFORT')
assert.equal(discomfortRequest.proceedDespiteDiscomfort, true)
assert.equal(discomfortRequest.hasPainOrHoarseness, null)

const result = {
  plan: {
    phases: [
      { name: '建立基础', startDay: 1, endDay: 1 },
      { name: '歌曲应用', startDay: 2, endDay: 2 },
    ],
    days: [{ day: 1, exercises: [] }, { day: 2, exercises: [] }],
  },
  citations: [
    { documentId: 'doc-1', title: '呼吸训练', url: '/api/ai/knowledge/documents/doc-1' },
    { documentId: 'doc-1', title: '重复项', url: '/api/ai/knowledge/documents/doc-1' },
    { id: 'chunk-1', title: '同一篇文档', url: '/api/ai/knowledge/documents/doc-2?chunk=1' },
    { id: 'chunk-2', title: '同一篇文档', url: '/api/ai/knowledge/documents/doc-2?chunk=2' },
  ],
}

assert.deepEqual(flattenTrainingDays(result), [
  { phaseName: '建立基础', day: 1, exercises: [] },
  { phaseName: '歌曲应用', day: 2, exercises: [] },
])
assert.deepEqual(getTrainingPlanReferences(result), [
  { documentId: 'doc-1', title: '呼吸训练', url: '/api/ai/knowledge/documents/doc-1' },
  { id: 'chunk-1', title: '同一篇文档', url: '/api/ai/knowledge/documents/doc-2?chunk=1' },
])

console.log('✓ validates form data and normalizes structured training plan results')

assert.equal(isTrainingPlanIntent('请为我制定 7 天气息训练计划'), true)
assert.equal(isTrainingPlanIntent('帮我制定一份适合初学者的练唱计划'), true)
assert.equal(isTrainingPlanIntent('怎么练高音'), false)
assert.equal(isTrainingPlanShortcut('为我制定7天气息训练'), true)
assert.equal(shouldOpenTrainingPlanForm('为我制定7天气息训练'), true)
assert.equal(shouldOpenTrainingPlanForm('请安排一个换声区练声方案'), true)
assert.equal(shouldOpenTrainingPlanForm('给我一个7天气息训练计划'), true)
assert.equal(shouldOpenTrainingPlanForm('创建一个适合初学者的练声计划'), true)
assert.equal(shouldOpenTrainingPlanForm('帮我做一份换声区训练方案'), true)
assert.equal(shouldOpenTrainingPlanForm('来个每天20分钟的练唱计划'), true)
assert.equal(shouldOpenTrainingPlanForm('怎么练高音'), false)
assert.equal(shouldOpenTrainingPlanForm('我有一个训练问题'), false)
assert.equal(shouldOpenTrainingPlanForm('推荐几首适合初学者的歌曲'), false)
assert.equal(buildTrainingPlanDraft('制定14天练声计划').durationDays, 14)
console.log('✓ conservatively detects explicit training-plan requests and extracts duration')
