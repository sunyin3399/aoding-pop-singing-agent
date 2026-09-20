import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

function compileComponent() {
  const filename = new URL('./SpectrumLabView.vue', import.meta.url)
  const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename: filename.pathname })
  if (errors.length) throw errors[0]

  let code = compileScript(descriptor, { id: 'spectrum-lab-test', inlineTemplate: true }).content
  code = code.replace(/import \{([^}]+)\} from ["']vue["']/g, (_, imports) => {
    const bindings = imports
      .split(',')
      .map((part) => part.trim().replace(/\s+as\s+/, ': '))
      .join(', ')
    return `const { ${bindings} } = globalThis.__spectrumTestVue`
  })
  code = code
    .replace("import VocalAnalysisReport from '../components/VocalAnalysisReport.vue'", 'const VocalAnalysisReport = globalThis.__spectrumTestReport')
    .replace("import VocalControlCard from '../components/VocalControlCard.vue'", 'const VocalControlCard = globalThis.__spectrumTestControl')
    .replace("import AudioEvidenceChart from '../components/AudioEvidenceChart.vue'", 'const AudioEvidenceChart = globalThis.__spectrumTestEvidence')
    .replace("import { analyzeSpectrogram, scoreAudio, analyzeVocalProduction } from '../api/sse.js'", 'const analyzeSpectrogram = globalThis.__spectrumTestAnalyze; const scoreAudio = globalThis.__spectrumTestScore; const analyzeVocalProduction = globalThis.__spectrumTestVocal')
    .replace('export default', 'globalThis.__spectrumTestPage =')
  return code
}

function hostNode(type, text = '') {
  return { type, text, children: [], props: {}, parent: null }
}

const renderer = Vue.createRenderer({
  patchProp(node, key, _previous, value) { node.props[key] = value },
  insert(node, parent, anchor) {
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove(node) {
    const index = node.parent?.children.indexOf(node) ?? -1
    if (index >= 0) node.parent.children.splice(index, 1)
  },
  createElement: (type) => hostNode(type),
  createText: (text) => hostNode('#text', text),
  createComment: (text) => hostNode('#comment', text),
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text; node.children = [] },
  parentNode: (node) => node.parent,
  nextSibling: (node) => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  querySelector: () => null,
  setScopeId() {},
  cloneNode: (node) => ({ ...node, children: [...node.children], props: { ...node.props } }),
  insertStaticContent(content, parent) {
    const node = hostNode('#static', content)
    node.parent = parent
    parent.children.push(node)
    return [node, node]
  },
})

function find(node, predicate) {
  if (predicate(node)) return node
  for (const child of node.children || []) {
    const match = find(child, predicate)
    if (match) return match
  }
  return null
}

function textContent(node) {
  return [node.text, ...(node.children || []).map(textContent)].join('')
}

function deferredRequest(file, signal) {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { file, signal, promise, resolve, reject }
}

async function flushAsyncState() {
  await Promise.resolve()
  await Vue.nextTick()
}

const requests = []
const createdUrls = []
const revokedUrls = []

globalThis.__spectrumTestVue = Vue
globalThis.__spectrumTestReport = {
  props: ['fileName', 'audioSrc', 'status', 'errorMessage', 'spectrogram', 'vocal'],
  render() {
    return Vue.h('div', {
      class: 'report-stub',
      status: this.status,
      fileName: this.fileName,
      errorMessage: this.errorMessage,
      hasSpectrogram: this.spectrogram ? 1 : 0,
      hasVocal: this.vocal ? 1 : 0,
    })
  },
}
globalThis.__spectrumTestEvidence = { render: () => Vue.h('div', { class: 'evidence-stub' }) }
globalThis.__spectrumTestControl = { render: () => Vue.h('div', { class: 'control-stub' }) }
globalThis.__spectrumTestScore = async () => ({
  totalScore: 82,
  grade: '表现良好',
  dimensions: [{ key: 'pitch', label: '音准', score: 80, evidence: '轨迹平稳' }],
  feedback: ['保持气息支持'],
  analysis: { durationSeconds: 3.2 },
  scoreScope: '基础声学评分',
})
globalThis.__spectrumTestAnalyze = (file, { signal }) => {
  const request = deferredRequest(file, signal)
  requests.push(request)
  return request.promise
}
globalThis.__spectrumTestVocal = async () => ({
  summaryNote: '整体判断：无明显极端发声特征。',
  scope: '仅用于演唱练习辅助。',
  behaviors: [{ label: '气声', confidence: 0.7, evidence: 'H1-H2 偏大', register: '低音区' }],
  bandEnergies: [{ name: '胸腔', startHz: 80, endHz: 200, ratio: 0.3, dbfs: -40 }],
  registers: [{ register: '低音区', medianPitchHz: 150, pitchStabilityCents: 20, medianDbfs: -20, medianH1h2Db: 9, spectralSlopeDbPerOctave: -12, singerFormantDbfs: -30, airRatio: 0.05, sibilanceRatio: 0.02, nasalRatio: 1.1 }],
})

const originalCreateObjectUrl = URL.createObjectURL
const originalRevokeObjectUrl = URL.revokeObjectURL

URL.createObjectURL = (file) => {
  const url = `blob:test-${createdUrls.length + 1}`
  createdUrls.push([file.name, url])
  return url
}
URL.revokeObjectURL = (url) => revokedUrls.push(url)

function mountPage() {
  const root = hostNode('root')
  const app = renderer.createApp(globalThis.__spectrumTestPage)
  app.mount(root)
  return { app, root }
}

function select(root, file) {
  const input = find(root, (node) => node.type === 'input')
  input.props.onChange({ target: { files: [file], value: 'selected' } })
}

function reportStub(root) {
  return find(root, (node) => String(node.props?.class || '').includes('report-stub'))
}

const analysisResult = {
  durationSeconds: 3.2,
  sampleRate: 11_025,
  nyquistHz: 5_512.5,
  fftSize: 2_048,
  hopSeconds: 0.1,
  frames: [{ timeSeconds: 0, dbfs: [-30] }],
  frequenciesHz: [100],
  peakDbfs: [-30],
}

try {
  await import(`data:text/javascript,${encodeURIComponent(compileComponent())}`)

  {
    const { app, root } = mountPage()
    const backLink = find(root, (node) => node.type === 'a' && node.props.href === '/')
    assert.ok(backLink, 'the spectrum lab must provide a native link back to the home page')
    select(root, { name: 'success.wav' })
    await Vue.nextTick()
    requests.at(-1).resolve(analysisResult)
    await flushAsyncState()

    const report = reportStub(root)
    assert.ok(report, 'a vocal analysis report should be mounted once a file is chosen')
    assert.equal(report.props.status, 'success')
    assert.equal(report.props.hasSpectrogram, 1)
    assert.equal(report.props.hasVocal, 1)
    assert.match(textContent(root), /演唱评分结果 · 82\/100/)
    app.unmount()
    assert.equal(revokedUrls.at(-1), 'blob:test-1')
    console.log('✓ renders the full report after a successful analysis and releases its URL on unmount')
  }

  {
    const { app, root } = mountPage()
    select(root, { name: 'error.wav' })
    await Vue.nextTick()
    requests.at(-1).reject(new Error('服务拒绝了该音频'))
    await flushAsyncState()

    const report = reportStub(root)
    assert.ok(report, 'a report stays mounted to surface the error state')
    assert.equal(report.props.status, 'error')
    assert.match(String(report.props.errorMessage || ''), /服务拒绝了该音频/)
    app.unmount()
    console.log('✓ surfaces the analysis error message through the report')
  }

  {
    const { app, root } = mountPage()
    select(root, { name: 'first.wav' })
    await Vue.nextTick()
    const first = requests.at(-1)
    select(root, { name: 'second.wav' })
    await Vue.nextTick()
    const second = requests.at(-1)

    assert.equal(first.signal.aborted, true)
    assert.ok(revokedUrls.includes('blob:test-3'))
    first.resolve({ ...analysisResult, durationSeconds: 99 })
    second.resolve(analysisResult)
    await flushAsyncState()
    assert.equal(reportStub(root).props.status, 'success')

    select(root, { name: 'pending.wav' })
    await Vue.nextTick()
    const pending = requests.at(-1)
    app.unmount()
    assert.equal(pending.signal.aborted, true)
    assert.ok(revokedUrls.includes('blob:test-5'))
    console.log('✓ aborts replacements and unmount work while revoking every superseded URL')
  }

  {
    const { app, root } = mountPage()
    select(root, { name: 'active.wav' })
    await Vue.nextTick()
    const activeRequestCount = requests.length
    const revocationCount = revokedUrls.length
    const upload = find(root, (node) => String(node.props.class).includes('spectrum-upload-panel'))

    upload.props.onDrop({
      dataTransfer: { files: [{ name: 'notes.txt' }] },
      preventDefault() {},
    })
    await Vue.nextTick()

    assert.equal(requests.length, activeRequestCount)
    assert.equal(revokedUrls.length, revocationCount)
    assert.equal(reportStub(root).props.status, 'loading')
    assert.match(textContent(root), /仅支持 WAV、MP3 或 M4A 音频文件/)
    app.unmount()
    console.log('✓ rejects invalid replacement files without disturbing active analysis')
  }
} finally {
  URL.createObjectURL = originalCreateObjectUrl
  URL.revokeObjectURL = originalRevokeObjectUrl
  delete globalThis.__spectrumTestVue
  delete globalThis.__spectrumTestReport
  delete globalThis.__spectrumTestControl
  delete globalThis.__spectrumTestEvidence
  delete globalThis.__spectrumTestScore
  delete globalThis.__spectrumTestAnalyze
  delete globalThis.__spectrumTestVocal
  delete globalThis.__spectrumTestPage
}
