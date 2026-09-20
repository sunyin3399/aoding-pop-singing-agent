export function queueAudioFile(pendingFiles, file) {
  pendingFiles.push(file)
}

export async function processPendingAudio({ pendingFiles, timeline, scoreAudio, makeId }) {
  const files = pendingFiles.splice(0)
  const entries = files.map((file) => ({
    id: makeId(),
    type: 'audio-analysis',
    fileName: file.name,
    status: 'loading',
    result: null,
    error: '',
    expanded: false,
    file,
  }))

  timeline.push(...entries)

  for (const entry of entries) {
    try {
      entry.result = await scoreAudio(entry.file)
      entry.status = 'success'
    } catch (error) {
      entry.status = 'error'
      entry.error = error.message || '音频分析失败'
    } finally {
      delete entry.file
    }
  }

  timeline.forEach((item) => {
    if (item.type === 'audio-analysis' && item.status === 'success') item.expanded = false
  })

  for (let index = entries.length - 1; index >= 0; index -= 1) {
    if (entries[index].status === 'success') {
      entries[index].expanded = true
      break
    }
  }

  return entries
}
