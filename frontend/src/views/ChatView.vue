<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  ChatDotRound,
  ChatLineRound,
  Delete,
  Download,
  Files,
  Headset,
  InfoFilled,
  Microphone,
  UploadFilled,
} from '@element-plus/icons-vue'
import {
  analyzeSpectrogram,
  analyzeVocalProduction,
  cancelStream,
  downloadUrl,
  fetchConversation,
  fetchConversations,
  deleteConversation,
  streamRequest,
} from '../api/sse'
import { useIdentity } from '../composables/useIdentity'
import { isNearBottom } from '../features/chat/scroll'
import VocalAnalysisReport from '../components/VocalAnalysisReport.vue'
import ChatMarkdown from '../components/ChatMarkdown.vue'
import ChatReferences from '../components/ChatReferences.vue'
import TrainingPlanChatCard from '../components/TrainingPlanChatCard.vue'
import { buildTrainingPlanDraft, shouldOpenTrainingPlanForm } from '../features/trainingPlan/trainingPlan'
import { useKnowledgePending } from '../stores/knowledgePending'

const props = defineProps({ mode: { type: String, required: true } })
const router = useRouter()
const { normalizedId } = useIdentity()
const { bump: bumpPending } = useKnowledgePending()
const message = ref('')
const running = ref(false)
const activeRequestId = ref('')
const timeline = ref([])
const conversations = ref([])
const conversationsLoading = ref(false)
const activeConversationId = ref('')
const deleteTarget = ref(null)
let conversationLoadVersion = 0
const steps = ref([])
const fileName = ref('')
const chatBox = ref(null)
const audioInput = ref(null)
const audioProcessing = ref(false)
let controller = null
let followLatest = true
const MAX_VISIBLE_STEPS = 12
const MAX_STEP_CHARS = 320

const isCoach = computed(() => props.mode === 'coach')
const conversationMode = computed(() => isCoach.value ? 'VOCAL_COACH' : 'GENERAL_AGENT')
const canSend = computed(() => Boolean(message.value.trim()) && !running.value)
const latestAssistantId = computed(() => [...timeline.value].reverse()
  .find((item) => item.type === 'assistant-message')?.id)
const meta = computed(() => isCoach.value
  ? {
      badge: '声乐教练',
      title: '定制你的演唱进阶计划',
      intro: '告诉我你的演唱基础、困扰与目标。我会记住这段学习旅程，并给出针对性的训练建议。',
      welcome: '你好，我是奥丁AI声乐教练！我可以帮你解答气息、音准、高音和换声区等常见问题，并把目标整理成容易坚持的练习计划。你可以直接提问，也可以上传一段演唱音频进行分析。',
      placeholder: '例如：我是初学者，高音容易挤卡，想在一个月内唱好《后来》…',
      prompts: ['高音挤卡怎么改善？', '推荐几首适合初学者的演唱歌曲', '为我制定7天气息训练'],
      icon: Microphone,
    }
  : {
      badge: '流行演唱 Agent',
      title: '解决你的流行演唱问题',
      intro: '描述你的流行演唱目标，Agent 会规划步骤、调用工具，并整理成一份可执行的结果。',
      welcome: '你好，欢迎使用流行演唱 Agent！你可以告诉我想唱的歌曲、遇到的演唱问题或需要整理的学习资料，我会帮你查找信息、调用合适的工具，并给出清晰可执行的结果。',
      placeholder: '例如：帮我制定一份 30 天流行演唱入门学习计划，并整理成文档…',
      prompts: ['我想唱陶喆的《普通朋友》，给我一些建议', '有哪些适合初学者的歌曲', '整理一份练声资源清单'],
      icon: ChatDotRound,
    })

async function scrollToBottom(force = false) {
  if (!force && !followLatest) return
  await nextTick()
  if (!force && !followLatest) return
  if (force) followLatest = true
  chatBox.value?.scrollTo({ top: chatBox.value.scrollHeight, behavior: 'auto' })
}

function handleChatScroll() {
  followLatest = isNearBottom(chatBox.value)
}

function sendPrompt(value) {
  if (running.value) return
  if (shouldOpenTrainingPlanForm(value)) {
    openTrainingPlanForm(value)
    return
  }
  message.value = value
  void send()
}

function openTrainingPlanForm(content, draft = {}, answer = null) {
  const item = answer || { id: makeRequestId() }
  Object.assign(item, { type: 'training-plan', role: 'assistant', userMessage: content,
    draft: buildTrainingPlanDraft(content, draft), result: null })
  if (!answer) {
    timeline.value.push({ id: makeRequestId(), type: 'user-message', role: 'user', content })
    timeline.value.push(item)
  }
  message.value = ''
  scrollToBottom(true)
}

function makeRequestId() {
  return `${normalizedId.value}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function newConversation() {
  if (running.value) return
  conversationLoadVersion += 1
  activeConversationId.value = ''
  timeline.value = []
  steps.value = []
  fileName.value = ''
}

async function loadConversationList() {
  const version = ++conversationLoadVersion
  conversationsLoading.value = true
  try {
    const result = await fetchConversations(normalizedId.value, conversationMode.value)
    if (version === conversationLoadVersion) conversations.value = Array.isArray(result) ? result : []
  } catch (error) {
    if (version === conversationLoadVersion) {
      conversations.value = []
      ElMessage.error(error.message || '历史会话加载失败')
    }
  } finally {
    if (version === conversationLoadVersion) conversationsLoading.value = false
  }
}

async function openConversation(item) {
  if (running.value || !item?.conversationId) return
  const version = ++conversationLoadVersion
  activeConversationId.value = item.conversationId
  try {
    const detail = await fetchConversation(item.conversationId, normalizedId.value, conversationMode.value)
    if (version !== conversationLoadVersion) return
    timeline.value = (detail.messages || []).map((entry) => entry.artifactType === 'TRAINING_PLAN'
      ? { id: entry.messageId, type: 'training-plan', role: 'assistant', result: entry.artifactPayload }
      : { id: entry.messageId, type: entry.role === 'USER' ? 'user-message' : 'assistant-message',
          role: entry.role === 'USER' ? 'user' : 'assistant', content: entry.content || '', references: entry.references || [] })
    steps.value = []
    fileName.value = ''
    scrollToBottom(true)
  } catch (error) {
    if (version === conversationLoadVersion) {
      newConversation()
      ElMessage.error(error.message || '历史会话已过期')
      loadConversationList()
    }
  }
}

function formatConversationTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

function formatRemainingDays(seconds) {
  const days = Math.max(1, Math.ceil(Number(seconds || 0) / 86400))
  return `剩余 ${days} 天`
}

function formatConversationTitle(title) {
  const value = String(title || '未命名会话')
  return value.length > 10 ? `${value.slice(0, 10)}…` : value
}

function closeDeleteDialog() {
  deleteTarget.value = null
}

function handleDeleteDialogKeydown(event) {
  if (event.key === 'Escape' && deleteTarget.value) closeDeleteDialog()
}

async function removeConversation(item, event) {
  event.stopPropagation()
  deleteTarget.value = item
}

async function confirmDeleteConversation() {
  const item = deleteTarget.value
  if (!item) return
  closeDeleteDialog()
  try {
    await deleteConversation(item.conversationId, normalizedId.value, conversationMode.value)
    if (activeConversationId.value === item.conversationId) newConversation()
    await loadConversationList()
    ElMessage.success('会话已删除')
  } catch (error) { ElMessage.error(error.message || '删除会话失败，请稍后重试') }
}

onMounted(() => window.addEventListener('keydown', handleDeleteDialogKeydown))
onUnmounted(() => window.removeEventListener('keydown', handleDeleteDialogKeydown))

function appendCompactStep(data) {
  const normalized = String(data || '').replace(/\s+/g, ' ').trim()
  const compact = normalized.length > MAX_STEP_CHARS
    ? `${normalized.slice(0, MAX_STEP_CHARS)}…`
    : normalized
  if (compact) steps.value.push(compact)
  if (steps.value.length > MAX_VISIBLE_STEPS) steps.value.splice(0, steps.value.length - MAX_VISIBLE_STEPS)
}

function chooseAudio() {
  if (!audioProcessing.value) audioInput.value?.click()
}

async function uploadAudio(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['wav', 'mp3', 'm4a'].includes(extension)) {
    ElMessage.warning('仅支持 WAV、MP3、M4A 音频')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('音频文件不能超过 10MB')
    return
  }

  audioProcessing.value = true
  const entry = {
    id: makeRequestId(),
    type: 'audio-analysis',
    status: 'loading',
    fileName: file.name,
    audioSrc: URL.createObjectURL(file),
    spectrogram: null,
    vocal: null,
    errorMessage: '',
  }
  timeline.value.push(entry)
  scrollToBottom(true)

  try {
    const [spectrogram, vocalResponse] = await Promise.all([
      analyzeSpectrogram(file),
      analyzeVocalProduction(file, {
        userId: normalizedId.value,
        mode: conversationMode.value,
        conversationId: activeConversationId.value,
      }),
    ])
    const vocal = vocalResponse.result || vocalResponse
    if (vocalResponse.conversationId) activeConversationId.value = vocalResponse.conversationId
    entry.spectrogram = spectrogram
    entry.vocal = vocal
    entry.status = 'success'
  } catch (error) {
    entry.status = 'error'
    entry.errorMessage = error.message || '音频分析失败'
  } finally {
    audioProcessing.value = false
    scrollToBottom()
  }
}

async function runChat(content, answer) {
  steps.value = []
  fileName.value = ''
  running.value = true
  activeRequestId.value = makeRequestId()
  controller = new AbortController()
  const existingConversationId = activeConversationId.value
  scrollToBottom()

  const path = isCoach.value ? '/ai/music_app/chat/sse' : '/ai/manus/chat'
  const params = {
    message: content,
    userId: normalizedId.value,
    mode: conversationMode.value,
    ...(activeConversationId.value ? { conversationId: activeConversationId.value } : {}),
    requestId: activeRequestId.value,
  }

  try {
    await streamRequest(path, params, {
      signal: controller.signal,
      onEvent({ event, data }) {
        if (event === 'message' || event === 'final') answer.content += data
        else if (event === 'conversation') {
          try {
            const identity = JSON.parse(data)
            if (identity?.conversationId) activeConversationId.value = identity.conversationId
          } catch { /* 服务端仍会继续返回正文 */ }
        }
        else if (event === 'step') appendCompactStep(data)
        else if (event === 'candidateKnowledge') bumpPending()
        else if (event === 'trainingPlanForm') {
          try {
            const payload = JSON.parse(data)
            if (!existingConversationId) activeConversationId.value = ''
            openTrainingPlanForm(content, payload?.data || payload, answer)
          } catch { throw new Error('训练计划表单数据无法解析') }
        }
        else if (event === 'references') answer.references = data
        else if (event === 'fileDownload') fileName.value = data
        else if (event === 'error') throw new Error(data)
        scrollToBottom()
      },
    })
  } catch (error) {
    if (error.name !== 'AbortError') {
      answer.content ||= `连接失败：${error.message}`
      ElMessage.error(error.message || '请求失败，请确认后端服务已启动')
    }
  } finally {
    running.value = false
    controller = null
    await loadConversationList()
  }
}

async function send() {
  const content = message.value.trim()
  if (!content || running.value) return

  if (shouldOpenTrainingPlanForm(content)) {
    openTrainingPlanForm(content)
    return
  }

  message.value = ''
  timeline.value.push({ id: makeRequestId(), type: 'user-message', role: 'user', content })
  const answer = { id: makeRequestId(), type: 'assistant-message', role: 'assistant', content: '' }
  timeline.value.push(answer)

  scrollToBottom(true)
  await runChat(content, answer)
}

async function stop() {
  controller?.abort()
  if (activeRequestId.value) await cancelStream(activeRequestId.value).catch(() => {})
  running.value = false
}

function trainingPlanSaved(item, conversationId, result) {
  item.result = result
  if (conversationId) activeConversationId.value = conversationId
  loadConversationList()
}

watch(
  () => [normalizedId.value, props.mode],
  () => {
    newConversation()
    conversations.value = []
    loadConversationList()
  },
  { immediate: true },
)
</script>

<template>
  <section class="chat-page">
    <aside class="chat-aside">
      <button class="back-button" type="button" @click="router.push('/')"><ArrowLeft /><span>返回首页</span></button>
      <div class="aside-title">
        <span class="aside-icon"><component :is="meta.icon" /></span>
        <div><small>{{ meta.badge }}</small><strong>{{ isCoach ? '流行演唱教学' : '智能任务助手' }}</strong></div>
      </div>
      <div class="session-panel">
        <small>当前记忆空间</small>
        <strong>{{ normalizedId }}</strong>
        <span>会话保存 7 天 · 最多 10 个</span>
      </div>
      <div class="conversation-history" :aria-busy="conversationsLoading">
        <div class="history-heading">
          <small>历史会话</small>
          <button type="button" :disabled="running" @click="newConversation">新建</button>
        </div>
        <button
          class="new-conversation-button"
          :class="{ 'is-active': !activeConversationId }"
          type="button"
          :disabled="running"
          @click="newConversation"
        >
          <ChatLineRound /><span>开始新会话</span>
        </button>
        <div v-if="conversationsLoading" class="history-empty">正在读取记忆…</div>
        <div v-else-if="!conversations.length" class="history-empty">还没有历史会话</div>
        <div v-else class="history-list">
          <div
            v-for="item in conversations"
            :key="item.conversationId"
            :class="{ 'is-active': activeConversationId === item.conversationId }"
            @click="openConversation(item)"
          >
            <button type="button" :disabled="running"><ChatLineRound /><span><strong :title="item.title">{{ formatConversationTitle(item.title) }}</strong><small :class="{ 'is-expiring': item.remainingSeconds <= 86400 }">{{ formatRemainingDays(item.remainingSeconds) }}</small></span></button>
            <button type="button" class="delete-conversation" title="删除会话" aria-label="删除会话" @click="removeConversation(item, $event)"><Delete /></button>
          </div>
        </div>
      </div>
      <div class="aside-tip">
        <InfoFilled />
        <p>历史保存完整问答和参考资料，不保存 Agent 工具轨迹。进入页面时默认开启空白新会话。</p>
      </div>
    </aside>

    <div class="chat-main">
      <div ref="chatBox" class="conversation" @scroll="handleChatScroll">
        <div v-if="!timeline.length" class="chat-welcome">
          <div class="welcome-orb"><component :is="meta.icon" /></div>
          <span>{{ meta.badge }}</span>
          <h1>{{ meta.title }}</h1>
          <p>{{ meta.intro }}</p>
          <div class="welcome-tutorial">
            <span class="message-avatar"><component :is="meta.icon" /></span>
            <div>
              <small>{{ meta.badge }}</small>
              <p>{{ meta.welcome }}</p>
            </div>
          </div>
        </div>

	        <div v-else class="message-list">
	          <template v-for="item in timeline" :key="item.id">
          <div v-if="item.type === 'user-message' || item.type === 'assistant-message'" class="message-row" :class="item.role">
            <div class="message-avatar">
              <span v-if="item.role === 'user'">{{ normalizedId.slice(0, 1).toUpperCase() }}</span>
              <component :is="meta.icon" v-else />
            </div>
	            <div class="message-body">
	              <small>{{ item.role === 'user' ? normalizedId : meta.badge }}</small>
	              <details
	                v-if="item.role === 'assistant' && item.id === latestAssistantId && steps.length"
	                class="steps-card"
	                :open="running"
	                :aria-busy="running"
	              >
	                <summary class="steps-heading"><Headset /><strong>Agent 执行轨迹</strong><small>{{ steps.length }} 步</small><ArrowDown /></summary>
	                <ol><li v-for="(step, index) in steps" :key="index">{{ step }}</li></ol>
	              </details>
	              <div class="message-bubble">
                <span v-if="!item.content && running" class="typing"><i></i><i></i><i></i></span>
                <template v-else>
                  <span v-if="item.role === 'user'">{{ item.content }}</span>
                  <ChatMarkdown v-else :content="item.content" :references="item.references" />
	          </template>
              </div>
              <ChatReferences v-if="item.references" :references="item.references" />
            </div>
          </div>

          <VocalAnalysisReport
            v-else-if="item.type === 'audio-analysis'"
            :file-name="item.fileName"
            :audio-src="item.audioSrc"
            :status="item.status"
            :error-message="item.errorMessage"
            :spectrogram="item.spectrogram"
            :vocal="item.vocal"
          />
          <TrainingPlanChatCard
            v-else-if="item.type === 'training-plan'"
            :item="item"
            :user-id="normalizedId"
            :mode="conversationMode"
            :conversation-id="activeConversationId"
            @saved="(conversationId, result) => trainingPlanSaved(item, conversationId, result)"
          />
          </template>

	          <a v-if="fileName" class="download-card" :href="downloadUrl(fileName)" target="_blank">
            <span>PDF</span><div><strong>{{ fileName }}</strong><small>下载 Agent 生成的计划文档</small></div><Download />
	          </a>

        </div>
        <div v-if="!timeline.length" class="quick-prompts conversation-prompts" aria-label="快捷问题">
          <button v-for="prompt in meta.prompts" :key="prompt" type="button" :disabled="running" @click="sendPrompt(prompt)">
            {{ prompt }} <ArrowRight />
          </button>
        </div>
      </div>

      <div class="composer-wrap">
        <div class="composer">
          <textarea v-model="message" :placeholder="meta.placeholder" rows="2" :disabled="running" @keydown.enter.exact.prevent="send"></textarea>
          <div class="composer-footer">
            <div class="composer-tools">
              <template v-if="isCoach">
                <input ref="audioInput" class="visually-hidden" type="file" accept=".wav,.mp3,.m4a,audio/wav,audio/mpeg,audio/mp4" @change="uploadAudio" />
                <button class="audio-upload-button" type="button" :disabled="audioProcessing" @click="chooseAudio">
                  <UploadFilled />{{ audioProcessing ? '正在分析音频' : '上传演唱音频' }}
                </button>
                <small>WAV / MP3 / M4A · 最大 10MB</small>
              </template>
              <span v-else>Enter 发送 · Shift + Enter 换行</span>
            </div>
            <button v-if="running" class="stop-button" type="button" @click="stop">停止</button>
            <button v-else class="send-button" type="button" :disabled="!canSend" aria-label="发送消息" @click="send"><ArrowRight /></button>
          </div>
        </div>
        <p>AI 生成内容仅供学习参考，请根据自身嗓音状态适度练习。</p>
      </div>
    </div>
  </section>
  <div v-if="deleteTarget" class="delete-dialog-backdrop" @click.self="closeDeleteDialog">
    <section class="delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-dialog-title" aria-describedby="delete-dialog-description">
      <div class="delete-dialog-icon" aria-hidden="true"><Delete /></div>
      <div class="delete-dialog-copy">
        <span class="delete-dialog-eyebrow">历史会话</span>
        <h2 id="delete-dialog-title">删除这段会话？</h2>
        <p id="delete-dialog-description">“{{ formatConversationTitle(deleteTarget.title) }}”将从历史记录中永久移除，删除后无法恢复。</p>
      </div>
      <div class="delete-dialog-actions">
        <button type="button" class="delete-dialog-cancel" @click="closeDeleteDialog">取消</button>
        <button type="button" class="delete-dialog-confirm" @click="confirmDeleteConversation"><Delete />删除会话</button>
      </div>
    </section>
  </div>
</template>
