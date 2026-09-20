import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'

const filename = new URL('./KnowledgeReviewView.vue', import.meta.url)
const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename: filename.pathname })
if (errors.length) throw errors[0]

const template = descriptor.template?.content || ''
assert.doesNotMatch(template, /v-html/, 'untrusted Markdown must render as escaped text')
assert.match(template, /v-model="statusFilter"/)
assert.match(template, /v-model="reviewNote"/)
assert.match(template, /approveSelected/)
assert.match(template, /rejectSelected/)
assert.match(template, /candidate\.sources/)
assert.match(template, /publishedDocumentIds/)
assert.match(descriptor.scriptSetup?.content || '', /ElMessageBox\.confirm/)

const router = readFileSync(new URL('../router/index.js', import.meta.url), 'utf8')
assert.match(router, /path:\s*['"]\/admin\/knowledge-review['"]/)

console.log('✓ renders a safe candidate review workflow with filtering and review actions')
