import assert from 'node:assert/strict'
import { createNoteTicks, createPitchSegments, midiToY, timeToX } from './pitchMath.js'

assert.equal(timeToX(5, 10, 40, 540), 290)
assert.equal(midiToY(60, 48, 72, 20, 620), 320)
assert.deepEqual(createNoteTicks(57, 69).filter((tick) => tick.label).map((tick) => tick.label), ['A4', 'G4', 'F4', 'E4', 'D4', 'C4', 'B3', 'A3'])
assert.deepEqual(createPitchSegments([
  { voiced: true, timeSeconds: 0, midi: 60 },
  { voiced: true, timeSeconds: 0.1, midi: 61 },
  { voiced: false, timeSeconds: 0.2, midi: null },
  { voiced: true, timeSeconds: 0.3, midi: 62 },
]), [[{ voiced: true, timeSeconds: 0, midi: 60 }, { voiced: true, timeSeconds: 0.1, midi: 61 }], [{ voiced: true, timeSeconds: 0.3, midi: 62 }]])

console.log('✓ pitch chart math keeps axes fixed and breaks silent gaps')
