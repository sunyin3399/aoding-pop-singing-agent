import createDOMPurify from 'dompurify'
import { marked } from 'marked'
import { normalizeReferences } from './references.js'

const renderer = new marked.Renderer()
renderer.html = () => ''

marked.use({
  gfm: true,
  breaks: false,
  renderer,
})

function normalizeResourceFieldLabel(value) {
  const label = String(value || '').trim().replace(/\s*[:\uFF1A]?$/, '')
  if (label === '\u4f7f\u7528\u5efa\u8bae') return '\u5efa\u8bae'
  if (['URL', '\u76ee\u7684', '\u5efa\u8bae'].includes(label)) return label
  return null
}

function normalizeResourceMarkdown(source) {
  const value = String(source)
  if (!/^\s*\d+\.\s+\*\*/m.test(value)) return value
  return value.replace(/^\s*-\s+\*\*(URL|目的|建议|使用建议)\*\*\s*([:：])/gm, '   - **$1**$2')
}

function enhanceResourceLists(container, browserWindow) {
  for (const list of container.querySelectorAll('ol')) {
    let enhancedItems = 0
    for (const item of list.querySelectorAll(':scope > li')) {
      const details = item.querySelector(':scope > ul')
      const fields = details ? [...details.querySelectorAll(':scope > li')] : []
      const urlField = fields.find((field) => {
        const label = field.querySelector('strong')?.textContent?.trim() || ''
        return /^URL\s*[:：]?$/.test(label)
      })
      const sourceLink = urlField?.querySelector('a[href]')
      const title = item.querySelector(':scope > strong, :scope > p > strong')
      if (!sourceLink || !title) continue

      const titleLink = browserWindow.document.createElement('a')
      titleLink.href = sourceLink.getAttribute('href')
      titleLink.textContent = title.textContent
      titleLink.className = 'resource-title-link'
      if (/^https?:/i.test(titleLink.href)) {
        titleLink.target = '_blank'
        titleLink.rel = 'noopener noreferrer'
      }
      title.replaceChildren(titleLink)
      urlField.remove()

      item.classList.add('resource-item')
      details.classList.add('resource-details')
      for (const field of fields) {
        if (!details.contains(field)) continue
        const label = field.querySelector('strong')
        const normalizedLabel = normalizeResourceFieldLabel(label?.textContent)
        if (label && normalizedLabel) {
          label.textContent = normalizedLabel
          label.classList.add('resource-field-label')
          const followingText = label.nextSibling
          if (followingText?.nodeType === 3) {
            followingText.textContent = followingText.textContent.replace(/^\s*[:：]\s*/, '')
          }
        }
      }
      enhancedItems++
    }
    if (enhancedItems) list.classList.add('resource-list')
  }
}

function continueFragmentedOrderedLists(container) {
  let nextStart = 1
  for (const element of container.children) {
    if (/^H[1-6]$/.test(element.tagName)) {
      nextStart = 1
      continue
    }
    if (element.tagName !== 'OL' || element.classList.contains('resource-list')) continue
    element.start = nextStart
    nextStart += element.querySelectorAll(':scope > li').length
  }
}

function applyCanonicalReferenceTitles(container, references) {
  const titlesByUrl = new Map(normalizeReferences(references)
    .filter((reference) => reference.clickable && reference.url)
    .map((reference) => [reference.url, reference.title]))
  if (!titlesByUrl.size) return
  for (const link of container.querySelectorAll('a[href]')) {
    const canonicalTitle = titlesByUrl.get(link.href)
    if (!canonicalTitle) continue
    link.textContent = canonicalTitle
    link.dataset.verifiedTitle = 'true'
  }
}

export function renderSafeMarkdown(source, browserWindow = globalThis.window, references = []) {
  if (!source) return ''
  if (!browserWindow?.document) return ''

  const purifier = createDOMPurify(browserWindow)
  const sanitized = purifier.sanitize(marked.parse(normalizeResourceMarkdown(source)), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['img', 'style', 'script', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['style'],
    ALLOWED_URI_REGEXP: /^(?:(?:https?):|(?:[/.?#][^\s]*$))/i,
  })

  const container = browserWindow.document.createElement('div')
  container.innerHTML = sanitized
  for (const link of container.querySelectorAll('a')) {
    const href = link.getAttribute('href') || ''
    if (/^https?:/i.test(href)) {
      link.setAttribute('target', '_blank')
      link.setAttribute('rel', 'noopener noreferrer')
    }
  }
  enhanceResourceLists(container, browserWindow)
  continueFragmentedOrderedLists(container)
  applyCanonicalReferenceTitles(container, references)
  return container.innerHTML
}
