const NOTE_NAMES = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B']
const LABELED_NOTES = new Set([0, 2, 4, 5, 7, 9, 11])

export function timeToX(time, duration, left, right) {
  return left + (Math.max(0, Math.min(duration, time)) / Math.max(duration, 0.001)) * (right - left)
}

export function midiToY(midi, minMidi, maxMidi, top, bottom) {
  return bottom - ((midi - minMidi) / Math.max(1, maxMidi - minMidi)) * (bottom - top)
}

export function createNoteTicks(minMidi, maxMidi) {
  const ticks = []
  for (let midi = Math.ceil(maxMidi); midi >= Math.floor(minMidi); midi -= 1) {
    const noteIndex = ((midi % 12) + 12) % 12
    ticks.push({ midi, label: LABELED_NOTES.has(noteIndex) ? `${NOTE_NAMES[noteIndex]}${Math.floor(midi / 12) - 1}` : '' })
  }
  return ticks
}

export function createPitchSegments(points) {
  const segments = []
  let current = []
  for (const point of points || []) {
    if (point.voiced && Number.isFinite(point.midi)) current.push(point)
    else if (current.length) { segments.push(current); current = [] }
  }
  if (current.length) segments.push(current)
  return segments
}
