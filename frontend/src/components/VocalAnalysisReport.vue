<script setup>
/**
 * 演唱分析报告（可复用的整块结果）：
 * 当前音频播放器 + 实时频率能量(随播放) + 声音能量分布(仿 EQ) + 发声控制分析(按音区倾向 + 数值参考)。
 * 在「频谱实验室」和「声乐教练」会话里被同一份数据渲染，保证两处一致。
 * 播放状态(progress/seek)由本组件自己维护，父级只需传入分析结果与音频地址。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import SpectrumCanvas from './SpectrumCanvas.vue'
import VocalControlCard from './VocalControlCard.vue'

const props = defineProps({
  fileName: { type: String, default: '' },
  audioSrc: { type: String, default: '' },
  status: { type: String, default: 'loading' }, // loading | error | success
  errorMessage: { type: String, default: '' },
  spectrogram: { type: Object, default: null },
  vocal: { type: Object, default: null },
})

const audio = ref(null)
const currentTime = ref(0)
let playbackAnimationId = 0
let reportCounter = 0
reportCounter += 1
const uid = `vocal-report-${reportCounter}`
const freqTitleId = `${uid}-freq`
const eqTitleId = `${uid}-eq`

const statusLabel = computed(() => {
  if (props.status === 'success') return '分析完成'
  if (props.status === 'loading') return '分析中'
  return '等待重试'
})

function updateCurrentTime() {
  currentTime.value = Number.isFinite(audio.value?.currentTime) ? audio.value.currentTime : 0
}

function stopPlaybackLoop() {
  if (!playbackAnimationId) return
  cancelAnimationFrame(playbackAnimationId)
  playbackAnimationId = 0
}

function samplePlaybackTime() {
  playbackAnimationId = 0
  updateCurrentTime()
  playbackAnimationId = requestAnimationFrame(samplePlaybackTime)
}

function startPlaybackLoop() {
  updateCurrentTime()
  if (!playbackAnimationId) playbackAnimationId = requestAnimationFrame(samplePlaybackTime)
}

function stopPlaybackLoopAndUpdate() {
  updateCurrentTime()
  stopPlaybackLoop()
}

function seekAudio(seconds) {
  const duration = Math.max(0, Number(props.spectrogram?.durationSeconds) || 0)
  const requestedTime = Number.isFinite(Number(seconds)) ? Number(seconds) : 0
  const nextTime = Math.min(duration, Math.max(0, requestedTime))
  if (audio.value) audio.value.currentTime = nextTime
  currentTime.value = nextTime
}

watch(() => props.audioSrc, () => {
  stopPlaybackLoop()
  currentTime.value = 0
})

// —— 仿 EQ 的能量柱 ——
const EQ_FREQ_LO = 90
const EQ_FREQ_HI = 12000
const EQ_DB_HI = -10
const EQ_DB_LO = -70

const eqBands = computed(() => {
  const shortNames = {
    胸腔: '低沉', '温暖·鼻': '温暖', '中频存在感': '明亮/主体',
    歌手共振峰: '穿透', 齿音: '齿音', 空气感: '空气感',
  }
  const bands = (props.vocal?.bandEnergies || [])
    .filter((band) => band.name !== '低切' && band.endHz > 0)
    .slice()
    .sort((a, b) => a.startHz - b.startHz)
  return bands.map((band) => {
    const dbfs = Number.isFinite(band.dbfs) ? band.dbfs : EQ_DB_LO
    const t = Math.max(0, Math.min(1, (dbfs - EQ_DB_LO) / (EQ_DB_HI - EQ_DB_LO)))
    return { name: band.name, shortLabel: shortNames[band.name] || band.name, level: t, dbfs }
  })
})

/** 数值标签放在柱顶上方一点点：bottom = 柱高 + 间距。 */
function barValueStyle(level) {
  const fillPercent = Math.max(2, level * 100)
  return { bottom: `calc(${fillPercent}% + 3px)` }
}

onBeforeUnmount(stopPlaybackLoop)
</script>

<template>
  <section class="vocal-analysis-report" :aria-label="`${fileName} 的演唱分析`">
    <div class="spectrum-file-panel" :aria-labelledby="`${uid}-file`">
      <header class="spectrum-file-heading">
        <div>
          <small>当前音频</small>
          <h2 :id="`${uid}-file`">{{ fileName }}</h2>
        </div>
        <span>{{ statusLabel }}</span>
      </header>

      <audio
        ref="audio"
        class="spectrum-audio-player"
        :src="audioSrc"
        controls
        preload="metadata"
        :aria-label="`播放 ${fileName}`"
        @timeupdate="updateCurrentTime"
        @play="startPlaybackLoop"
        @pause="stopPlaybackLoopAndUpdate"
        @ended="stopPlaybackLoopAndUpdate"
      ></audio>

      <div v-if="status === 'loading'" class="spectrum-status is-loading" role="status" aria-live="polite">
        <span class="spectrum-status-spinner" aria-hidden="true"></span>
        <div>
          <strong>正在分析</strong>
          <p>音频可以立即播放，分析完成后图表会自动出现。</p>
        </div>
      </div>

      <div v-else-if="status === 'error'" class="spectrum-status is-error" role="alert">
        <span class="spectrum-status-mark" aria-hidden="true">!</span>
        <div>
          <strong>分析未完成</strong>
          <p>{{ errorMessage }}</p>
        </div>
      </div>
    </div>

    <template v-if="status === 'success' && spectrogram">
      <!-- 实时频谱（随播放更新；红线 = 全程峰值参考） -->
      <section class="spectrum-frequency" :aria-labelledby="freqTitleId">
        <header class="spectrum-frequency__head">
          <div>
            <small>实时频谱</small>
            <h2 :id="freqTitleId">频率能量</h2>
          </div>
          <a
            class="vocal-report__guide"
            href="vocal_spectrum_guide.html"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="查看频谱分析的算法原理说明"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z" />
              <path d="M4 20.5A2.5 2.5 0 0 1 6.5 18H20" />
              <path d="M9 8h7M9 11.5h7" />
            </svg>
            算法原理
          </a>
        </header>
        <p class="spectrum-frequency__hint">随播放实时更新，黄线 = 当前这一瞬的声音，红线 = 整段在各频率上的全程峰值。</p>
        <SpectrumCanvas :result="spectrogram" :current-time="currentTime" :waterfall="false" @seek="seekAudio" />
      </section>

      <!-- 声音能量分布（仿 EQ；能量强度，非质量分） -->
      <section v-if="vocal?.bandEnergies?.length" class="vocal-eq" :aria-labelledby="eqTitleId">
        <header>
          <div>
            <small>仿 EQ 音色区域</small>
            <h2 :id="eqTitleId">声音能量分布</h2>
          </div>
          <span class="vocal-eq__scale-tag">能量刻度 0–10 · 柱越高越强</span>
        </header>
        <p class="vocal-eq__hint">
          把声音按"音色区域"拆开，看每块能量的强弱（0–10，越高 = 那块声音越强）。各柱只描述能量、不评价好坏；
          <b class="vocal-eq__warn">唯有「齿音」要反向读：齿音越高 = 唇齿音越重、越刺</b>。
        </p>
        <div class="vocal-eq__bars" role="img" :aria-label="`${fileName} 各音色区域能量柱状图（0-10 刻度）`">
          <div class="vocal-eq__bar" v-for="band in eqBands" :key="band.name">
            <div class="vocal-eq__bar-track">
              <div class="vocal-eq__bar-fill" :style="{ height: `${Math.max(2, band.level * 100)}%` }"></div>
              <span
                class="vocal-eq__bar-val"
                :style="barValueStyle(band.level)"
                :aria-label="`${band.shortLabel} 能量 ${(band.level * 10).toFixed(1)} / 10`"
              >{{ (band.level * 10).toFixed(1) }}</span>
            </div>
            <span class="vocal-eq__bar-name">{{ band.shortLabel }}</span>
          </div>
        </div>
        <p class="vocal-eq__axis-note">刻度说明：柱底 = 0、柱顶 = 10；数值标在柱顶，是能量强度，不是演唱得分。</p>
      </section>

      <!-- 发声控制分析（按音区倾向叙述 + 数值参考折叠；替代旧的整体判断/行为推断/按音区对比三卡，避免判据冲突） -->
      <section
        v-if="vocal && ((vocal.controlNote && vocal.controlNote.trim()) || vocal.registerControls?.length)"
        class="vocal-panel"
        aria-label="发声控制分析"
      >
        <VocalControlCard
          :register-controls="vocal.registerControls || []"
          :break-events="vocal.breakEvents || []"
          :control-note="vocal.controlNote || ''"
        />
      </section>
    </template>
  </section>
</template>

<style scoped>
.vocal-panel {
  display: grid;
  gap: 14px;
  margin-top: 18px;
}
.vocal-eq {
  margin-top: 14px;
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.vocal-eq > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.vocal-eq > header small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.vocal-eq > header h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.vocal-eq__scale-tag {
  flex: none;
  margin-top: 2px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--border, #2a2f36);
  color: var(--muted, #9aa0a6);
  font-size: 10px;
  white-space: nowrap;
}
.vocal-eq__hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted, #9aa0a6);
}
.vocal-eq__warn { color: #ffb4a6; font-weight: 600; }
.vocal-eq__bars {
  display: flex;
  gap: 5px;
  margin-top: 14px;
  align-items: flex-end;
}
.vocal-eq__bar {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}
.vocal-eq__bar-track {
  position: relative;
  width: 100%;
  max-width: 34px;
  height: 108px;
  background: rgba(255, 255, 255, 0.07);
  border-radius: 5px;
}
.vocal-eq__bar-fill {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(180deg, var(--accent, #4aa3ff), rgba(74, 163, 255, 0.55));
  border-radius: 3px 3px 0 0;
}
.vocal-eq__bar-val {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 4px;
  border-radius: 5px;
  background: rgba(10, 13, 20, 0.85);
  color: #e8ecf3;
  font-size: 9px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  pointer-events: none;
}
.vocal-eq__bar-name {
  font-size: 10px;
  color: var(--muted, #9aa0a6);
  white-space: nowrap;
}
.vocal-eq__axis-note {
  margin: 10px 0 0;
  font-size: 10px;
  line-height: 1.5;
  color: var(--muted, #9aa0a6);
}
.spectrum-frequency {
  margin-top: 18px;
  padding: 16px;
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  border: 1px solid var(--border, #2a2f36);
}
.spectrum-frequency__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.spectrum-frequency__head small {
  color: var(--accent-dark, #4aa3ff);
  font-size: 10px;
  font-weight: 600;
}
.spectrum-frequency__head h2 {
  margin: 3px 0 0;
  font-size: 18px;
  letter-spacing: -0.4px;
}
.spectrum-frequency__hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--muted, #9aa0a6);
}
.spectrum-frequency .spectrum-canvas {
  margin-top: 12px;
  display: grid;
  gap: 12px;
}
.vocal-report__guide {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 13px;
  border-radius: 999px;
  border: 1px solid var(--border, #2a2f36);
  background: var(--surface, #14171a);
  color: var(--muted, #9aa0a6);
  font-size: 12px;
  font-weight: 500;
  text-decoration: none;
  transition: color 0.15s ease, border-color 0.15s ease;
}
.vocal-report__guide svg {
  width: 14px;
  height: 14px;
}
.vocal-report__guide:hover {
  color: var(--accent, #4aa3ff);
  border-color: var(--accent, #4aa3ff);
}
@media (max-width: 720px) {
  .spectrum-frequency__head { flex-direction: column; }
}
</style>
