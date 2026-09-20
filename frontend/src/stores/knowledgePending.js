import { ref } from 'vue'
import { listKnowledgeCandidates } from '../api/knowledgeReview'

// 模块级单例：App 顶栏按钮的气泡、ChatView 收到 candidateKnowledge 事件、审核页的刷新
// 都读写同一份待审计数，跨页面保持同步。
const pendingCount = ref(0)
let initialized = false

export function useKnowledgePending() {
  async function refresh() {
    try {
      const result = await listKnowledgeCandidates({ status: 'PENDING', page: 0, size: 1 })
      pendingCount.value = result?.total || 0
    } catch {
      // 拉取失败时保留当前值，不打断用户操作。
    }
  }

  // App 首次加载时从服务端同步一次，避免只显示本次会话新增的候选。
  function ensureInitialized() {
    if (!initialized) {
      initialized = true
      refresh()
    }
  }

  // 收到新的 PENDING_REVIEW 候选知识 SSE 事件时 +1。
  function bump() {
    pendingCount.value += 1
  }

  return { pendingCount, refresh, ensureInitialized, bump }
}
