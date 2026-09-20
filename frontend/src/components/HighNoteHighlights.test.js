import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('./HighNoteHighlights.vue', import.meta.url), 'utf8')
assert.match(source, /精选高音片段/)
assert.match(source, /最长连续/)
assert.match(source, /低频/)
assert.match(source, /中频/)
assert.match(source, /高频/)
assert.match(source, /自动推测仅供筛选重点片段/)
assert.match(source, /未检测到足够可靠的高音片段/)
console.log('✓ high note cards expose evidence, confidence and safe inference copy')
