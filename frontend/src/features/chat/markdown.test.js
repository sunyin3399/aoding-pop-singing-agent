import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { renderSafeMarkdown } from './markdown.js'

const window = new JSDOM('').window
const html = renderSafeMarkdown(`
### 练声资源

**每天练习**

- [NATS](https://www.nats.org/resources)
- [危险链接](javascript:alert(1))

| 类型 | 时间 |
| --- | --- |
| 热身 | 5 分钟 |

<img src=x onerror=alert(1)>
`, window)

assert.match(html, /<h3>练声资源<\/h3>/)
assert.match(html, /<strong>每天练习<\/strong>/)
assert.match(html, /<table>/)
assert.match(html, /href="https:\/\/www\.nats\.org\/resources"/)
assert.doesNotMatch(html, /javascript:/i)
assert.doesNotMatch(html, /<img/i)
assert.doesNotMatch(html, /onerror/i)

const paragraph = renderSafeMarkdown('第一行\n第二行', window)
assert.doesNotMatch(paragraph, /<br/i)

const resources = renderSafeMarkdown(`
1. **适合初学者唱的一些歌曲**
   - **URL**: [点击查看](https://example.com/first)
   - **目的**: 帮助新手快速上手。
   - **使用建议**: 从简单旋律开始。

2. **十首简单好唱的歌曲**
   - **URL**: [点击查看](https://example.com/second)
   - **目的**: 帮助零基础用户入门。
- **使用建议**: 作为日常练声材料。
`, window)
assert.equal((resources.match(/class="resource-item"/g) || []).length, 2)
assert.match(resources, /class="resource-title-link"[^>]*>适合初学者唱的一些歌曲<\/a>/)
assert.match(resources, /href="https:\/\/example\.com\/second"/)
assert.doesNotMatch(resources, />URL<\/strong>/)
assert.match(resources, /作为日常练声材料/)
assert.doesNotMatch(resources, /resource-field-label">目的<\/strong>\s*[:：]/)

const legacyAdvice = renderSafeMarkdown(`
1. **练声教程**
   - **URL**: [查看](https://example.com/tutorial)
   - **使用建议**: 每天练十分钟。
`, window)
assert.match(legacyAdvice, /resource-field-label">建议<\/strong>/)
assert.doesNotMatch(legacyAdvice, /使用建议/)

const fragmentedSongs = renderSafeMarkdown(`
### 推荐歌曲
1. **歌曲一**
- 练习重点：气息
1. **歌曲二**
- 练习重点：节奏
1. **歌曲三**
`, window)
assert.match(fragmentedSongs, /<ol start="1">/)
assert.match(fragmentedSongs, /<ol start="2">/)
assert.match(fragmentedSongs, /<ol start="3">/)

const canonicalLink = renderSafeMarkdown(
  '[模型自己编写的标题](https://www.bilibili.com/video/BV123)',
  window,
  [{ title: '网页抓取到的真实视频标题', url: 'https://www.bilibili.com/video/BV123' }],
)
assert.match(canonicalLink, />网页抓取到的真实视频标题<\/a>/)
assert.doesNotMatch(canonicalLink, /模型自己编写的标题/)

console.log('✓ renders common Markdown while removing unsafe HTML and links')
