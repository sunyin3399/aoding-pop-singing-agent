import { computed, ref } from 'vue'

const STORAGE_KEY = 'aoding-user-id'
export const USER_OPTIONS = Object.freeze(['user-001', 'user-002', 'user-003'])
const DEFAULT_USER_ID = USER_OPTIONS[0]

export function normalizeUserId(value) {
  const candidate = typeof value === 'string' ? value.trim() : ''
  if (candidate === 'demo-user-001') return DEFAULT_USER_ID
  return USER_OPTIONS.includes(candidate) ? candidate : DEFAULT_USER_ID
}

const userId = ref(normalizeUserId(localStorage.getItem(STORAGE_KEY)))
localStorage.setItem(STORAGE_KEY, userId.value)

export function useIdentity() {
  const normalizedId = computed(() => normalizeUserId(userId.value))

  function setUserId(value) {
    userId.value = normalizeUserId(value)
    localStorage.setItem(STORAGE_KEY, userId.value)
  }

  function chatId(mode) {
    return `${normalizedId.value}:${mode}`
  }

  return { userId, normalizedId, setUserId, chatId }
}
