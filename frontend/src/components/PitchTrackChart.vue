<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createNoteTicks, createPitchSegments, midiToY, timeToX } from '../features/pitch/pitchMath.js'

const props = defineProps({ result: { type: Object, required: true } })
const canvas = ref(null)
let observer

function draw() {
  const element = canvas.value
  if (!element) return
  const width = Math.max(320, element.clientWidth)
  const height = 620
  const ratio = window.devicePixelRatio || 1
  element.width = width * ratio
  element.height = height * ratio
  const context = element.getContext('2d')
  context.scale(ratio, ratio)
  context.fillStyle = '#050807'
  context.fillRect(0, 0, width, height)

  const left = 54, right = width - 14, waveTop = 12, waveBottom = 82, plotTop = 112, plotBottom = 584
  const duration = Math.max(0.1, Number(props.result.durationSeconds) || 0.1)
  const minMidi = Number(props.result.minMidi) || 36
  const maxMidi = Number(props.result.maxMidi) || 84

  context.strokeStyle = '#173820'
  context.lineWidth = 1
  context.beginPath()
  for (const point of props.result.waveform || []) {
    const x = timeToX(point.timeSeconds, duration, left, right)
    const y1 = (waveTop + waveBottom) / 2 - point.max * 30
    const y2 = (waveTop + waveBottom) / 2 - point.min * 30
    context.moveTo(x, y1); context.lineTo(x, y2)
  }
  context.stroke()

  context.font = '11px ui-monospace, SFMono-Regular, monospace'
  for (const tick of createNoteTicks(minMidi, maxMidi)) {
    const y = midiToY(tick.midi, minMidi, maxMidi, plotTop, plotBottom)
    context.strokeStyle = tick.label ? '#24312a' : '#111914'
    context.beginPath(); context.moveTo(left, y); context.lineTo(right, y); context.stroke()
    if (tick.label) { context.fillStyle = '#849189'; context.textAlign = 'right'; context.fillText(tick.label, left - 7, y + 4) }
  }
  const timeStep = duration <= 15 ? 1 : duration <= 60 ? 5 : 10
  context.textAlign = 'center'
  for (let time = 0; time <= duration; time += timeStep) {
    const x = timeToX(time, duration, left, right)
    context.strokeStyle = '#1a241e'; context.beginPath(); context.moveTo(x, plotTop); context.lineTo(x, plotBottom); context.stroke()
    context.fillStyle = '#748078'; context.fillText(`${time}s`, x, 605)
  }
  context.strokeStyle = '#21df4b'; context.shadowColor = '#21df4b'; context.shadowBlur = 5; context.lineWidth = 2.2
  for (const segment of createPitchSegments(props.result.points)) {
    context.beginPath()
    segment.forEach((point, index) => {
      const x = timeToX(point.timeSeconds, duration, left, right)
      const y = midiToY(point.midi, minMidi, maxMidi, plotTop, plotBottom)
      if (index) context.lineTo(x, y); else context.moveTo(x, y)
    })
    context.stroke()
  }
  context.shadowBlur = 0
}

onMounted(() => { observer = new ResizeObserver(draw); observer.observe(canvas.value); draw() })
onBeforeUnmount(() => observer?.disconnect())
watch(() => props.result, draw, { deep: true })
</script>

<template><canvas ref="canvas" class="pitch-track-canvas" role="img" aria-label="横轴为时间、纵轴为固定音高的演唱轨迹图"></canvas></template>

<style scoped>
.pitch-track-canvas { display: block; width: 100%; height: 620px; border: 1px solid #24312a; border-radius: 14px; background: #050807; }
@media (max-width: 700px) { .pitch-track-canvas { height: 500px; } }
</style>
