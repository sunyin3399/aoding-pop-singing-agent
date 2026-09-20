<script setup>
defineProps({ highlights: { type: Array, default: () => [] } })

function number(value, digits = 1) {
  return Number.isFinite(value) ? Number(value).toFixed(digits) : '—'
}

function percent(value) {
  return Number.isFinite(value) ? `${Math.round(value * 100)}%` : '—'
}

const confidenceLabels = { HIGH: '较高可信', MEDIUM: '中等可信', LOW: '低可信' }
</script>

<template>
  <section class="high-note-section">
    <div class="high-note-heading">
      <div><small>重点复听</small><h3>精选高音片段</h3></div>
      <span>{{ highlights.length }} 段</span>
    </div>
    <p v-if="!highlights.length" class="high-note-empty">未检测到足够可靠的高音片段</p>
    <div v-else class="high-note-list">
      <article v-for="(item, index) in highlights" :key="`${item.startSeconds}-${item.representativeNote}`" class="high-note-card">
        <header>
          <span>精选 {{ index + 1 }}</span>
          <strong>{{ number(item.startSeconds) }}–{{ number(item.endSeconds) }} 秒</strong>
          <b>推测主要音 {{ item.representativeNote || '—' }}</b>
        </header>
        <div class="high-note-metrics">
          <div><small>音高波动</small><strong>{{ number(item.pitchStabilityCents) }} cents</strong></div>
          <div><small>最长连续</small><strong>{{ number(item.longestContinuousSeconds) }} 秒</strong></div>
          <div><small>持续偏低</small><strong>{{ percent(item.belowRepresentativeRatio) }}</strong></div>
          <div><small>轨迹中断</small><strong>{{ item.interruptionCount ?? '—' }} 次</strong></div>
        </div>
        <div class="frequency-balance" aria-label="片段频段能量占比">
          <span>低频 <b>{{ percent(item.lowRatio) }}</b></span>
          <span>中频 <b>{{ percent(item.midRatio) }}</b></span>
          <span>高频 <b>{{ percent(item.highRatio) }}</b></span>
          <span>谐波平衡 <b>{{ number(item.harmonicBalance, 2) }}</b></span>
        </div>
        <div v-if="item.inferences?.length" class="high-note-inferences">
          <div v-for="inference in item.inferences" :key="inference.type">
            <span>{{ confidenceLabels[inference.confidence] || inference.confidence }}</span>
            <strong>{{ inference.label }}</strong>
            <p>{{ inference.evidence }}</p>
            <small>{{ inference.advice }}</small>
          </div>
        </div>
        <p v-else class="high-note-neutral">当前证据不足以推测明确状态，建议结合原音复听。</p>
      </article>
    </div>
    <p class="high-note-disclaimer">自动推测仅供筛选重点片段，请结合原音与专业听感确认。</p>
  </section>
</template>

<style scoped>
.high-note-section { display: grid; gap: 14px; }
.high-note-heading { display: flex; align-items: end; justify-content: space-between; }
.high-note-heading small { color: #849189; }
.high-note-heading h3 { margin: 2px 0 0; }
.high-note-heading > span { color: #22c55e; font-weight: 700; }
.high-note-list { display: grid; gap: 12px; }
.high-note-card { padding: 16px; border: 1px solid rgba(50, 176, 96, .2); border-radius: 14px; background: linear-gradient(145deg, rgba(11, 30, 20, .08), rgba(32, 109, 61, .04)); }
.high-note-card header { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.high-note-card header span { color: #16a34a; font-size: 12px; font-weight: 800; }
.high-note-card header b { margin-left: auto; }
.high-note-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 14px 0 10px; }
.high-note-metrics > div { display: grid; gap: 3px; padding: 10px; border-radius: 9px; background: rgba(128, 145, 135, .08); }
.high-note-metrics small, .frequency-balance { color: #76837b; }
.frequency-balance { display: flex; gap: 12px; flex-wrap: wrap; font-size: 12px; }
.frequency-balance b { color: inherit; }
.high-note-inferences { display: grid; gap: 8px; margin-top: 12px; }
.high-note-inferences > div { padding: 12px; border-left: 3px solid #22c55e; background: rgba(34, 197, 94, .06); }
.high-note-inferences span { float: right; color: #7b897f; font-size: 11px; }
.high-note-inferences p { margin: 6px 0; }
.high-note-inferences small { color: #66736b; }
.high-note-empty, .high-note-neutral { color: #758078; }
.high-note-disclaimer { margin: 0; color: #89928c; font-size: 12px; }
@media (max-width: 700px) { .high-note-metrics { grid-template-columns: repeat(2, 1fr); } .high-note-card header b { width: 100%; margin-left: 0; } }
</style>
