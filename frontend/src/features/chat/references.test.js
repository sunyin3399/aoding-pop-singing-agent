import assert from 'node:assert/strict'
import { normalizeReferences } from './references.js'

const structured = normalizeReferences(JSON.stringify([
  {
    id: 'source-1',
    title: 'NATS 热声练习',
    url: 'https://www.nats.org/warm-up',
    excerpt: '五种常用热声方法',
    status: 'SCRAPED',
  },
  {
    title: '危险来源',
    url: 'javascript:alert(1)',
    status: 'SEARCH_ONLY',
  },
]))

assert.equal(structured.length, 2)
assert.deepEqual(structured[0], {
  id: 'source-1',
  title: 'NATS 热声练习',
  url: 'https://www.nats.org/warm-up',
  excerpt: '五种常用热声方法',
  status: 'SCRAPED',
  domain: 'www.nats.org',
  clickable: true,
})
assert.equal(structured[1].clickable, false)
assert.equal(structured[1].url, '')

const internal = normalizeReferences([{
  id: 'chunk-1',
  title: '周杰伦-《晴天》演唱技巧.md',
  url: '/api/ai/knowledge/documents/chunk-1',
  status: 'INTERNAL_APPROVED',
}])[0]
assert.equal(internal.clickable, false)
assert.equal(internal.domain, '内部知识库')

const legacy = normalizeReferences('参考资料:\\n[1] [练声文章](https://example.com/very/long/path)\\n[2] 百度文库 (https://wenku.baidu.com/view/1)')
assert.equal(legacy.length, 2)
assert.equal(legacy[0].title, '练声文章')
assert.equal(legacy[0].url, 'https://example.com/very/long/path')
assert.equal(legacy[1].title, '百度文库')
assert.equal(legacy[1].domain, 'wenku.baidu.com')
assert.equal(legacy[0].id, normalizeReferences('1. [练声文章](https://example.com/very/long/path)')[0].id)

assert.deepEqual(normalizeReferences('not a reference'), [])
assert.deepEqual(normalizeReferences(null), [])

console.log('✓ normalizes structured and legacy references into safe link records')
