import assert from 'node:assert/strict'
import { processPendingAudio, queueAudioFile } from './audioWorkflow.js'

const tests = []

function test(name, run) {
  tests.push({ name, run })
}

test('queueing files does not analyze them', () => {
  const pendingFiles = []
  let analysisCalls = 0

  queueAudioFile(pendingFiles, { name: 'first.wav' })

  assert.deepEqual(pendingFiles.map((file) => file.name), ['first.wav'])
  assert.equal(analysisCalls, 0)
})

test('processes every queued file in upload order and clears the queue', async () => {
  const pendingFiles = [{ name: 'first.wav' }, { name: 'second.mp3' }]
  const timeline = [{ id: 'old', type: 'audio-analysis', status: 'success', expanded: true }]
  const calls = []
  let id = 0

  await processPendingAudio({
    pendingFiles,
    timeline,
    makeId: () => `audio-${++id}`,
    scoreAudio: async (file) => {
      calls.push(file.name)
      return { totalScore: file.name === 'first.wav' ? 71 : 84, analysis: { durationSeconds: 3 } }
    },
  })

  assert.deepEqual(calls, ['first.wav', 'second.mp3'])
  assert.equal(pendingFiles.length, 0)
  assert.deepEqual(timeline.slice(1).map((item) => item.fileName), ['first.wav', 'second.mp3'])
  assert.deepEqual(timeline.map((item) => item.expanded), [false, false, true])
})

test('appends a later batch without replacing prior results', async () => {
  const old = { id: 'old', type: 'audio-analysis', fileName: 'old.wav', status: 'success', expanded: true }
  const timeline = [old]

  await processPendingAudio({
    pendingFiles: [{ name: 'new.wav' }],
    timeline,
    makeId: () => 'new',
    scoreAudio: async () => ({ totalScore: 90, analysis: { durationSeconds: 4 } }),
  })

  assert.equal(timeline[0], old)
  assert.deepEqual(timeline.map((item) => item.fileName), ['old.wav', 'new.wav'])
})

test('keeps a failed card and continues with later files', async () => {
  const timeline = []

  await processPendingAudio({
    pendingFiles: [{ name: 'bad.wav' }, { name: 'good.wav' }],
    timeline,
    makeId: (() => { let id = 0; return () => String(++id) })(),
    scoreAudio: async (file) => {
      if (file.name === 'bad.wav') throw new Error('无法读取')
      return { totalScore: 88, analysis: { durationSeconds: 5 } }
    },
  })

  assert.deepEqual(timeline.map((item) => item.status), ['error', 'success'])
  assert.equal(timeline[0].error, '无法读取')
  assert.equal(timeline[1].expanded, true)
})

test('inserts every loading card before analysis finishes', async () => {
  const pendingFiles = [{ name: 'one.wav' }, { name: 'two.wav' }]
  const timeline = []
  let release
  const blocked = new Promise((resolve) => { release = resolve })

  const processing = processPendingAudio({
    pendingFiles,
    timeline,
    makeId: (() => { let id = 0; return () => String(++id) })(),
    scoreAudio: async () => blocked,
  })

  assert.deepEqual(timeline.map((item) => [item.fileName, item.status]), [
    ['one.wav', 'loading'],
    ['two.wav', 'loading'],
  ])
  release({ totalScore: 80, analysis: { durationSeconds: 2 } })
  await processing
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
