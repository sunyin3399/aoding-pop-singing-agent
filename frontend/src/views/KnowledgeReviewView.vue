<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, CircleCheck, CircleClose, Link, Refresh } from '@element-plus/icons-vue'
import {
  approveKnowledgeCandidate,
  getKnowledgeCandidate,
  listKnowledgeCandidates,
  rejectKnowledgeCandidate,
} from '../api/knowledgeReview'
import { useKnowledgePending } from '../stores/knowledgePending'

const router = useRouter()
const { refresh: refreshPendingBadge } = useKnowledgePending()
const statusFilter = ref('PENDING')
const candidates = ref([])
const selectedId = ref('')
const candidate = ref(null)
const page = ref(0)
const total = ref(0)
const totalPages = ref(0)
const loadingList = ref(false)
const loadingDetail = ref(false)
const reviewing = ref(false)
const error = ref('')
const reviewNote = ref('')

const canReview = computed(() => candidate.value?.status === 'PENDING' && !reviewing.value)
const statusLabels = { PENDING: '待审核', APPROVED: '已批准', REJECTED: '已拒绝' }

async function loadList({ keepSelection = false, preserveDetail = false } = {}) {
  loadingList.value = true
  error.value = ''
  try {
    const result = await listKnowledgeCandidates({ status: statusFilter.value || undefined, page: page.value, size: 20 })
    candidates.value = result.items || []
    total.value = result.total || 0
    totalPages.value = result.totalPages || 0
    if (preserveDetail) return
    const nextId = keepSelection && candidates.value.some((item) => item.id === selectedId.value)
      ? selectedId.value
      : candidates.value[0]?.id || ''
    if (nextId) await selectCandidate(nextId)
    else { selectedId.value = ''; candidate.value = null }
  } catch (requestError) {
    error.value = requestError.message || '候选知识列表加载失败。'
  } finally {
    loadingList.value = false
  }
}

async function selectCandidate(id) {
  selectedId.value = id
  loadingDetail.value = true
  error.value = ''
  try {
    candidate.value = await getKnowledgeCandidate(id)
    reviewNote.value = candidate.value.reviewNote || ''
  } catch (requestError) {
    error.value = requestError.message || '候选知识详情加载失败。'
  } finally {
    loadingDetail.value = false
  }
}

async function review(action) {
  if (!canReview.value) return
  try {
    await ElMessageBox.confirm(
      action === 'approve'
        ? '批准后将立即写入正式 RAG 知识库。确认继续？'
        : '拒绝后该候选不会写入知识库。确认继续？',
      action === 'approve' ? '确认批准' : '确认拒绝',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: action === 'approve' ? 'warning' : 'error' },
    )
  } catch {
    return
  }
  reviewing.value = true
  error.value = ''
  try {
    candidate.value = action === 'approve'
      ? await approveKnowledgeCandidate(candidate.value.id, reviewNote.value)
      : await rejectKnowledgeCandidate(candidate.value.id, reviewNote.value)
    await loadList({ preserveDetail: true })
    refreshPendingBadge()
  } catch (requestError) {
    error.value = requestError.message || '审核操作失败，候选状态未改变。'
  } finally {
    reviewing.value = false
  }
}

const approveSelected = () => review('approve')
const rejectSelected = () => review('reject')

function changePage(nextPage) {
  page.value = nextPage
  loadList()
}

watch(statusFilter, () => { page.value = 0; loadList() })
onMounted(() => { loadList(); refreshPendingBadge() })
</script>

<template>
  <section class="review-page">
    <div class="review-shell">
      <button class="review-back" type="button" @click="router.push('/')"><ArrowLeft />返回首页</button>
      <header class="review-heading">
        <div>
          <h1>候选知识审核</h1>
          <p>外部资料只有批准并成功写入向量库后，才会成为正式 RAG 知识。</p>
        </div>
        <button type="button" class="refresh-button" :disabled="loadingList" @click="loadList({ keepSelection: true })"><Refresh />刷新列表</button>
      </header>

      <p v-if="error" class="review-error" role="alert">{{ error }}</p>

      <div class="review-layout">
        <aside class="candidate-panel" aria-label="候选知识列表">
          <div class="candidate-toolbar">
            <label><span>状态筛选</span>
              <select v-model="statusFilter">
                <option value="">全部状态</option>
                <option value="PENDING">待审核</option>
                <option value="APPROVED">已批准</option>
                <option value="REJECTED">已拒绝</option>
              </select>
            </label>
            <span>共 {{ total }} 条</span>
          </div>

          <div v-if="loadingList" class="candidate-loading" aria-label="正在加载候选知识"><span /><span /><span /></div>
          <div v-else-if="!candidates.length" class="candidate-empty">当前筛选条件下没有候选知识。</div>
          <nav v-else class="candidate-list" aria-label="审核候选">
            <button v-for="item in candidates" :key="item.id" type="button" :class="{ active: item.id === selectedId }" @click="selectCandidate(item.id)">
              <span class="status-label" :class="item.status.toLowerCase()">{{ statusLabels[item.status] }}</span>
              <strong>{{ item.title }}</strong>
              <small>{{ item.originalQuery }}</small>
              <time>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time>
            </button>
          </nav>

          <div v-if="totalPages > 1" class="candidate-pagination">
            <button type="button" :disabled="page === 0" @click="changePage(page - 1)">上一页</button>
            <span>{{ page + 1 }} / {{ totalPages }}</span>
            <button type="button" :disabled="page + 1 >= totalPages" @click="changePage(page + 1)">下一页</button>
          </div>
        </aside>

        <main class="candidate-detail">
          <div v-if="loadingDetail" class="detail-loading" aria-label="正在加载详情"><span /><span /><span /></div>
          <div v-else-if="!candidate" class="detail-empty">从左侧选择一条候选知识开始审核。</div>
          <article v-else>
            <header class="detail-heading">
              <div>
                <span class="status-label" :class="candidate.status.toLowerCase()">{{ statusLabels[candidate.status] }}</span>
                <h2>{{ candidate.title }}</h2>
                <p>原始问题：{{ candidate.originalQuery }}</p>
              </div>
              <dl>
                <div><dt>创建时间</dt><dd>{{ new Date(candidate.createdAt).toLocaleString('zh-CN') }}</dd></div>
                <div><dt>内容哈希</dt><dd>{{ candidate.contentHash }}</dd></div>
              </dl>
            </header>

            <section class="source-section">
              <h3>外部来源</h3>
              <div class="source-links">
                <a v-for="source in candidate.sources" :key="source.url" :href="source.url" target="_blank" rel="noopener noreferrer"><Link />{{ source.title }}</a>
              </div>
            </section>

            <section class="markdown-section">
              <h3>Markdown 预览</h3>
              <pre>{{ candidate.markdownContent }}</pre>
            </section>

            <section v-if="candidate.publishedDocumentIds?.length" class="published-section">
              <h3>正式知识文档</h3>
              <a v-for="documentId in candidate.publishedDocumentIds" :key="documentId" :href="`/api/ai/knowledge/documents/${documentId}`" target="_blank" rel="noopener noreferrer">查看文档 {{ documentId }}</a>
            </section>

            <section class="review-actions">
              <label><span>审核备注</span><textarea v-model="reviewNote" rows="3" :disabled="candidate.status !== 'PENDING'" placeholder="记录来源核验结果、修改建议或拒绝原因" /></label>
              <div v-if="candidate.status === 'PENDING'" class="action-buttons">
                <button type="button" class="reject-button" :disabled="!canReview" @click="rejectSelected"><CircleClose />拒绝</button>
                <button type="button" class="approve-button" :disabled="!canReview" @click="approveSelected"><CircleCheck />{{ reviewing ? '处理中...' : '批准并发布' }}</button>
              </div>
              <p v-else>审核已完成{{ candidate.reviewedAt ? `，时间：${new Date(candidate.reviewedAt).toLocaleString('zh-CN')}` : '' }}。</p>
            </section>
          </article>
        </main>
      </div>
    </div>
  </section>
</template>

<style scoped>
.review-page { min-height: calc(100dvh - 70px); padding: 28px clamp(18px, 4vw, 58px) 50px; background: var(--page); }
.review-shell { width: min(1380px, 100%); margin: 0 auto; }
.review-back, .refresh-button, .candidate-pagination button { min-height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; cursor: pointer; }
.review-back { padding: 0; border: 0; background: none; color: var(--muted); font-size: 13px; }
.review-heading { display: flex; align-items: end; justify-content: space-between; gap: 20px; padding: 18px 0 22px; }
.review-heading h1 { margin: 0; font-size: clamp(28px, 3vw, 40px); letter-spacing: -1.4px; }
.review-heading p { margin: 7px 0 0; color: var(--muted); font-size: 14px; line-height: 1.6; }
.refresh-button, .candidate-pagination button { padding: 0 14px; border: 1px solid var(--line-strong); border-radius: 10px; background: var(--surface); font-size: 13px; }
.refresh-button:hover:not(:disabled), .candidate-pagination button:hover:not(:disabled) { border-color: var(--accent); color: var(--accent-dark); }
.review-error { margin: 0 0 14px; padding: 12px 14px; border-radius: 10px; background: #fff0ed; color: #9d2f23; font-size: 13px; }
.review-layout { display: grid; grid-template-columns: 340px minmax(0, 1fr); gap: 16px; align-items: start; }
.candidate-panel, .candidate-detail { border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); }
.candidate-panel { position: sticky; top: 86px; overflow: hidden; }
.candidate-toolbar { display: flex; align-items: end; justify-content: space-between; gap: 12px; padding: 16px; border-bottom: 1px solid var(--line); }
.candidate-toolbar label { display: grid; gap: 6px; color: var(--ink); font-size: 12px; font-weight: 700; }
.candidate-toolbar select { min-width: 150px; height: 40px; padding: 0 10px; border: 1px solid var(--line-strong); border-radius: 9px; background: #fff; color: var(--ink); }
.candidate-toolbar > span { color: var(--muted); font-size: 12px; }
.candidate-list { max-height: calc(100dvh - 270px); overflow-y: auto; padding: 7px; }
.candidate-list button { width: 100%; min-height: 118px; display: grid; grid-template-columns: 1fr auto; gap: 5px 10px; padding: 14px; border: 1px solid transparent; border-radius: 11px; background: transparent; color: var(--ink); cursor: pointer; text-align: left; transition: background .2s ease, border-color .2s ease; }
.candidate-list button:hover { background: var(--surface-soft); }
.candidate-list button.active { border-color: #e7a48f; background: #fff7f3; }
.candidate-list strong, .candidate-list small, .candidate-list time { grid-column: 1 / -1; }
.candidate-list strong { font-size: 14px; }
.candidate-list small, .candidate-list time { overflow: hidden; color: var(--muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.status-label { width: max-content; padding: 4px 8px; border-radius: 8px; font-size: 12px; font-weight: 700; }
.status-label.pending { background: #fff3d8; color: #795312; }
.status-label.approved { background: #e4f3e9; color: #285d3d; }
.status-label.rejected { background: #fbe9e6; color: #923b30; }
.candidate-pagination { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 12px 14px; border-top: 1px solid var(--line); font-size: 12px; }
.candidate-pagination button:disabled, .refresh-button:disabled { cursor: not-allowed; opacity: .55; }
.candidate-detail { min-height: 680px; padding: 24px; }
.detail-empty, .candidate-empty { display: grid; place-items: center; min-height: 260px; padding: 24px; color: var(--muted); font-size: 13px; text-align: center; }
.detail-heading { display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, .52fr); gap: 24px; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
.detail-heading h2 { margin: 9px 0 7px; font-size: 24px; letter-spacing: -.6px; }
.detail-heading p { margin: 0; color: var(--muted); font-size: 13px; }
.detail-heading dl { margin: 0; display: grid; gap: 10px; }
.detail-heading dl div { min-width: 0; padding: 10px 12px; border-radius: 9px; background: var(--surface-soft); }
.detail-heading dt { color: var(--muted); font-size: 12px; }
.detail-heading dd { margin: 4px 0 0; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.candidate-detail section { margin-top: 22px; }
.candidate-detail h3 { margin: 0 0 10px; font-size: 15px; }
.source-links, .published-section { display: flex; flex-wrap: wrap; gap: 9px; }
.source-links a, .published-section a { min-height: 44px; display: inline-flex; align-items: center; gap: 7px; padding: 0 12px; border: 1px solid var(--line); border-radius: 9px; color: var(--accent-dark); font-size: 12px; text-decoration: none; }
.source-links a:hover, .published-section a:hover { border-color: var(--accent); text-decoration: underline; text-underline-offset: 3px; }
.markdown-section pre { max-height: 540px; margin: 0; padding: 18px; overflow: auto; border: 1px solid var(--line); border-radius: 11px; background: #f6f9f7; color: var(--ink); font: 13px/1.75 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.review-actions { padding-top: 20px; border-top: 1px solid var(--line); }
.review-actions label { display: grid; gap: 8px; font-size: 13px; font-weight: 700; }
.review-actions textarea { width: 100%; padding: 11px 12px; border: 1px solid var(--line-strong); border-radius: 10px; background: #fff; color: var(--ink); resize: vertical; line-height: 1.6; }
.review-actions textarea:focus { border-color: var(--accent); outline: 3px solid rgba(217, 95, 59, .15); }
.action-buttons { display: flex; justify-content: flex-end; gap: 10px; margin-top: 13px; }
.action-buttons button { min-width: 110px; min-height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border-radius: 10px; cursor: pointer; font-weight: 700; }
.reject-button { border: 1px solid #d9978e; background: #fff; color: #8c342a; }
.approve-button { border: 1px solid var(--accent); background: var(--accent); color: #fffaf7; }
.action-buttons button:disabled { cursor: wait; opacity: .6; }
.review-actions > p { color: var(--muted); font-size: 13px; }
.candidate-loading, .detail-loading { display: grid; gap: 12px; padding: 16px; }
.candidate-loading span, .detail-loading span { height: 92px; border-radius: 10px; background: #eef3f0; }
.detail-loading span:first-child { height: 140px; }
@media (max-width: 900px) { .review-layout { grid-template-columns: 1fr; } .candidate-panel { position: static; } .candidate-list { max-height: 380px; } .detail-heading { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .review-page { padding-inline: 14px; } .review-heading { align-items: stretch; flex-direction: column; } .refresh-button { width: 100%; } .candidate-detail { padding: 16px; } .candidate-toolbar select, .review-actions textarea { font-size: 16px; } .action-buttons { flex-direction: column-reverse; } .action-buttons button { width: 100%; } }
</style>
