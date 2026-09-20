<script setup>
import { onBeforeUnmount, ref } from 'vue'
import VocalAnalysisReport from '../components/VocalAnalysisReport.vue'
import AudioEvidenceChart from '../components/AudioEvidenceChart.vue'
import { analyzeSpectrogram, scoreAudio, analyzeVocalProduction } from '../api/sse.js'

const status = ref('idle')
const file = ref(null)
const audioUrl = ref('')
const result = ref(null)
const scoreResult = ref(null)
const vocalResult = ref(null)
const errorMessage = ref('')
const abortController = ref(null)
const fileInput = ref(null)
const isDragging = ref(false)
const validationMessage = ref('')

const SUPPORTED_AUDIO_EXTENSIONS = new Set(['wav', 'mp3', 'm4a'])

function abortPendingRequest() {
  abortController.value?.abort()
  abortController.value = null
}

function revokeAudioUrl() {
  if (!audioUrl.value) return
  URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = ''
}

async function selectFile(selectedFile) {
  if (!selectedFile) return
  const extension = selectedFile.name?.split('.').pop()?.toLowerCase()
  if (!SUPPORTED_AUDIO_EXTENSIONS.has(extension)) {
    validationMessage.value = '仅支持 WAV、MP3 或 M4A 音频文件。'
    return
  }

  validationMessage.value = ''
  abortPendingRequest()
  revokeAudioUrl()

  const controller = new AbortController()
  abortController.value = controller
  file.value = selectedFile
  audioUrl.value = URL.createObjectURL(selectedFile)
  result.value = null
  scoreResult.value = null
  vocalResult.value = null
  errorMessage.value = ''
  status.value = 'loading'

  try {
    const [analysis, scoring, vocal] = await Promise.all([
      analyzeSpectrogram(selectedFile, { signal: controller.signal }),
      scoreAudio(selectedFile, { signal: controller.signal }),
      analyzeVocalProduction(selectedFile, { signal: controller.signal }),
    ])
    if (controller.signal.aborted || abortController.value !== controller) return
    result.value = analysis
    scoreResult.value = scoring
    vocalResult.value = vocal
    status.value = 'success'
  } catch (error) {
    if (error?.name === 'AbortError' || abortController.value !== controller) return
    errorMessage.value = error instanceof TypeError
      ? '网络连接失败，请稍后重试。'
      : error?.message || '频谱分析失败，请稍后重试。'
    status.value = 'error'
  } finally {
    if (abortController.value === controller) abortController.value = null
  }
}

function openFilePicker() {
  fileInput.value?.click()
}

function handleFileInput(event) {
  const selectedFile = event.target.files?.[0]
  event.target.value = ''
  if (selectedFile) void selectFile(selectedFile)
}

function handleDrop(event) {
  isDragging.value = false
  const selectedFile = event.dataTransfer?.files?.[0]
  if (selectedFile) void selectFile(selectedFile)
}

function handleDragLeave(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) isDragging.value = false
}

onBeforeUnmount(() => {
  abortPendingRequest()
  revokeAudioUrl()
})
</script>

<template>
  <section class="spectrum-lab">
    <div class="spectrum-lab-inner">
      <a class="spectrum-back" href="/" aria-label="返回首页">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <path d="M19 12H5m6-6-6 6 6 6" />
        </svg>
        返回首页
      </a>
      <header class="spectrum-lab-heading">
        <span>实验功能</span>
        <h1>频谱实验室</h1>
        <p>上传一段人声，查看实时频谱、声音能量分布与按音区的发声控制倾向。</p>
      </header>

      <input
        ref="fileInput"
        class="visually-hidden"
        type="file"
        accept=".wav,.mp3,.m4a"
        aria-label="选择要分析的音频文件"
        @change="handleFileInput"
      />
      <button
        class="spectrum-upload-panel"
        :class="{ 'is-dragging': isDragging }"
        type="button"
        aria-describedby="spectrum-upload-help"
        @click="openFilePicker"
        @dragenter.prevent="isDragging = true"
        @dragover.prevent="isDragging = true"
        @dragleave="handleDragLeave"
        @drop.prevent="handleDrop"
      >
        <span class="spectrum-upload-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4" />
          </svg>
        </span>
        <span class="spectrum-upload-copy">
          <strong>{{ isDragging ? '松开即可开始分析' : '点击选择或拖入音频' }}</strong>
          <small id="spectrum-upload-help">支持 WAV、MP3、M4A，单个文件最大 10 MB、最长 180 秒</small>
        </span>
        <span class="spectrum-upload-action">选择文件</span>
      </button>
      <p v-if="validationMessage" class="spectrum-upload-error" role="alert">{{ validationMessage }}</p>

      <VocalAnalysisReport
        v-if="file && audioUrl"
        class="lab-report"
        :file-name="file.name"
        :audio-src="audioUrl"
        :status="status"
        :error-message="errorMessage"
        :spectrogram="result"
        :vocal="vocalResult"
      />

      <!-- 旧版评分模型（收起，仅频谱实验室展示） -->
      <details v-if="status === 'success' && scoreResult" class="legacy-score-details" aria-labelledby="legacy-score-title">
        <summary>原声学评分模型（旧版）</summary>
        <div class="spectrum-chart-shell legacy-score-panel">
          <header>
            <div><small>原声学评分模型</small><h2 id="legacy-score-title">演唱评分结果 · {{ scoreResult.totalScore }}/100</h2></div>
            <p>{{ scoreResult.grade }}</p>
          </header>
          <div class="audio-dimensions">
            <div v-for="dimension in scoreResult.dimensions" :key="dimension.key">
              <span><b>{{ dimension.label }}</b><strong>{{ dimension.score }}</strong></span>
              <small>{{ dimension.evidence }}</small>
            </div>
          </div>
          <AudioEvidenceChart v-if="scoreResult.analysis" :analysis="scoreResult.analysis" />
          <div class="audio-feedback"><strong>模型建议</strong><ul><li v-for="feedback in scoreResult.feedback" :key="feedback">{{ feedback }}</li></ul></div>
          <p class="audio-scope">{{ scoreResult.scoreScope }}</p>
        </div>
      </details>
    </div>
  </section>
</template>

<style scoped>
.legacy-score-details {
  margin-top: 18px;
  border: 1px solid var(--border, #2a2f36);
  border-radius: 14px;
  background: var(--surface-soft, #1a1d21);
  padding: 14px;
}
.legacy-score-details summary {
  cursor: pointer;
  font-size: 13px;
  color: var(--muted, #9aa0a6);
}
.legacy-score-details .spectrum-chart-shell {
  margin-top: 12px;
  border: none;
  background: transparent;
  padding: 0;
}
.lab-report { margin-top: 18px; }
</style>
