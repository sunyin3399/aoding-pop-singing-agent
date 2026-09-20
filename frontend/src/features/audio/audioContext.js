function formatEntry(entry, index) {
  const lines = [`音频 ${index + 1}：${entry.fileName}`]
  if (entry.result.llmSummary) lines.push(`音高摘要：${entry.result.llmSummary}`)
  const highlights = (entry.result.highNoteHighlights || []).slice(0, 3).map((item) => {
    const inferences = (item.inferences || []).map((inference) => (
      `${inference.label}（${inference.confidence}：${inference.evidence}）`
    )).join('、')
    return `${Number(item.startSeconds).toFixed(1)}-${Number(item.endSeconds).toFixed(1)} 秒 推测主要音 ${item.representativeNote}，最长连续 ${Number(item.longestContinuousSeconds).toFixed(1)} 秒${inferences ? `，${inferences}` : ''}`
  })
  if (highlights.length) lines.push(`精选高音：${highlights.join('；')}`)
  if (entry.result.analysisScope) lines.push(`分析边界：${entry.result.analysisScope}`)
  return lines.join('\n')
}

export function formatAudioContext(entries) {
  const successful = entries.filter((entry) => entry.status === 'success' && entry.result)
  if (!successful.length) return ''
  return ['【演唱音频分析数据】', ...successful.map(formatEntry)].join('\n')
}

export async function resolveChatMessage(content, audioProcessing) {
  const entries = await audioProcessing
  const audioContext = formatAudioContext(entries)
  return audioContext ? `${content}\n\n${audioContext}` : content
}
