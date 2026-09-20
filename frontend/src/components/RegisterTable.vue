<template>
  <section class="vocal-registers" aria-labelledby="vocal-registers-title">
    <header>
      <div>
        <small>发声机制视角</small>
        <h2 id="vocal-registers-title">按音区对比</h2>
      </div>
      <p>低 / 中 / 高音区的声音特征对比，看唱到不同高度时发声怎么变化。</p>
    </header>
    <template v-if="registers.length">
      <div class="vocal-registers__table-wrap">
        <table class="vocal-registers__table">
          <thead>
            <tr>
              <th>音区</th>
              <th>中位音高</th>
              <th>H1−H2</th>
              <th>频谱斜率</th>
              <th>歌手共振峰</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in registers" :key="row.register">
              <td class="vocal-registers__name">{{ row.register }}</td>
              <td>{{ row.medianPitchHz == null ? '—' : `${Math.round(row.medianPitchHz)} Hz` }}</td>
              <td>{{ row.medianH1h2Db == null ? '—' : `${row.medianH1h2Db} dB` }}</td>
              <td>{{ row.spectralSlopeDbPerOctave == null ? '—' : `${row.spectralSlopeDbPerOctave} dB/oct` }}</td>
              <td>{{ row.singerFormantDbfs == null ? '—' : `${row.singerFormantDbfs} dBFS` }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <p v-else class="vocal-registers__empty">这段录音没有检测到足够的有效音高，暂时无法做音区对比。</p>
  </section>
</template>

<script setup>
/**
 * 按音区对比表（第 2 层 / 发声机制视角）。
 * 展示每个音区的中位音高、音高稳定度、电平、H1-H2、频谱斜率、歌手共振峰、空气感、
 * 齿音、鼻音倾向等指标，方便横向比较"唱到高音/低音时发声状态怎么变"。
 */
const props = defineProps({
  registers: { type: Array, default: () => [] },
})

function fmtRatio(value) {
  if (value == null) return '—'
  return `${(value * 100).toFixed(1)}%`
}
</script>

<style scoped>
.vocal-registers {
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.vocal-registers > header small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.vocal-registers > header h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.vocal-registers > header p {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--muted, #9aa0a6);
}
.vocal-registers__table-wrap { overflow-x: auto; margin-top: 14px; }
.vocal-registers__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  white-space: nowrap;
}
.vocal-registers__table th,
.vocal-registers__table td {
  padding: 8px 10px;
  text-align: right;
  border-bottom: 1px solid var(--border, #2a2f36);
}
.vocal-registers__table th {
  color: var(--muted, #9aa0a6);
  font-weight: 600;
  font-size: 11px;
}
.vocal-registers__table td:first-child,
.vocal-registers__table th:first-child { text-align: left; }
.vocal-registers__name { font-weight: 600; }
.vocal-registers__empty {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--muted, #9aa0a6);
}
</style>
