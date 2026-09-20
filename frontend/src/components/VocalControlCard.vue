<script setup>
import { computed } from 'vue'

/**
 * 发声控制卡：主内容 = 按音区倾向叙述（像声乐老师读发声），
 * 下方折叠"数值参考"仅作支撑。不展示破音/疑似断裂或误导性原始量。
 */
const props = defineProps({
  registerControls: { type: Array, default: () => [] },
  breakEvents: { type: Array, default: () => [] },
  controlNote: { type: String, default: '' },
})

const hasData = computed(() => {
  return props.controlNote.trim().length > 0 || props.registerControls.length > 0
})

const noteLines = computed(() => {
  return String(props.controlNote || '')
    .split('。')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => `${line}。`)
})

const rows = computed(() => props.registerControls.map((r) => ({
  register: r.register,
  pitch: pitchCell(r.baselinePitchHz, r.topPitchHz),
  h1h2: bothDb(r.baselineH1h2Db, r.topH1h2Db),
  singer: bothDb(r.baselineSingerDb, r.topSingerDb),
})))

const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']

/** 频率(Hz) → 音名+八度，如 392→"G4"、494→"B4"。A4=440Hz。 */
function noteOf(hz) {
  if (!Number.isFinite(hz)) return ''
  const midi = Math.round(69 + 12 * Math.log(hz / 440) / Math.log(2))
  const name = NOTE_NAMES[((midi % 12) + 12) % 12]
  return `${name}${Math.floor(midi / 12) - 1}`
}

/** 基准→顶部 的音高单元格：突出音名区间(约 G4~B4)，括号内附 Hz 精确值。 */
function pitchCell(a, b) {
  const hz = bothHz(a, b)
  if (hz === '—') return '—'
  const an = a == null ? '' : noteOf(a)
  const bn = b == null ? '' : noteOf(b)
  if (an && bn && an === bn) return `约 ${an}（${hz}）`
  if (an && bn) return `约 ${an}~${bn}（${hz}）`
  return `约 ${an || bn}（${hz}）`
}

function bothHz(a, b) {
  if (a == null && b == null) return '—'
  return `${a == null ? '—' : `${Math.round(a)}Hz`} → ${b == null ? '—' : `${Math.round(b)}Hz`}`
}
function bothDb(a, b) {
  if (a == null && b == null) return '—'
  const f = (v) => `${v.toFixed(1)}dB`
  return `${a == null ? '—' : f(a)} → ${b == null ? '—' : f(b)}`
}
</script>

<template>
  <section v-if="hasData" class="vocal-control" aria-labelledby="vocal-control-title">
    <header>
      <div>
        <small>发声控制 · 按音区倾向</small>
        <h2 id="vocal-control-title">发声控制分析</h2>
      </div>
      <span class="vocal-control__tag">倾向推断 · 中性 · 非评价</span>
    </header>

    <p v-for="(line, index) in noteLines" :key="index" class="vocal-control__line">{{ line }}</p>

    <details v-if="rows.length" class="vocal-control__data">
      <summary>查看数值参考（H1-H2 基准→顶部 / 亮芯）</summary>
      <div class="vocal-control__table-wrap">
        <table class="vocal-control__table">
          <thead>
            <tr>
              <th>音区</th>
              <th>音高（基准→顶部）</th>
              <th>H1-H2</th>
              <th>歌手共振峰(亮芯)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.register">
              <td class="vocal-control__name">{{ row.register }}</td>
              <td>{{ row.pitch }}</td>
              <td>{{ row.h1h2 }}</td>
              <td>{{ row.singer }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="vocal-control__note">数值为发声机制视角的相对量，仅供支撑；阈值待真实素材校准。</p>
    </details>
  </section>
</template>

<style scoped>
.vocal-control {
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.vocal-control > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.vocal-control > header small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.vocal-control > header h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.vocal-control__tag {
  flex: none;
  margin-top: 2px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--border, #2a2f36);
  color: var(--muted, #9aa0a6);
  font-size: 10px;
  white-space: nowrap;
}
.vocal-control__line {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  padding-left: 14px;
  position: relative;
}
.vocal-control__line::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0.62em;
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: var(--accent, #4aa3ff);
}
.vocal-control__line + .vocal-control__line { margin-top: 4px; }
.vocal-control__data { margin-top: 14px; }
.vocal-control__data summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--muted, #9aa0a6);
}
.vocal-control__table-wrap { overflow-x: auto; margin-top: 10px; }
.vocal-control__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  white-space: nowrap;
}
.vocal-control__table th,
.vocal-control__table td {
  padding: 7px 10px;
  text-align: right;
  border-bottom: 1px solid var(--border, #2a2f36);
}
.vocal-control__table th {
  color: var(--muted, #9aa0a6);
  font-weight: 600;
  font-size: 11px;
}
.vocal-control__table td:first-child,
.vocal-control__table th:first-child { text-align: left; }
.vocal-control__name { font-weight: 600; }
.vocal-control__note {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--muted, #9aa0a6);
}
/* 内容用黑色字体（浅色报告面板） */
.vocal-control { background: #ffffff; border-color: #d0d5dd; color: #111; }
.vocal-control > header small { color: #1a73e8; }
.vocal-control > header h2 { color: #111; }
.vocal-control__tag { background: #eef1f4; color: #3c4043; }
.vocal-control__line { color: #111; }
.vocal-control__line::before { background: #1a73e8; }
.vocal-control__data summary { color: #3c4043; }
.vocal-control__table th { color: #5f6368; }
.vocal-control__table td { color: #1a1d21; }
.vocal-control__table th,
.vocal-control__table td { border-bottom-color: #e4e6ea; }
.vocal-control__name { color: #111; }
.vocal-control__note { color: #6f7276; }
</style>
