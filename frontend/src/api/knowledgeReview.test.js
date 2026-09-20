import assert from 'node:assert/strict'
import {
  approveKnowledgeCandidate,
  getKnowledgeCandidate,
  listKnowledgeCandidates,
  rejectKnowledgeCandidate,
} from './knowledgeReview.js'

const calls = []
const originalFetch = globalThis.fetch
globalThis.fetch = async (url, options = {}) => {
  calls.push({ url, options })
  return { ok: true, json: async () => ({ id: 'candidate-1' }) }
}

try {
  await listKnowledgeCandidates({ status: 'PENDING', page: 1, size: 20 })
  await getKnowledgeCandidate('candidate-1')
  await approveKnowledgeCandidate('candidate-1', '来源已核验')
  await rejectKnowledgeCandidate('candidate-2', '证据不足')

  assert.equal(calls[0].url, '/api/admin/knowledge/candidates?status=PENDING&page=1&size=20')
  assert.equal(calls[1].url, '/api/admin/knowledge/candidates/candidate-1')
  assert.equal(calls[2].url, '/api/admin/knowledge/candidates/candidate-1/approve')
  assert.equal(calls[2].options.method, 'POST')
  assert.deepEqual(JSON.parse(calls[2].options.body), { reviewNote: '来源已核验' })
  assert.equal(calls[3].url, '/api/admin/knowledge/candidates/candidate-2/reject')
  console.log('✓ calls candidate list, detail, approve, and reject admin endpoints')
} finally {
  globalThis.fetch = originalFetch
}
