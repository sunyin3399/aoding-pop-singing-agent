export function isNearBottom(element, threshold = 72) {
  if (!element) return true
  return element.scrollHeight - element.scrollTop - element.clientHeight <= threshold
}
