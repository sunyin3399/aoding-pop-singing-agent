<template>
  <section v-if="hasSpectrogram" class="audio-evidence">
    <div class="evidence-heading">
      <div><small>时频声学证据</small><strong>梅尔声谱图</strong></div>
      <span>{{ spectrogram.minDb }}～{{ spectrogram.maxDb }} dB</span>
    </div>

    <div class="spectrogram-wrap">
      <canvas ref="canvas" role="img" aria-label="梅尔声谱热力图"></canvas>
      <div class="frequency-axis">
        <span>{{ formatFrequency(maxFrequency) }}</span>
        <span>{{ formatFrequency(middleFrequency) }}</span>
        <span>{{ formatFrequency(minFrequency) }}</span>
      </div>
      <div class="energy-legend"><span>弱</span><i></i><span>强</span></div>
    </div>

    <div class="time-axis">
      <span>0 秒</span><span>{{ (duration / 2).toFixed(1) }} 秒</span><span>{{ duration.toFixed(1) }} 秒</span>
    </div>

    <div v-if="hasFeatures" class="feature-grid">
      <div><small>谱质心</small><strong>{{ formatFrequency(features.centroidHz) }}</strong><span>声音明亮度重心</span></div>
      <div><small>谱带宽</small><strong>{{ formatFrequency(features.bandwidthHz) }}</strong><span>频率能量分散度</span></div>
      <div><small>85% 滚降点</small><strong>{{ formatFrequency(features.rolloffHz) }}</strong><span>高频延伸位置</span></div>
      <div><small>谱平坦度</small><strong>{{ formatPercent(features.flatness) }}</strong><span>越高越接近噪声</span></div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ analysis: { type: Object, required: true } })
const canvas = ref(null)
let observer

const visualization = computed(() => props.analysis?.visualization || {})
const spectrogram = computed(() => visualization.value.melSpectrogram || {})
const features = computed(() => visualization.value.spectralFeatures || {})
const hasSpectrogram = computed(() => (spectrogram.value.levels?.length || 0) > 0)
const hasFeatures = computed(() => Number.isFinite(features.value.centroidHz))
const duration = computed(() => Math.max(Number(props.analysis?.durationSeconds) || 0, 0.01))
const frequencies = computed(() => spectrogram.value.frequenciesHz || [])
const minFrequency = computed(() => frequencies.value[0] || 0)
const maxFrequency = computed(() => frequencies.value.at(-1) || 0)
const middleFrequency = computed(() => frequencies.value[Math.floor(frequencies.value.length / 2)] || 0)

function formatFrequency(value) {
  if (!Number.isFinite(value)) return '—'
  return value >= 1000 ? `${(value / 1000).toFixed(value >= 5000 ? 0 : 1)} kHz` : `${Math.round(value)} Hz`
}

function formatPercent(value) {
  return Number.isFinite(value) ? `${Math.round(value * 100)}%` : '—'
}

function color(level) {
  const stops = [[7, 11, 30], [38, 24, 74], [91, 33, 112], [179, 54, 93], [241, 123, 42], [252, 231, 105]]
  const scaled = Math.max(0, Math.min(1, Number(level) / 255)) * (stops.length - 1)
  const index = Math.min(stops.length - 2, Math.floor(scaled))
  const amount = scaled - index
  return stops[index].map((value, channel) => Math.round(value + (stops[index + 1][channel] - value) * amount))
}

async function draw() {
  await nextTick()
  const target = canvas.value
  const frames = spectrogram.value.levels || []
  if (!target || !frames.length) return
  const width = Math.max(1, Math.round(target.clientWidth))
  const height = Math.max(1, Math.round(target.clientHeight))
  target.width = width
  target.height = height
  const context = target.getContext('2d')
  const image = context.createImageData(width, height)
  const bands = frames[0]?.length || 1
  for (let y = 0; y < height; y++) {
    const band = Math.min(bands - 1, Math.floor((height - 1 - y) / height * bands))
    for (let x = 0; x < width; x++) {
      const frame = Math.min(frames.length - 1, Math.floor(x / width * frames.length))
      const [red, green, blue] = color(frames[frame]?.[band] || 0)
      const offset = (y * width + x) * 4
      image.data[offset] = red
      image.data[offset + 1] = green
      image.data[offset + 2] = blue
      image.data[offset + 3] = 255
    }
  }
  context.putImageData(image, 0, 0)
}

watch(() => spectrogram.value.levels, draw)
onMounted(() => {
  draw()
  observer = new ResizeObserver(draw)
  if (canvas.value) observer.observe(canvas.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<style scoped>
.audio-evidence { margin-top: 18px; padding: 18px; border: 1px solid rgba(107, 93, 211, .14); border-radius: 18px; background: rgba(249, 248, 255, .72); }
.evidence-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.evidence-heading div { display: flex; flex-direction: column; gap: 2px; }
.evidence-heading small, .evidence-heading span, .feature-grid small, .feature-grid span, .time-axis { color: #817f91; font-size: 12px; }
.evidence-heading strong { color: #29263a; font-size: 16px; }
.spectrogram-wrap { position: relative; overflow: hidden; border-radius: 12px; background: #070b1e; box-shadow: inset 0 0 0 1px rgba(255,255,255,.08); }
.spectrogram-wrap canvas { display: block; width: 100%; height: 220px; }
.frequency-axis { position: absolute; inset: 10px auto 10px 8px; display: flex; flex-direction: column; justify-content: space-between; color: rgba(255,255,255,.72); font-size: 10px; text-shadow: 0 1px 3px #000; pointer-events: none; }
.energy-legend { position: absolute; right: 9px; bottom: 8px; display: flex; align-items: center; gap: 5px; color: rgba(255,255,255,.76); font-size: 10px; }
.energy-legend i { width: 70px; height: 6px; border-radius: 99px; background: linear-gradient(90deg, #070b1e, #5b2170, #f17b2a, #fce769); }
.time-axis { display: flex; justify-content: space-between; margin-top: 5px; }
.feature-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 13px; }
.feature-grid div { display: flex; min-width: 0; flex-direction: column; gap: 3px; padding: 10px 11px; border: 1px solid rgba(107, 93, 211, .1); border-radius: 12px; background: #fff; }
.feature-grid strong { color: #302b4f; font-size: 15px; }
@media (max-width: 720px) {
  .feature-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .spectrogram-wrap canvas { height: 180px; }
}
</style>
