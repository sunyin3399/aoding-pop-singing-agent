const API_BASE = import.meta.env?.VITE_API_BASE_URL || '/api'

export async function createTrainingPlan(request, { signal } = {}) {
  const response = await fetch(`${API_BASE}/ai/training-plans`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(request),
    signal,
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new Error(result?.message || `训练计划生成失败（${response.status}）`)
  return result
}

export async function createConversationTrainingPlan(command) {
  const response = await fetch(`${API_BASE}/ai/training-plans/conversation`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(command),
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new Error(result?.message || `训练计划生成失败（${response.status}）`)
  return result
}

export async function exportTrainingPlanPdf(plan) {
  const response = await fetch(`${API_BASE}/ai/training-plans/pdf`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(plan),
  })
  const artifact = await response.json().catch(() => null)
  if (!response.ok) throw new Error(artifact?.message || `PDF 导出失败（${response.status}）`)
  return artifact
}
