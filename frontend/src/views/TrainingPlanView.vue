<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Document, RefreshRight } from '@element-plus/icons-vue'
import { createTrainingPlan, exportTrainingPlanPdf } from '../api/trainingPlan'
import { useIdentity } from '../composables/useIdentity'
import {
  buildTrainingPlanRequest,
  exerciseCategoryLabels,
  flattenTrainingDays, mergeTrainingDays,
  getTrainingPlanReferences,
  validateTrainingPlanForm,
} from '../features/trainingPlan/trainingPlan'

const router = useRouter()
const { normalizedId } = useIdentity()
const form = reactive({
  goal: '',
  level: 'BEGINNER',
  durationDays: 7,
  minutesPerDay: 30,
  currentProblems: '',
  comfortableRange: '',
  hasPainOrHoarseness: false,
})
const errors = reactive({})
const loading = ref(false)
const requestError = ref('')
const result = ref(null)
const exportingPdf = ref(false)

const days = computed(() => mergeTrainingDays(flattenTrainingDays(result.value)))
const references = computed(() => getTrainingPlanReferences(result.value))
function displayNotice(notice) {
  return String(notice || '').replace(/BEGINNER/g, '入门').replace(/INTERMEDIATE/g, '进阶').replace(/ADVANCED/g, '高级')
}

function replaceErrors(nextErrors) {
  Object.keys(errors).forEach((key) => delete errors[key])
  Object.assign(errors, nextErrors)
}

async function submit() {
  replaceErrors(validateTrainingPlanForm(form))
  if (Object.keys(errors).length) return
  loading.value = true
  requestError.value = ''
  result.value = null
  try {
    result.value = await createTrainingPlan(buildTrainingPlanRequest(form, normalizedId.value))
  } catch (error) {
    requestError.value = error.message || '暂时无法生成训练计划，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function resetResult() {
  result.value = null
  requestError.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function downloadPdf() {
  exportingPdf.value = true
  requestError.value = ''
  try {
    const artifact = await exportTrainingPlanPdf(result.value.plan)
    const link = document.createElement('a')
    link.href = artifact.downloadUrl
    link.download = artifact.fileName
    link.click()
  } catch (error) {
    requestError.value = error.message || 'PDF 导出失败，请稍后重试。'
  } finally {
    exportingPdf.value = false
  }
}
</script>

<template>
  <section class="plan-page">
    <div class="plan-shell">
      <button type="button" class="plan-back" @click="router.push('/')"><ArrowLeft />返回首页</button>

      <header class="plan-heading">
        <div>
          <span>专属练声计划</span>
          <h1>把目标一次说清楚，直接开始练。</h1>
          <p>填写训练目标和当前问题。系统会结合知识库生成阶段目标与每日训练项。</p>
        </div>
        <img src="/assets/odin-cat-logo.png" alt="奥丁 AI 猫咪助手" />
      </header>

      <div class="plan-layout">
        <form class="plan-form" novalidate @submit.prevent="submit">
          <div class="form-intro">
            <h2>训练信息</h2>
            <p>带 * 的字段用于判断计划约束，请一次填写完整。</p>
          </div>

          <label class="field field-wide">
            <span>训练目标 <b class="required-mark">*</b></span>
            <input v-model="form.goal" list="training-goal-options" :aria-invalid="Boolean(errors.goal)" placeholder="可自由输入，或选择常见训练目标" />
            <datalist id="training-goal-options">
              <option value="声带闭合训练" />
              <option value="气息支撑与稳定性" />
              <option value="高音稳定与音域拓展" />
              <option value="换声区衔接" />
              <option value="节奏感与演唱表现力" />
              <option value="减少喉部紧张与挤压" />
            </datalist>
            <small v-if="errors.goal" class="field-error">{{ errors.goal }}</small>
          </label>

          <label class="field">
            <span>当前水平 <b class="required-mark">*</b></span>
            <select v-model="form.level">
              <option value="BEGINNER">入门</option>
              <option value="INTERMEDIATE">进阶</option>
              <option value="ADVANCED">高级</option>
            </select>
          </label>

          <label class="field">
          </label>

          <label class="field">
            <span>训练周期 <b class="required-mark">*</b></span>
            <span class="input-with-unit"><input v-model.number="form.durationDays" type="number" min="1" max="30" :aria-invalid="Boolean(errors.durationDays)" /><em>天</em></span>
            <small v-if="errors.durationDays" class="field-error">{{ errors.durationDays }}</small>
          </label>

          <label class="field">
            <span>每日时长 <b class="required-mark">*</b></span>
            <span class="input-with-unit"><input v-model.number="form.minutesPerDay" type="number" min="5" max="120" :aria-invalid="Boolean(errors.minutesPerDay)" /><em>分钟</em></span>
            <small v-if="errors.minutesPerDay" class="field-error">{{ errors.minutesPerDay }}</small>
          </label>

          <label class="field field-wide">
            <span>当前问题 <b class="required-mark">*</b></span>
            <textarea v-model="form.currentProblems" rows="4" :aria-invalid="Boolean(errors.currentProblems)" placeholder="每行填写一项，例如：高音挤卡、换气不稳定、咬字含混" />
            <small class="field-help">可用换行或逗号分隔多个问题。</small>
            <small v-if="errors.currentProblems" class="field-error">{{ errors.currentProblems }}</small>
          </label>

          <label class="field">
            <span>舒适音域</span>
            <input v-model="form.comfortableRange" placeholder="可选，例如：C3-G4" />
          </label>

          <label class="risk-field">
            <input v-model="form.hasPainOrHoarseness" type="checkbox" />
            <span><strong>目前有疼痛或持续嘶哑</strong>勾选后不会生成训练动作，会先给出安全提示。</span>
          </label>

          <p v-if="requestError" class="request-error" role="alert">{{ requestError }}</p>
          <button class="submit-plan" type="submit" :disabled="loading">
            {{ loading ? '正在检索并生成计划...' : '生成训练计划' }}
          </button>
        </form>

        <aside class="plan-result" aria-live="polite">
          <div v-if="loading" class="result-loading" aria-label="正在生成训练计划">
            <span v-for="index in 4" :key="index" />
          </div>

          <div v-else-if="!result" class="result-empty">
            <Document />
            <h2>计划会显示在这里</h2>
            <p>包括阶段目标、每日热身、核心训练、放松动作和停止条件。</p>
          </div>

          <div v-else-if="result.status === 'NEEDS_CLARIFICATION'" class="result-message">
            <h2>还缺少必要信息</h2>
            <ul><li v-for="question in result.questions" :key="question.field">{{ question.question }}</li></ul>
            <button type="button" @click="resetResult"><RefreshRight />返回补充</button>
          </div>

          <div v-else-if="result.status === 'SAFETY_BLOCKED'" class="result-message safety-message">
            <h2>请先暂停发声训练</h2>
            <p>你填写了疼痛或持续嘶哑。建议先休息并咨询耳鼻喉科或专业嗓音治疗人员，症状解除后再制定计划。</p>
          </div>

          <div v-else-if="result.status === 'VALIDATION_FAILED' || result.status === 'FAILED'" class="result-message">
            <h2>这次计划未通过校验</h2>
            <p>系统没有返回不完整计划。你可以保持当前信息并重新生成。</p>
            <ul><li v-for="issue in result.issues" :key="`${issue.code}-${issue.path}`">{{ issue.message }}</li></ul>
            <button type="button" @click="submit"><RefreshRight />重新生成</button>
          </div>

          <article v-else class="result-content">
            <header>
              <span>{{ result.plan.durationDays }} 天，每天 {{ result.plan.minutesPerDay }} 分钟</span>
              <h2>{{ result.plan.title }}</h2>
              <p>{{ result.plan.goal }}</p>
              <button class="pdf-button" type="button" :disabled="exportingPdf" @click="downloadPdf">
                {{ exportingPdf ? '正在导出...' : '导出 PDF' }}
              </button>
            </header>

            <section class="phase-list">
              <div v-for="phase in result.plan.phases" :key="`${phase.startDay}-${phase.name}`" class="phase-item">
                <strong>{{ phase.name }}</strong>
                <span>第 {{ phase.startDay }}-{{ phase.endDay }} 天</span>
                <p>{{ phase.goal }}</p>
                <ul><li v-for="criterion in phase.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ul>
              </div>
            </section>

            <section v-if="result.plan.phases?.some((phase) => phase.exercises?.length)" class="phase-exercise-list">
              <article v-for="phase in result.plan.phases" :key="`exercise-${phase.name}`"><h3>{{ phase.name }}</h3><div v-for="exercise in phase.exercises" :key="`${phase.name}-${exercise.name}`" class="exercise-item"><div><span>{{ exerciseCategoryLabels[exercise.category] || exercise.category }}</span><strong>{{ exercise.name }}</strong><small>{{ exercise.minutes }} 分钟 · {{ exercise.sets }} 组</small></div><ol><li v-for="instruction in exercise.instructions" :key="instruction">{{ instruction }}</li></ol><p v-if="exercise.stopConditions?.length">停止条件：{{ exercise.stopConditions.join('；') }}</p></div></article>
            </section>
            <section v-if="result.plan.days?.length" class="day-list">
              <details v-for="day in days" :key="day.startDay" name="training-days" :open="day.startDay === 1" class="day-item">
                <summary><span>第 {{ day.startDay }}{{ day.endDay > day.startDay ? `-${day.endDay}` : '' }} 天</span><strong>{{ day.goal }}</strong><small>{{ day.phaseName }}</small></summary>
                <div class="exercise-list">
                  <div v-for="exercise in day.exercises" :key="`${day.day}-${exercise.category}-${exercise.name}`" class="exercise-item">
                    <div><span>{{ exerciseCategoryLabels[exercise.category] || exercise.category }}</span><strong>{{ exercise.name }}</strong><small>{{ exercise.minutes }} 分钟，{{ exercise.sets }} 组</small></div>
                    <ol><li v-for="instruction in exercise.instructions" :key="instruction">{{ instruction }}</li></ol>
                    <p v-if="exercise.stopConditions?.length">停止条件：{{ exercise.stopConditions.join('；') }}</p>
                  </div>
                </div>
                <p v-if="day.checkpoint" class="checkpoint">当日检查：{{ day.checkpoint }}</p>
              </details>
            </section>

            <section v-if="result.plan.safetyNotices?.length" class="safety-notices">
              <h3>安全提示</h3>
              <ul><li v-for="notice in result.plan.safetyNotices" :key="notice">{{ displayNotice(notice) }}</li></ul>
            </section>

            <footer v-if="references.length" class="plan-references">
              <strong>参考资料（内部知识库）:</strong>
              <span v-for="reference in references" :key="reference.documentId">{{ reference.title }}</span>
            </footer>
          </article>
        </aside>
      </div>
    </div>
  </section>
</template>

<style scoped>
.plan-page { min-height: calc(100dvh - 70px); padding: 28px clamp(18px, 4vw, 58px) 56px; background: var(--page); }
.plan-shell { width: min(1320px, 100%); margin: 0 auto; }
.plan-back { display: inline-flex; align-items: center; gap: 7px; padding: 0; border: 0; background: none; color: var(--muted); cursor: pointer; font-size: 12px; }
.plan-back svg { width: 15px; }
.plan-heading { min-height: 188px; display: grid; grid-template-columns: minmax(0, 1fr) 160px; align-items: center; gap: 32px; padding: 24px 10px 26px; }
.plan-heading > div { max-width: 720px; }
.plan-heading span { color: var(--accent-dark); font-size: 12px; font-weight: 700; }
.plan-heading h1 { margin: 9px 0 12px; color: var(--ink); font-size: clamp(30px, 4vw, 48px); line-height: 1.18; letter-spacing: -2px; }
.plan-heading p { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.7; }
.plan-heading img { width: 150px; height: 150px; object-fit: contain; }
.plan-layout { display: grid; grid-template-columns: minmax(340px, .72fr) minmax(520px, 1.28fr); gap: 18px; align-items: start; }
.plan-form, .plan-result { border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); }
.plan-form { position: sticky; top: 88px; display: grid; grid-template-columns: 1fr 1fr; gap: 18px 14px; padding: 24px; }
.form-intro, .field-wide, .request-error, .submit-plan { grid-column: 1 / -1; }
.form-intro h2 { margin: 0; font-size: 20px; }
.form-intro p { margin: 6px 0 0; color: var(--muted); font-size: 11px; }
.field { display: flex; flex-direction: column; gap: 7px; min-width: 0; }
.field > span { color: var(--ink); font-size: 12px; font-weight: 650; }
.required-mark { color: #b53e2f; }
.input-with-unit { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 10px; background: #fff; }
.input-with-unit input { border: 0; border-radius: 0; }
.input-with-unit em { padding: 0 12px; color: var(--muted); font-size: 11px; font-style: normal; }
.field input, .field select, .field textarea { width: 100%; border: 1px solid var(--line-strong); border-radius: 10px; background: #fff; color: var(--ink); font-size: 13px; outline: none; transition: border-color .2s ease, box-shadow .2s ease; }
.field input, .field select { height: 42px; padding: 0 12px; }
.field textarea { padding: 11px 12px; resize: vertical; line-height: 1.6; }
.field input::placeholder, .field textarea::placeholder { color: #757f7b; }
.field input:focus, .field select:focus, .field textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(217, 95, 59, .14); }
.field [aria-invalid='true'] { border-color: #b53e2f; }
.field-help { color: var(--muted); font-size: 10px; }
.field-error { color: #a72e22; font-size: 10px; }
.risk-field { grid-column: 1 / -1; display: flex; gap: 10px; align-items: flex-start; padding: 13px; border-radius: 10px; background: #fff6f1; color: #704737; font-size: 11px; line-height: 1.55; }
.risk-field input { margin-top: 3px; accent-color: var(--accent); }
.risk-field strong { display: block; color: #7f3023; }
.request-error { margin: 0; padding: 10px 12px; border-radius: 10px; background: #fff0ed; color: #9d2f23; font-size: 12px; }
.submit-plan { min-height: 46px; border: 0; border-radius: 11px; background: var(--accent); color: #fffaf7; cursor: pointer; font-weight: 700; transition: background .2s ease, transform .2s ease; }
.submit-plan:hover:not(:disabled) { background: var(--accent-dark); transform: translateY(-1px); }
.submit-plan:active:not(:disabled) { transform: scale(.99); }
.submit-plan:disabled { cursor: wait; opacity: .72; }
.plan-result { min-height: 620px; padding: 28px; }
.result-empty, .result-message { min-height: 560px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.result-empty > svg { width: 52px; height: 52px; padding: 14px; border-radius: 14px; background: var(--surface-soft); color: #557166; }
.result-empty h2, .result-message h2 { margin: 18px 0 7px; font-size: 20px; }
.result-empty p, .result-message p { max-width: 440px; margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.result-message ul { max-width: 520px; color: var(--muted); text-align: left; font-size: 12px; line-height: 1.7; }
.result-message button { display: inline-flex; align-items: center; gap: 7px; margin-top: 18px; padding: 10px 14px; border: 1px solid var(--line-strong); border-radius: 10px; background: #fff; cursor: pointer; }
.safety-message { padding: 24px; border-radius: 12px; background: #fff6f1; }
.result-loading { display: grid; gap: 16px; }
.result-loading span { height: 112px; border-radius: 12px; background: linear-gradient(90deg, #eef3f0 25%, #f8faf9 50%, #eef3f0 75%); background-size: 200% 100%; }
@media (prefers-reduced-motion: no-preference) { .result-loading span { animation: loading 1.3s ease infinite; } }
@keyframes loading { from { background-position: 100% 0; } to { background-position: -100% 0; } }
.result-content > header { padding-bottom: 22px; border-bottom: 1px solid var(--line); }
.result-content > header span { color: var(--accent-dark); font-size: 11px; font-weight: 700; }
.result-content > header h2 { margin: 7px 0; font-size: 26px; letter-spacing: -.8px; }
.result-content > header p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.7; }
.pdf-button { margin-top: 14px; padding: 9px 13px; border: 1px solid var(--line-strong); border-radius: 10px; background: #fff; color: var(--ink); cursor: pointer; font-size: 11px; font-weight: 700; }
.pdf-button:hover:not(:disabled) { border-color: var(--accent); color: var(--accent-dark); }
.pdf-button:active:not(:disabled) { transform: scale(.98); }
.pdf-button:disabled { cursor: wait; opacity: .65; }
.phase-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 20px 0 24px; }
.phase-item { padding: 16px; border-radius: 12px; background: var(--surface-soft); }
.phase-item > strong { display: block; font-size: 14px; }
.phase-item > span { color: var(--accent-dark); font-size: 10px; font-weight: 700; }
.phase-item p, .phase-item li { color: var(--muted); font-size: 10px; line-height: 1.6; }
.phase-item ul { margin: 8px 0 0; padding-left: 17px; }
.day-list { display: grid; gap: 10px; }
.day-item { border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.day-item summary { display: grid; grid-template-columns: 58px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 15px 16px; cursor: pointer; }
.day-item summary > span { color: var(--accent-dark); font-size: 11px; font-weight: 700; }
.day-item summary > strong { font-size: 13px; }
.day-item summary > small { color: var(--muted); font-size: 9px; }
.exercise-list { display: grid; gap: 10px; padding: 0 16px 14px; }
.exercise-item { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 14px; padding: 14px; border-radius: 10px; background: #f8faf9; }
.exercise-item > div { display: flex; flex-direction: column; gap: 3px; }
.exercise-item > div span { color: var(--accent-dark); font-size: 9px; font-weight: 700; }
.exercise-item > div strong { font-size: 12px; }
.exercise-item > div small, .exercise-item li, .exercise-item > p { color: var(--muted); font-size: 10px; line-height: 1.6; }
.exercise-item ol { margin: 0; padding-left: 18px; }
.exercise-item > p { grid-column: 2; margin: 0; color: #8b4534; }
.checkpoint { margin: 0 16px 16px; color: #4f675e; font-size: 10px; }
.safety-notices { margin-top: 18px; padding: 16px; border-radius: 12px; background: #fff6f1; }
.safety-notices h3 { margin: 0 0 7px; font-size: 13px; }
.safety-notices ul { margin: 0; padding-left: 18px; color: #704737; font-size: 10px; line-height: 1.7; }
.plan-references { display: flex; flex-wrap: wrap; gap: 8px 14px; margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--line); font-size: 11px; }
.plan-references a { color: var(--accent-dark); text-underline-offset: 3px; }
@media (max-width: 960px) { .plan-layout { grid-template-columns: 1fr; } .plan-form { position: static; } .plan-result { min-height: 420px; } .result-empty, .result-message { min-height: 360px; } }
@media (max-width: 640px) { .plan-page { padding-inline: 14px; } .plan-heading { grid-template-columns: 1fr 88px; gap: 12px; } .plan-heading img { width: 88px; height: 88px; } .plan-heading h1 { font-size: 30px; letter-spacing: -1.3px; } .plan-form { grid-template-columns: 1fr; padding: 18px; } .field, .risk-field { grid-column: 1; } .plan-result { padding: 18px; } .phase-list { grid-template-columns: 1fr; } .day-item summary { grid-template-columns: 52px 1fr; } .day-item summary > small { grid-column: 2; } .exercise-item { grid-template-columns: 1fr; } .exercise-item > p { grid-column: 1; } }
</style>
