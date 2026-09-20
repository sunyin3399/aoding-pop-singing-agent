import assert from 'node:assert/strict'
import {
  dbfsToColor,
  dbfsToRatio,
  frameIndexAtTime,
  frequencyToRatio,
  ratioToFrequency,
  ratioToTime,
  seekTimeFromKey,
  waterfallFrameIndexAtRow,
} from './spectrumMath.js'
import { analyzeSpectrogram } from '../../api/sse.js'

const tests = []

function test(name, run) {
  tests.push({ name, run })
}

function restoreGlobal(name, value) {
  if (value === undefined) delete globalThis[name]
  else globalThis[name] = value
}

test('uses real frame timestamps for nearest lookup at 11025 Hz and end of file', () => {
  const frames = Array.from({ length: 1002 }, (_, index) => ({
    timeSeconds: Number((index * 1103 / 11025).toFixed(2)),
  }))

  assert.equal(frameIndexAtTime(100.07, frames), 1000)
  assert.equal(frameIndexAtTime(-1, frames), 0)
  assert.equal(frameIndexAtTime(999, frames), 1001)
})

test('keeps exactly one source frame per waterfall row for a valid short tail', () => {
  const frames = [0, 0.1, 0.2, 0.3].map((timeSeconds) => ({ timeSeconds }))

  assert.deepEqual(
    frames.map((_, row) => waterfallFrameIndexAtRow(row, frames)),
    [0, 1, 2, 3],
  )
})

test('maps the frequency range logarithmically to a clamped ratio', () => {
  assert.equal(frequencyToRatio(20, 20, 20000), 0)
  assert.equal(frequencyToRatio(20000, 20, 20000), 1)
})

test('maps a clamped ratio back to logarithmic frequency', () => {
  assert.equal(ratioToFrequency(-1, 20, 20000), 20)
  assert.equal(ratioToFrequency(2, 20, 20000), 20000)
  assert.ok(Math.abs(ratioToFrequency(0.5, 20, 20000) - 632.4555) < 0.001)
})

test('round-trips logarithmic frequencies within floating-point tolerance', () => {
  for (const frequency of [20, 100, 1000, 12500, 20000]) {
    const ratio = frequencyToRatio(frequency, 20, 20000)
    assert.ok(Math.abs(ratioToFrequency(ratio, 20, 20000) - frequency) < 1e-9)
  }
})

test('maps a clamped ratio to audio time', () => {
  assert.equal(ratioToTime(-1, 12.5), 0)
  assert.equal(ratioToTime(0.4, 12.5), 5)
  assert.equal(ratioToTime(2, 12.5), 12.5)
})

test('maps slider keys to clamped audio seek times', () => {
  assert.equal(seekTimeFromKey('ArrowRight', 4.9, 5, 0.2), 5)
  assert.equal(seekTimeFromKey('ArrowLeft', 0.1, 5, 0.2), 0)
  assert.equal(seekTimeFromKey('ArrowDown', 4.9, 5, 0.2), 5)
  assert.equal(seekTimeFromKey('ArrowUp', 0.1, 5, 0.2), 0)
  assert.equal(seekTimeFromKey('Home', 3, 5, 0.2), 0)
  assert.equal(seekTimeFromKey('End', 3, 5, 0.2), 5)
  assert.equal(seekTimeFromKey('Enter', 3, 5, 0.2), null)
})

test('maps dBFS to intensity and a CSS RGB color', () => {
  assert.equal(dbfsToRatio(-120, -120, 0), 0)
  assert.equal(dbfsToRatio(0, -120, 0), 1)
  assert.match(dbfsToColor(-40), /^rgb\(/)
  assert.equal(dbfsToColor(0), 'rgb(255, 255, 255)')
})

test('uploads an audio file to the spectrogram endpoint', async () => {
  const originalFetch = globalThis.fetch
  const originalFormData = globalThis.FormData
  const appended = []
  const file = { name: 'take.wav' }
  const signal = { aborted: false }
  const result = { durationSeconds: 3.2 }
  let request

  globalThis.FormData = class {
    append(name, value) {
      appended.push([name, value])
    }
  }
  globalThis.fetch = async (url, options) => {
    request = { url, options }
    return { ok: true, json: async () => result }
  }

  try {
    assert.equal(await analyzeSpectrogram(file, { signal }), result)
    assert.equal(request.url, '/api/ai/audio/spectrogram')
    assert.equal(request.options.method, 'POST')
    assert.equal(request.options.signal, signal)
    assert.deepEqual(appended, [['file', file]])
  } finally {
    restoreGlobal('fetch', originalFetch)
    restoreGlobal('FormData', originalFormData)
  }
})

test('uses the server message when spectrogram analysis fails', async () => {
  const originalFetch = globalThis.fetch
  const originalFormData = globalThis.FormData

  globalThis.FormData = class { append() {} }
  globalThis.fetch = async () => ({ ok: false, status: 422, json: async () => ({ message: '不支持该音频格式' }) })

  try {
    await assert.rejects(analyzeSpectrogram({ name: 'take.txt' }), /不支持该音频格式/)
  } finally {
    restoreGlobal('fetch', originalFetch)
    restoreGlobal('FormData', originalFormData)
  }
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
