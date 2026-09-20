const API_BASE = import.meta.env?.VITE_API_BASE_URL || '/api'
const CANDIDATE_PATH = '/admin/knowledge/candidates'

async function request(path, options) {
  const response = await fetch(`${API_BASE}${path}`, options)
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new Error(result?.message || `知识审核请求失败（${response.status}）`)
  return result
}

export function listKnowledgeCandidates({ status, page = 0, size = 20 } = {}) {
  const query = new URLSearchParams()
  if (status) query.set('status', status)
  query.set('page', String(page))
  query.set('size', String(size))
  return request(`${CANDIDATE_PATH}?${query}`)
}

export function getKnowledgeCandidate(id) {
  return request(`${CANDIDATE_PATH}/${encodeURIComponent(id)}`)
}

function reviewCandidate(id, action, reviewNote) {
  return request(`${CANDIDATE_PATH}/${encodeURIComponent(id)}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ reviewNote }),
  })
}

export function approveKnowledgeCandidate(id, reviewNote) {
  return reviewCandidate(id, 'approve', reviewNote)
}

export function rejectKnowledgeCandidate(id, reviewNote) {
  return reviewCandidate(id, 'reject', reviewNote)
}
