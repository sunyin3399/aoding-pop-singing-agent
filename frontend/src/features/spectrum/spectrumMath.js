const COLOR_STOPS = [
  [-120, [7, 11, 30]],
  [-90, [91, 33, 112]],
  [-65, [179, 54, 93]],
  [-40, [241, 123, 42]],
  [-20, [252, 231, 105]],
  [0, [255, 255, 255]],
]

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

export function frameIndexAtTime(timeSeconds, frames) {
  if (!Array.isArray(frames) || frames.length <= 1) return 0

  const timeAt = (index) => Number(frames[index]?.timeSeconds)
  const firstTime = timeAt(0)
  const lastTime = timeAt(frames.length - 1)
  if (!Number.isFinite(firstTime) || !Number.isFinite(lastTime)) return 0

  const time = Number.isFinite(timeSeconds) ? timeSeconds : firstTime
  if (time <= firstTime) return 0
  if (time >= lastTime) return frames.length - 1

  let low = 1
  let high = frames.length - 1
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (timeAt(middle) < time) low = middle + 1
    else high = middle
  }

  const lower = low - 1
  return timeAt(low) - time <= time - timeAt(lower) ? low : lower
}

export function waterfallFrameIndexAtRow(row, frames) {
  const frameCount = Array.isArray(frames) ? frames.length : 0
  if (frameCount === 0) return 0
  return clamp(Math.floor(Number.isFinite(row) ? row : 0), 0, frameCount - 1)
}

export function frequencyToRatio(frequencyHz, minHz, maxHz) {
  if (!Number.isFinite(minHz) || !Number.isFinite(maxHz) || minHz <= 0 || maxHz <= minHz) return 0

  const frequency = clamp(Number.isFinite(frequencyHz) ? frequencyHz : minHz, minHz, maxHz)
  return Math.log(frequency / minHz) / Math.log(maxHz / minHz)
}

export function ratioToFrequency(ratio, minHz, maxHz) {
  if (!Number.isFinite(minHz) || !Number.isFinite(maxHz) || minHz <= 0 || maxHz <= minHz) return 0

  const amount = clamp(Number.isFinite(ratio) ? ratio : 0, 0, 1)
  return minHz * Math.pow(maxHz / minHz, amount)
}

export function ratioToTime(ratio, durationSeconds) {
  if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) return 0
  return clamp(Number.isFinite(ratio) ? ratio : 0, 0, 1) * durationSeconds
}

export function seekTimeFromKey(key, currentTime, durationSeconds, stepSeconds) {
  const duration = Number.isFinite(durationSeconds) && durationSeconds > 0 ? durationSeconds : 0
  const current = clamp(Number.isFinite(currentTime) ? currentTime : 0, 0, duration)
  const step = Number.isFinite(stepSeconds) && stepSeconds > 0 ? stepSeconds : duration / 100

  if (key === 'Home') return 0
  if (key === 'End') return duration
  if (key === 'ArrowLeft' || key === 'ArrowUp') return clamp(current - step, 0, duration)
  if (key === 'ArrowRight' || key === 'ArrowDown') return clamp(current + step, 0, duration)
  return null
}

export function dbfsToRatio(dbfs, minDb = -120, maxDb = 0) {
  if (!Number.isFinite(minDb) || !Number.isFinite(maxDb) || maxDb <= minDb) return 0
  const value = clamp(Number.isFinite(dbfs) ? dbfs : minDb, minDb, maxDb)
  return (value - minDb) / (maxDb - minDb)
}

export function dbfsToColor(dbfs) {
  const value = clamp(
    Number.isFinite(dbfs) ? dbfs : COLOR_STOPS[0][0],
    COLOR_STOPS[0][0],
    COLOR_STOPS[COLOR_STOPS.length - 1][0],
  )
  const stopIndex = COLOR_STOPS.findIndex(([stop]) => value <= stop)
  const upperIndex = stopIndex === -1 ? COLOR_STOPS.length - 1 : stopIndex
  const lowerIndex = Math.max(0, upperIndex - 1)
  const [lowerDb, lowerColor] = COLOR_STOPS[lowerIndex]
  const [upperDb, upperColor] = COLOR_STOPS[upperIndex]
  const amount = upperDb === lowerDb ? 0 : (value - lowerDb) / (upperDb - lowerDb)
  const color = lowerColor.map((channel, index) => Math.round(channel + (upperColor[index] - channel) * amount))
  return `rgb(${color.join(', ')})`
}
