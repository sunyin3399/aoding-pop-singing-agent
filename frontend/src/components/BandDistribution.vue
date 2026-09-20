<template>
  <section class="vocal-bands" aria-labelledby="vocal-bands-title">
    <header>
      <div>
        <small>混音/EQ 视角</small>
        <h2 id="vocal-bands-title">感知频段分布</h2>
      </div>
      <p>各频段占整段声音能量的比例（第 1 层标准，仿 EQ 界面）。</p>
    </header>
    <ul class="vocal-bands__list">
      <li v-for="band in bands" :key="band.name" class="vocal-bands__item">
        <div class="vocal-bands__row">
          <span class="vocal-bands__name">{{ band.name }}</span>
          <span class="vocal-bands__range">{{ formatHz(band.startHz) }}–{{ formatHz(band.endHz) }}</span>
          <span class="vocal-bands__val">{{ (band.ratio * 100).toFixed(1) }}%</span>
        </div>
        <div class="vocal-bands__track" role="img" :aria-label="`${band.name} 能量占比 ${(band.ratio * 100).toFixed(1)}%`">
          <i class="vocal-bands__fill" :style="{ width: `${Math.min(100, band.ratio * 100 * 2)}%` }"></i>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup>
/**
 * 感知频段分布卡片（第 1 层 / 混音 EQ 视角）。
 * 用横向条形展示每个感知频段占整段能量的比例，让"能量主要落在哪"一目了然。
 */
const props = defineProps({
  bands: { type: Array, default: () => [] },
})

function formatHz(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return number >= 1000 ? `${(number / 1000).toFixed(1)}k` : `${Math.round(number)}`
}
</script>

<style scoped>
.vocal-bands {
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.vocal-bands > header small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.vocal-bands > header h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.vocal-bands > header p {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--muted, #9aa0a6);
}
.vocal-bands__list {
  list-style: none;
  margin: 14px 0 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.vocal-bands__row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
}
.vocal-bands__name { font-weight: 600; }
.vocal-bands__range { color: var(--muted, #9aa0a6); font-size: 11px; flex: 1; }
.vocal-bands__val { font-variant-numeric: tabular-nums; }
.vocal-bands__track {
  height: 8px;
  border-radius: 6px;
  background: var(--border, #2a2f36);
  overflow: hidden;
}
.vocal-bands__fill {
  display: block;
  height: 100%;
  border-radius: 6px;
  background: var(--accent, #4aa3ff);
}
</style>
