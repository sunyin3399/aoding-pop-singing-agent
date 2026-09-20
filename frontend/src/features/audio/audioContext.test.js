import assert from 'node:assert/strict'
import { formatAudioContext, resolveChatMessage } from './audioContext.js'

const tests = []

function test(name, run) {
  tests.push({ name, run })
}

test('formats compact pitch summaries and representative segments for the coach LLM', () => {
  const text = formatAudioContext([
    {
      fileName: 'first.wav',
      status: 'success',
      result: {
        durationSeconds: 12.34,
        llmSummary: '有效音域 A3-A4；稳定性 45.0 cents。',
        analysisScope: '无参考旋律分析，不能判断是否唱准原曲。',
        highNoteHighlights: [{
          startSeconds: 2, endSeconds: 7, representativeNote: 'A4', longestContinuousSeconds: 2.8,
          inferences: [{ label: '可能存在高音到位困难', confidence: 'MEDIUM', evidence: '持续偏低' }],
        }],
      },
    },
    { fileName: 'bad.wav', status: 'error', error: '无法读取' },
    {
      fileName: 'second.wav',
      status: 'success',
      result: { durationSeconds: 3, llmSummary: '未检测到稳定人声音高。', analysisScope: '无参考旋律分析。', highNoteHighlights: [] },
    },
  ])

  assert.equal(text, [
    '【演唱音频分析数据】',
    '音频 1：first.wav',
    '音高摘要：有效音域 A3-A4；稳定性 45.0 cents。',
    '精选高音：2.0-7.0 秒 推测主要音 A4，最长连续 2.8 秒，可能存在高音到位困难（MEDIUM：持续偏低）',
    '分析边界：无参考旋律分析，不能判断是否唱准原曲。',
    '音频 2：second.wav',
    '音高摘要：未检测到稳定人声音高。',
    '分析边界：无参考旋律分析。',
  ].join('\n'))
  assert.equal(text.includes('points'), false)
  assert.equal(text.includes('bad.wav'), false)
})

test('returns no context when every audio analysis failed', () => {
  assert.equal(formatAudioContext([{ fileName: 'bad.wav', status: 'error' }]), '')
})

test('waits for scoring before appending audio context to user text', async () => {
  let release
  let settled = false
  const scoring = new Promise((resolve) => { release = resolve })
  const messagePromise = resolveChatMessage('请分析我的演唱', scoring).then((value) => {
    settled = true
    return value
  })

  await Promise.resolve()
  assert.equal(settled, false)
  release([{
    fileName: 'take.wav',
    status: 'success',
    result: { llmSummary: '音高轨迹稳定。', analysisScope: '无参考旋律分析。', highNoteHighlights: [] },
  }])
  assert.equal(
    await messagePromise,
    '请分析我的演唱\n\n【演唱音频分析数据】\n音频 1：take.wav\n音高摘要：音高轨迹稳定。\n分析边界：无参考旋律分析。',
  )
})

test('keeps the original user text when scoring has no successful result', async () => {
  const message = await resolveChatMessage(
    '请给我建议',
    Promise.resolve([{ fileName: 'bad.wav', status: 'error' }]),
  )
  assert.equal(message, '请给我建议')
})

let failures = 0
for (const { name, run } of tests) {
  try {
    await run()
    console.log(`✓ ${name}`)
  } catch (error) {
    failures += 1
    console.error(`✗ ${name}`)
    console.error(error)
  }
}

if (failures) process.exitCode = 1
