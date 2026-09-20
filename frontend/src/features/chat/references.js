function safeLink(value) {
  const raw = String(value || '').trim()
  if (!raw) return { url: '', domain: '', clickable: false }
  if (raw.startsWith('/')) return { url: raw, domain: '站内资料', clickable: true }
  try {
    const parsed = new URL(raw)
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('unsafe protocol')
    return { url: parsed.href, domain: parsed.hostname, clickable: true }
  } catch {
    return { url: '', domain: '', clickable: false }
  }
}

function stableId(title, url) {
  const input = `${title}|${url}`
  let hash = 2166136261
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return `reference-${(hash >>> 0).toString(16)}`
}

function normalizeItem(item) {
  const title = String(item?.title || '').trim()
  if (!title) return null
  const link = safeLink(item.url)
  const status = String(item.status || 'SEARCH_ONLY')
  return {
    id: String(item.id || stableId(title, link.url)),
    title,
    url: link.url,
    excerpt: String(item.excerpt || '').trim(),
    status,
    domain: String(item.domain || (status === 'INTERNAL_APPROVED' ? '内部知识库' : link.domain)),
    clickable: status !== 'INTERNAL_APPROVED' && link.clickable,
  }
}

function parseLegacy(value) {
  return value
    .replaceAll('\\n', '\n')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !/^参考资料[:：]?/.test(line))
    .map((line) => {
      const markdown = line.match(/^(?:\[?\d+\]?[.)]?\s*)?\[([^\]]+)]\((https?:\/\/[^)]+|\/[^)]+)\)/)
      if (markdown) return { title: markdown[1], url: markdown[2] }
      const plain = line.match(/^(?:\[?\d+\]?[.)]?\s*)?(.+?)\s*\((https?:\/\/[^)]+|\/[^)]+)\)\s*$/)
      if (plain) return { title: plain[1].trim(), url: plain[2] }
      return null
    })
    .filter(Boolean)
}

export function normalizeReferences(value) {
  if (!value) return []
  let items = value
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      items = Array.isArray(parsed) ? parsed : parsed?.references
    } catch {
      items = parseLegacy(value)
    }
  }
  if (!Array.isArray(items)) return []
  return items.map(normalizeItem).filter(Boolean)
}
