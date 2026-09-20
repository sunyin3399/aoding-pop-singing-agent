<template>
  <section class="spectrum-canvas" aria-label="音频频谱图">
    <div class="spectrum-panel">
      <header class="plot-heading">
        <div>
          <small>当前频谱</small>
          <strong>频率能量</strong>
        </div>
        <div class="spectrum-legend" aria-label="频谱图例">
          <span><i class="current-swatch"></i>当前帧</span>
          <span><i class="peak-swatch"></i>全程峰值</span>
        </div>
      </header>
      <canvas ref="upperCanvas" class="upper-canvas" role="img" aria-label="当前帧与全程峰值频谱"></canvas>
    </div>

    <template v-if="waterfall">
    <div class="spectrum-panel waterfall-panel">
      <header class="plot-heading">
        <div>
          <small>完整音频</small>
          <strong>瀑布频谱</strong>
        </div>
        <span class="interaction-hint">拖动图表可跳转播放位置</span>
      </header>
      <div class="waterfall-shell">
        <canvas
          ref="lowerCanvas"
          class="lower-canvas"
          role="img"
          aria-label="以时间为纵轴的瀑布频谱"
        ></canvas>
        <canvas
          ref="lowerOverlayCanvas"
          class="lower-overlay"
          role="slider"
          tabindex="0"
          aria-label="瀑布频谱播放位置"
          aria-orientation="vertical"
          aria-describedby="waterfall-keyboard-help"
          aria-valuemin="0"
          :aria-valuemax="durationSeconds()"
          :aria-valuenow="clampedCurrentTime()"
          :aria-valuetext="formatTime(clampedCurrentTime())"
          @pointerdown="handlePointerDown"
          @pointermove="handlePointerMove"
          @pointerup="handlePointerUp"
          @pointercancel="handlePointerUp"
          @pointerleave="handlePointerLeave"
          @keydown="handleKeyDown"
        ></canvas>
        <span id="waterfall-keyboard-help" class="visually-hidden">
          上方向键跳到更早时间，下方向键跳到更晚时间；Home 跳到开头，End 跳到结尾。
        </span>
        <div
          v-if="hover"
          class="spectrum-tooltip"
          :style="{ left: `${hover.tooltipX}px`, top: `${hover.tooltipY}px` }"
          aria-live="polite"
        >
          <strong>{{ formatTime(hover.time) }} · {{ formatFrequency(hover.frequency) }}</strong>
          <span>{{ formatDbfs(hover.dbfs) }}</span>
        </div>
      </div>
    </div>
    </template>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  dbfsToColor,
  dbfsToRatio,
  frameIndexAtTime,
  frequencyToRatio,
  ratioToFrequency,
  ratioToTime,
  seekTimeFromKey,
  waterfallFrameIndexAtRow,
} from '../features/spectrum/spectrumMath.js'

const props = defineProps({
  result: { type: Object, required: true },
  currentTime: { type: Number, default: 0 },
  waterfall: { type: Boolean, default: true },
})
const emit = defineEmits(['seek'])

const upperCanvas = ref(null)
const lowerCanvas = ref(null)
const lowerOverlayCanvas = ref(null)
const offscreenWaterfall = ref(null)
const hover = ref(null)

const UPPER_INSETS = Object.freeze({ left: 54, right: 18, top: 20, bottom: 32 })
const WATERFALL_INSETS = Object.freeze({ left: 54, right: 18, top: 14, bottom: 32 })
const FREQUENCY_TICKS = Object.freeze([20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000])
const DBFS_TICKS = Object.freeze([-120, -90, -60, -30, 0])
const COLOR_CACHE = new Map()

let resizeObserver
let animationId = 0
let waterfallBuildId = 0
let waterfallBuildVersion = 0
let drawUpperNextFrame = false
let drawWaterfallNextFrame = false
let drawOverlayNextFrame = false
let lastSpectrumFrame = -1
let isDragging = false

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function finite(value, fallback) {
  return Number.isFinite(value) ? value : fallback
}

function frequencyRange() {
  const frequencies = props.result?.frequenciesHz || []
  const min = finite(props.result?.minFrequencyHz, finite(frequencies[0], 20))
  const requestedMax = finite(props.result?.maxFrequencyHz, finite(frequencies.at(-1), min))
  const nyquist = finite(props.result?.nyquistHz, requestedMax)
  return { min, max: Math.max(min, Math.min(requestedMax, nyquist, 20000)) }
}

function dbfsRange() {
  const min = finite(props.result?.minDbfs, -120)
  return { min, max: Math.max(min, finite(props.result?.maxDbfs, 0)) }
}

function durationSeconds() {
  return Math.max(0, finite(props.result?.durationSeconds, 0))
}

function clampedCurrentTime() {
  return clamp(finite(props.currentTime, 0), 0, durationSeconds())
}

function visibleBucketCount() {
  const frequencies = props.result?.frequenciesHz || []
  const { max } = frequencyRange()
  const firstHidden = frequencies.findIndex((frequency) => frequency > max)
  return firstHidden === -1 ? frequencies.length : firstHidden
}

function plotRect(width, height, insets) {
  const left = Math.min(insets.left, width * 0.3)
  const top = Math.min(insets.top, height * 0.2)
  const right = Math.max(left + 1, width - insets.right)
  const bottom = Math.max(top + 1, height - insets.bottom)
  return { left, top, right, bottom, width: right - left, height: bottom - top }
}

function prepareCanvas(canvas) {
  if (!canvas) return null
  const width = Math.max(1, Math.round(canvas.clientWidth))
  const height = Math.max(1, Math.round(canvas.clientHeight))
  const dpr = Math.max(1, window.devicePixelRatio || 1)
  const backingWidth = Math.max(1, Math.round(width * dpr))
  const backingHeight = Math.max(1, Math.round(height * dpr))
  if (canvas.width !== backingWidth || canvas.height !== backingHeight) {
    canvas.width = backingWidth
    canvas.height = backingHeight
  }
  const context = canvas.getContext('2d')
  context.setTransform(dpr, 0, 0, dpr, 0, 0)
  return { context, width, height }
}

function formatFrequency(value) {
  if (!Number.isFinite(value)) return '— Hz'
  if (value >= 1000) {
    const digits = value >= 10000 ? 0 : 1
    return `${(value / 1000).toFixed(digits)} kHz`
  }
  return `${Math.round(value)} Hz`
}

function formatTime(value) {
  return Number.isFinite(value) ? `${value.toFixed(2)} 秒` : '— 秒'
}

function formatDbfs(value) {
  return Number.isFinite(value) ? `${value.toFixed(1)} dBFS` : '— dBFS'
}

function visibleFrequencyTicks(width) {
  const { min, max } = frequencyRange()
  const candidates = width < 520
    ? FREQUENCY_TICKS.filter((tick) => tick === 20 || tick === 100 || tick === 1000 || tick === 10000 || tick === 20000)
    : FREQUENCY_TICKS
  return candidates.filter((tick) => tick >= min && tick <= max)
}

function drawBackground(context, width, height) {
  context.clearRect(0, 0, width, height)
  context.fillStyle = '#070b1e'
  context.fillRect(0, 0, width, height)
}

function drawFrequencyGrid(context, plot, width, labelsAtBottom = true) {
  const { min, max } = frequencyRange()
  context.save()
  context.font = '11px system-ui, sans-serif'
  context.textAlign = 'center'
  context.textBaseline = 'top'
  for (const frequency of visibleFrequencyTicks(width)) {
    const x = plot.left + frequencyToRatio(frequency, min, max) * plot.width
    context.strokeStyle = 'rgba(255, 255, 255, 0.09)'
    context.lineWidth = 1
    context.beginPath()
    context.moveTo(x, plot.top)
    context.lineTo(x, plot.bottom)
    context.stroke()
    if (labelsAtBottom) {
      context.fillStyle = 'rgba(226, 229, 246, 0.72)'
      context.fillText(formatFrequency(frequency), x, plot.bottom + 8)
    }
  }
  context.restore()
}

function drawDbfsGrid(context, plot) {
  const { min, max } = dbfsRange()
  context.save()
  context.font = '11px system-ui, sans-serif'
  context.textAlign = 'right'
  context.textBaseline = 'middle'
  for (const dbfs of DBFS_TICKS.filter((tick) => tick >= min && tick <= max)) {
    const y = plot.bottom - dbfsToRatio(dbfs, min, max) * plot.height
    context.strokeStyle = 'rgba(255, 255, 255, 0.09)'
    context.lineWidth = 1
    context.beginPath()
    context.moveTo(plot.left, y)
    context.lineTo(plot.right, y)
    context.stroke()
    context.fillStyle = 'rgba(226, 229, 246, 0.72)'
    context.fillText(`${dbfs}`, plot.left - 8, y)
  }
  context.restore()
}

function drawSpectrumLine(context, values, color, lineWidth, plot) {
  const frequencies = props.result?.frequenciesHz || []
  const { min: minFrequency, max: maxFrequency } = frequencyRange()
  const { min: minDbfs, max: maxDbfs } = dbfsRange()
  const count = Math.min(frequencies.length, values?.length || 0)
  if (!count) return

  context.save()
  context.strokeStyle = color
  context.lineWidth = lineWidth
  context.lineJoin = 'round'
  context.lineCap = 'round'
  context.beginPath()
  let started = false
  for (let index = 0; index < count; index++) {
    const frequency = frequencies[index]
    const dbfs = values[index]
    if (!Number.isFinite(frequency) || !Number.isFinite(dbfs) || frequency < minFrequency || frequency > maxFrequency) continue
    const x = plot.left + frequencyToRatio(frequency, minFrequency, maxFrequency) * plot.width
    const y = plot.bottom - dbfsToRatio(dbfs, minDbfs, maxDbfs) * plot.height
    if (started) context.lineTo(x, y)
    else {
      context.moveTo(x, y)
      started = true
    }
  }
  if (started) context.stroke()
  context.restore()
}

function strongestVisibleBin(values) {
  const frequencies = props.result?.frequenciesHz || []
  const { min, max } = frequencyRange()
  let strongestIndex = -1
  let strongestDbfs = -Infinity
  for (let index = 0; index < (values?.length || 0); index++) {
    const frequency = frequencies[index]
    if (
      Number.isFinite(frequency)
      && frequency >= min
      && frequency <= max
      && Number.isFinite(values[index])
      && values[index] > strongestDbfs
    ) {
      strongestDbfs = values[index]
      strongestIndex = index
    }
  }
  return { index: strongestIndex, dbfs: strongestDbfs }
}

function drawStrongestLabel(context, values, plot) {
  const frequencies = props.result?.frequenciesHz || []
  const { index, dbfs } = strongestVisibleBin(values)
  const frequency = frequencies[index]
  if (!Number.isFinite(frequency) || !Number.isFinite(dbfs)) return

  const { min: minFrequency, max: maxFrequency } = frequencyRange()
  if (frequency < minFrequency || frequency > maxFrequency) return
  const { min: minDbfs, max: maxDbfs } = dbfsRange()
  const x = plot.left + frequencyToRatio(frequency, minFrequency, maxFrequency) * plot.width
  const y = plot.bottom - dbfsToRatio(dbfs, minDbfs, maxDbfs) * plot.height
  const label = `${formatFrequency(frequency)}  ${formatDbfs(dbfs)}`

  context.save()
  context.font = '600 11px system-ui, sans-serif'
  const boxWidth = context.measureText(label).width + 16
  const boxHeight = 24
  const boxX = clamp(x + 8, plot.left + 4, plot.right - boxWidth - 4)
  const boxY = clamp(y - boxHeight - 8, plot.top + 4, plot.bottom - boxHeight - 4)
  context.fillStyle = 'rgba(19, 22, 46, 0.9)'
  context.strokeStyle = 'rgba(255, 216, 77, 0.55)'
  context.lineWidth = 1
  context.beginPath()
  context.roundRect(boxX, boxY, boxWidth, boxHeight, 7)
  context.fill()
  context.stroke()
  context.fillStyle = '#fff2a6'
  context.textAlign = 'left'
  context.textBaseline = 'middle'
  context.fillText(label, boxX + 8, boxY + boxHeight / 2)
  context.restore()
}

function drawSpectrum() {
  const prepared = prepareCanvas(upperCanvas.value)
  if (!prepared) return
  const { context, width, height } = prepared
  const plot = plotRect(width, height, UPPER_INSETS)
  drawBackground(context, width, height)
  drawFrequencyGrid(context, plot, width)
  drawDbfsGrid(context, plot)

  const frames = props.result?.frames || []
  const frameIndex = frameIndexAtTime(props.currentTime, frames)
  const currentValues = frames[frameIndex]?.dbfs || []
  drawSpectrumLine(context, props.result?.peakDbfs || [], '#ff6078', 1.5, plot)
  drawSpectrumLine(context, currentValues, '#ffd84d', 2, plot)
  drawStrongestLabel(context, currentValues, plot)
  lastSpectrumFrame = frameIndex
}

function colorChannels(dbfs) {
  const key = Math.round(finite(dbfs, -120) * 10) / 10
  if (!COLOR_CACHE.has(key)) {
    const channels = dbfsToColor(key).match(/\d+/g)?.map(Number) || [7, 11, 30]
    COLOR_CACHE.set(key, channels)
  }
  return COLOR_CACHE.get(key)
}

function cancelWaterfallBuild() {
  waterfallBuildVersion += 1
  if (waterfallBuildId) cancelAnimationFrame(waterfallBuildId)
  waterfallBuildId = 0
}

function buildWaterfall() {
  cancelWaterfallBuild()
  offscreenWaterfall.value = null
  const frames = props.result?.frames || []
  const bucketCount = props.result?.frequenciesHz?.length || frames[0]?.dbfs?.length || 0
  if (!frames.length || !bucketCount) {
    scheduleDraw(false, true, true)
    return
  }

  const version = waterfallBuildVersion
  const target = document.createElement('canvas')
  target.width = bucketCount
  target.height = frames.length
  const context = target.getContext('2d')
  const image = context.createImageData(bucketCount, frames.length)
  let row = 0

  function fillChunk() {
    if (version !== waterfallBuildVersion) return
    const end = Math.min(frames.length, row + 32)
    for (; row < end; row++) {
      const frameIndex = waterfallFrameIndexAtRow(row, frames)
      const values = frames[frameIndex]?.dbfs || []
      for (let column = 0; column < bucketCount; column++) {
        const [red, green, blue] = colorChannels(values[column])
        const offset = (row * bucketCount + column) * 4
        image.data[offset] = red
        image.data[offset + 1] = green
        image.data[offset + 2] = blue
        image.data[offset + 3] = 255
      }
    }
    if (row < frames.length) {
      waterfallBuildId = requestAnimationFrame(fillChunk)
      return
    }
    context.putImageData(image, 0, 0)
    offscreenWaterfall.value = target
    waterfallBuildId = 0
    scheduleDraw(false, true, true)
  }

  waterfallBuildId = requestAnimationFrame(fillChunk)
}

function drawTimeGrid(context, plot) {
  const duration = durationSeconds()
  context.save()
  context.font = '11px system-ui, sans-serif'
  context.textAlign = 'right'
  context.textBaseline = 'middle'
  for (const ratio of [0, 0.25, 0.5, 0.75, 1]) {
    const y = plot.top + ratio * plot.height
    context.strokeStyle = 'rgba(255, 255, 255, 0.1)'
    context.lineWidth = 1
    context.beginPath()
    context.moveTo(plot.left, y)
    context.lineTo(plot.right, y)
    context.stroke()
    context.fillStyle = 'rgba(226, 229, 246, 0.72)'
    context.fillText(`${(duration * ratio).toFixed(duration < 10 ? 1 : 0)}s`, plot.left - 8, y)
  }
  context.restore()
}

function drawWaterfallBackground() {
  const prepared = prepareCanvas(lowerCanvas.value)
  if (!prepared) return
  const { context, width, height } = prepared
  const plot = plotRect(width, height, WATERFALL_INSETS)
  drawBackground(context, width, height)

  if (offscreenWaterfall.value) {
    context.imageSmoothingEnabled = true
    const sourceWidth = Math.max(1, visibleBucketCount())
    context.drawImage(
      offscreenWaterfall.value,
      0,
      0,
      sourceWidth,
      offscreenWaterfall.value.height,
      plot.left,
      plot.top,
      plot.width,
      plot.height,
    )
  } else if (props.result?.frames?.length) {
    context.fillStyle = 'rgba(226, 229, 246, 0.7)'
    context.font = '12px system-ui, sans-serif'
    context.textAlign = 'center'
    context.textBaseline = 'middle'
    context.fillText('正在绘制瀑布图…', plot.left + plot.width / 2, plot.top + plot.height / 2)
  }

  drawFrequencyGrid(context, plot, width)
  drawTimeGrid(context, plot)
}

function drawWaterfallOverlay() {
  const prepared = prepareCanvas(lowerOverlayCanvas.value)
  if (!prepared) return
  const { context, width, height } = prepared
  const plot = plotRect(width, height, WATERFALL_INSETS)
  context.clearRect(0, 0, width, height)

  const duration = durationSeconds()
  if (duration > 0) {
    const currentRatio = clampedCurrentTime() / duration
    const cursorY = plot.top + currentRatio * plot.height
    context.save()
    context.strokeStyle = '#ffffff'
    context.lineWidth = 1.5
    context.shadowColor = 'rgba(0, 0, 0, 0.8)'
    context.shadowBlur = 3
    context.beginPath()
    context.moveTo(plot.left, cursorY)
    context.lineTo(plot.right, cursorY)
    context.stroke()
    context.restore()
  }

  if (hover.value) {
    const { min, max } = frequencyRange()
    const hoverX = plot.left + frequencyToRatio(hover.value.frequency, min, max) * plot.width
    const hoverY = plot.top + (duration > 0 ? hover.value.time / duration : 0) * plot.height
    context.save()
    context.strokeStyle = 'rgba(255, 255, 255, 0.68)'
    context.lineWidth = 1
    context.setLineDash([4, 4])
    context.beginPath()
    context.moveTo(hoverX, plot.top)
    context.lineTo(hoverX, plot.bottom)
    context.moveTo(plot.left, hoverY)
    context.lineTo(plot.right, hoverY)
    context.stroke()
    context.restore()
  }
}

function scheduleDraw(upper = true, waterfall = true, overlay = true) {
  drawUpperNextFrame ||= upper
  drawWaterfallNextFrame ||= waterfall
  drawOverlayNextFrame ||= overlay
  if (animationId) return
  animationId = requestAnimationFrame(() => {
    animationId = 0
    const shouldDrawUpper = drawUpperNextFrame
    const shouldDrawWaterfall = drawWaterfallNextFrame
    const shouldDrawOverlay = drawOverlayNextFrame
    drawUpperNextFrame = false
    drawWaterfallNextFrame = false
    drawOverlayNextFrame = false
    if (shouldDrawUpper) drawSpectrum()
    if (shouldDrawWaterfall) drawWaterfallBackground()
    if (shouldDrawOverlay) drawWaterfallOverlay()
  })
}

function nearestFrequencyIndex(frequency) {
  const frequencies = props.result?.frequenciesHz || []
  const visibleCount = visibleBucketCount()
  if (!visibleCount) return -1
  let low = 0
  let high = visibleCount - 1
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (frequencies[middle] < frequency) low = middle + 1
    else high = middle
  }
  if (low === 0) return 0
  return Math.abs(frequencies[low] - frequency) < Math.abs(frequencies[low - 1] - frequency) ? low : low - 1
}

function updateHover(event) {
  const canvas = lowerOverlayCanvas.value
  if (!canvas) return null
  const bounds = canvas.getBoundingClientRect()
  const plot = plotRect(bounds.width, bounds.height, WATERFALL_INSETS)
  const localX = event.clientX - bounds.left
  const localY = event.clientY - bounds.top
  const xRatio = clamp((localX - plot.left) / plot.width, 0, 1)
  const yRatio = clamp((localY - plot.top) / plot.height, 0, 1)
  const { min, max } = frequencyRange()
  const frequency = ratioToFrequency(xRatio, min, max)
  const time = ratioToTime(yRatio, durationSeconds())
  const frames = props.result?.frames || []
  const frameIndex = frameIndexAtTime(time, frames)
  const frequencyIndex = nearestFrequencyIndex(frequency)
  const bucketFrequency = props.result?.frequenciesHz?.[frequencyIndex]
  const dbfs = frames[frameIndex]?.dbfs?.[frequencyIndex]

  hover.value = {
    frequency: Number.isFinite(bucketFrequency) ? bucketFrequency : frequency,
    time,
    dbfs: Number.isFinite(dbfs) ? dbfs : null,
    tooltipX: clamp(localX + 12, 8, Math.max(8, bounds.width - 190)),
    tooltipY: clamp(localY + 12, 8, Math.max(8, bounds.height - 54)),
  }
  scheduleDraw(false, false, true)
  return time
}

function seekFromPointer(event) {
  const time = updateHover(event)
  if (Number.isFinite(time)) emit('seek', time)
}

function handlePointerDown(event) {
  isDragging = true
  event.preventDefault()
  event.currentTarget.focus({ preventScroll: true })
  event.currentTarget.setPointerCapture?.(event.pointerId)
  seekFromPointer(event)
}

function handlePointerMove(event) {
  if (isDragging) seekFromPointer(event)
  else updateHover(event)
}

function handlePointerUp(event) {
  if (!isDragging) return
  isDragging = false
  if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
    event.currentTarget.releasePointerCapture(event.pointerId)
  }
  updateHover(event)
}

function handlePointerLeave() {
  if (isDragging) return
  hover.value = null
  scheduleDraw(false, false, true)
}

function handleKeyDown(event) {
  const time = seekTimeFromKey(
    event.key,
    clampedCurrentTime(),
    durationSeconds(),
    props.result?.hopSeconds,
  )
  if (time === null) return
  event.preventDefault()
  emit('seek', time)
}

watch(() => props.currentTime, () => {
  const frames = props.result?.frames || []
  const frameIndex = frameIndexAtTime(props.currentTime, frames)
  scheduleDraw(frameIndex !== lastSpectrumFrame, false, true)
})

watch(() => props.result, () => {
  lastSpectrumFrame = -1
  hover.value = null
  buildWaterfall()
  scheduleDraw(true, true, true)
})

onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    lastSpectrumFrame = -1
    scheduleDraw(true, true, true)
  })
  if (upperCanvas.value) resizeObserver.observe(upperCanvas.value)
  if (lowerCanvas.value) resizeObserver.observe(lowerCanvas.value)
  buildWaterfall()
  scheduleDraw(true, true, true)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  cancelWaterfallBuild()
  if (animationId) cancelAnimationFrame(animationId)
  animationId = 0
})
</script>

<style scoped>
.spectrum-canvas { display: grid; gap: 18px; }
.spectrum-panel { overflow: hidden; border: 1px solid rgba(107, 93, 211, .16); border-radius: 18px; background: rgba(249, 248, 255, .78); }
.plot-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px 10px; }
.plot-heading > div:first-child { display: flex; flex-direction: column; gap: 2px; }
.plot-heading small, .interaction-hint { color: #817f91; font-size: 12px; }
.plot-heading strong { color: #29263a; font-size: 16px; }
.spectrum-legend { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; color: #666278; font-size: 12px; }
.spectrum-legend span { display: inline-flex; align-items: center; gap: 5px; }
.spectrum-legend i { width: 15px; height: 3px; border-radius: 99px; }
.current-swatch { background: #ffd84d; }
.peak-swatch { background: #ff6078; }
.upper-canvas, .lower-canvas, .lower-overlay { display: block; width: 100%; }
.upper-canvas, .lower-canvas { background: #070b1e; }
.upper-canvas { height: 280px; }
.waterfall-shell { position: relative; }
.lower-canvas { height: 430px; pointer-events: none; }
.lower-overlay { position: absolute; inset: 0; height: 100%; cursor: crosshair; background: transparent; touch-action: none; }
.lower-overlay:active { cursor: ns-resize; }
.lower-overlay:focus-visible { outline: 2px solid #ffd84d; outline-offset: -3px; }
.spectrum-tooltip { position: absolute; z-index: 2; display: flex; width: max-content; max-width: 180px; flex-direction: column; gap: 2px; padding: 7px 9px; border: 1px solid rgba(255, 255, 255, .18); border-radius: 8px; color: #fff; background: rgba(15, 18, 39, .92); box-shadow: 0 6px 18px rgba(0, 0, 0, .25); font-size: 11px; font-variant-numeric: tabular-nums; pointer-events: none; }
.spectrum-tooltip strong { font-size: 11px; font-weight: 600; }
.spectrum-tooltip span { color: #fff2a6; }

@media (max-width: 720px) {
  .plot-heading { align-items: flex-start; padding-inline: 14px; }
  .spectrum-legend { gap: 7px 10px; }
  .interaction-hint { max-width: 130px; text-align: right; }
  .upper-canvas { height: 240px; }
  .lower-canvas { height: 360px; }
}
</style>
