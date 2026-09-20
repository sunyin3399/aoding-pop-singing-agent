const API_BASE = import.meta.env?.VITE_API_BASE_URL || '/api'

function dispatchBlock(block, onEvent) {
  if (!block.trim()) return
  let event = 'message'
  let id = ''
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('id:')) id = line.slice(3).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (data.length || event !== 'message') onEvent({ event, id, data: data.join('\n') })
}

export async function streamRequest(path, params, { signal, onEvent }) {
  const query = new URLSearchParams(params)
  const response = await fetch(`${API_BASE}${path}?${query}`, {
    headers: { Accept: 'text/event-stream' },
    signal,
  })
  if (!response.ok || !response.body) {
    const detail = await response.text().catch(() => '')
    throw new Error(detail || `请求失败（${response.status}）`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    blocks.forEach((block) => dispatchBlock(block, onEvent))
    if (done) break
  }
  dispatchBlock(buffer, onEvent)
}

export async function cancelStream(requestId) {
  return fetch(`${API_BASE}/ai/streams/${encodeURIComponent(requestId)}`, { method: 'DELETE' })
}

async function getJson(path, params) {
  const query = new URLSearchParams(params)
  const response = await fetch(`${API_BASE}${path}?${query}`)
  if (!response.ok) throw new Error(`请求失败（${response.status}）`)
  return response.json()
}

export function fetchConversations(userId, mode) {
  return getJson('/ai/conversations', { userId, mode })
}

export function fetchConversation(conversationId, userId, mode) {
  return getJson(`/ai/conversations/${encodeURIComponent(conversationId)}`, { userId, mode })
}

export function deleteConversation(conversationId, userId, mode) {
  return fetch(`${API_BASE}/ai/conversations/${encodeURIComponent(conversationId)}?${new URLSearchParams({ userId, mode })}`, { method: 'DELETE' })
    .then((response) => { if (!response.ok) throw new Error('删除会话失败，请稍后重试') })
}

export function downloadUrl(fileName) {
  return `${API_BASE}/ai/pdf/download/${encodeURIComponent(fileName)}`
}

export async function scoreAudio(file, { signal } = {}) {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(`${API_BASE}/ai/audio/score`, {
    method: 'POST',
    body,
    signal,
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(result?.message || `音频分析失败（${response.status}）`)
  }
  return result
}

export async function analyzePitchTrack(file, { signal } = {}) {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(`${API_BASE}/ai/audio/pitch-track`, { method: 'POST', body, signal })
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new Error(result?.message || `音高分析失败（${response.status}）`)
  return result
}

export async function analyzeSpectrogram(file, { signal } = {}) {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(`${API_BASE}/ai/audio/spectrogram`, {
    method: 'POST',
    body,
    signal,
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(result?.message || `频谱分析失败（${response.status}）`)
  }
  return result
}

/** 人声"发声行为"分析：感知频段分布 + 按音区聚合的发声机制指标 + 行为推断。 */
export async function analyzeVocalProduction(file, { signal, userId, mode, conversationId } = {}) {
  const body = new FormData()
  body.append('file', file)
  const query = new URLSearchParams({ ...(userId ? { userId } : {}), ...(mode ? { mode } : {}), ...(conversationId ? { conversationId } : {}) })
  const response = await fetch(`${API_BASE}/ai/audio/vocal-analysis?${query}`, {
    method: 'POST',
    body,
    signal,
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(result?.message || `发声行为分析失败（${response.status}）`)
  }
  return result
}
