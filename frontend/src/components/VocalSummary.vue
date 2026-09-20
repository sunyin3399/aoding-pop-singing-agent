<template>
  <section class="vocal-summary" aria-labelledby="vocal-summary-title">
    <header class="vocal-summary__head">
      <div>
        <small>发声行为分析</small>
        <h2 id="vocal-summary-title">整体判断</h2>
      </div>
    </header>
    <p v-for="(line, index) in noteLines" :key="index" class="vocal-summary__line">{{ line }}</p>
    <p v-if="scope" class="vocal-summary__scope">{{ scope }}</p>
  </section>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 人声"发声行为"分析的整体判断卡片：把后端一段串起来的长句按句读拆成多行显示，
 * 再带一句能力边界说明。数据来自后端 /ai/audio/vocal-analysis 的 summaryNote 与 scope。
 */
const props = defineProps({
  summary: { type: String, default: '' },
  scope: { type: String, default: '' },
})

/** 把 summaryNote 按句号拆成多行；scope 里已含免责声明时，去掉末尾重复的声明句。 */
const noteLines = computed(() => {
  const sentences = String(props.summary || '')
    .split('。')
    .map((sentence) => sentence.trim())
    .filter(Boolean)
    .map((sentence) => `${sentence}。`)
  if (props.scope) {
    return sentences.filter((sentence) => !sentence.includes('不构成'))
  }
  return sentences
})
</script>

<style scoped>
.vocal-summary {
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.vocal-summary__head small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.vocal-summary__head h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.vocal-summary__line {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  padding-left: 14px;
  position: relative;
}
.vocal-summary__line::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0.62em;
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: var(--accent, #4aa3ff);
}
.vocal-summary__line + .vocal-summary__line {
  margin-top: 4px;
}
.vocal-summary__scope {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--muted, #9aa0a6);
  line-height: 1.5;
}
</style>
