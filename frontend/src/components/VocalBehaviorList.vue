<template>
  <section class="vocal-behaviors" aria-labelledby="vocal-behaviors-title">
    <header>
      <div>
        <small>发声机制视角</small>
        <h2 id="vocal-behaviors-title">行为推断</h2>
      </div>
      <p>基于 H1-H2、频谱斜率、响度、音高稳定度、齿音/空气感等指标（第 2 层标准）。</p>
      <p class="vocal-behaviors__confidence-hint">每条带的「置信度」= 该推断对这段录音的把握程度（越高越可信），不是演唱得分。</p>
    </header>
    <ul v-if="behaviors.length" class="vocal-behaviors__list">
      <li v-for="(behavior, index) in behaviors" :key="index" class="vocal-behaviors__item">
        <div class="vocal-behaviors__row">
          <strong class="vocal-behaviors__label">{{ behavior.label }}</strong>
          <span v-if="behavior.register && behavior.register !== '整体'" class="vocal-behaviors__register">{{ behavior.register }}</span>
          <span class="vocal-behaviors__confidence">置信度 {{ Math.round(behavior.confidence * 100) }}%</span>
        </div>
        <div class="vocal-behaviors__track" role="img" :aria-label="`置信度 ${Math.round(behavior.confidence * 100)}%`">
          <i class="vocal-behaviors__fill" :style="{ width: `${behavior.confidence * 100}%` }"></i>
        </div>
        <p class="vocal-behaviors__evidence">{{ behavior.evidence }}</p>
      </li>
    </ul>
    <p v-else class="vocal-behaviors__empty">未能从录音中检测到足够的有效音高片段。</p>
  </section>
</template>

<script setup>
/**
 * 发声行为推断卡片：列出后端推断出的行为标签，每条带置信度条与证据文本，
 * 让"为什么这么判断"可解释。
 */
defineProps({
  behaviors: { type: Array, default: () => [] },
})
</script>

<style scoped>
.vocal-behaviors {
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.vocal-behaviors > header small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.vocal-behaviors > header h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.vocal-behaviors > header p {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--muted, #9aa0a6);
}
.vocal-behaviors__confidence-hint {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--muted, #9aa0a6);
}
.vocal-behaviors__list {
  list-style: none;
  margin: 14px 0 0;
  padding: 0;
  display: grid;
  gap: 14px;
}
.vocal-behaviors__row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.vocal-behaviors__label { font-size: 14px; }
.vocal-behaviors__register {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: 999px;
  background: #000;
  color: #fff;
  white-space: nowrap;
}
.vocal-behaviors__confidence { margin-left: auto; font-size: 11px; color: var(--muted, #9aa0a6); }
.vocal-behaviors__track {
  height: 6px;
  border-radius: 4px;
  background: var(--border, #2a2f36);
  overflow: hidden;
  margin-top: 6px;
}
.vocal-behaviors__fill {
  display: block;
  height: 100%;
  background: var(--accent, #4aa3ff);
  border-radius: 4px;
}
.vocal-behaviors__evidence {
  margin: 7px 0 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--muted, #9aa0a6);
}
.vocal-behaviors__empty { margin: 14px 0 0; font-size: 12px; color: var(--muted, #9aa0a6); }
</style>
