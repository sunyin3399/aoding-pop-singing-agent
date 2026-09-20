<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { createConversationTrainingPlan, exportTrainingPlanPdf } from '../api/trainingPlan'
import {
  buildTrainingPlanRequest, exerciseCategoryLabels, flattenTrainingDays, mergeTrainingDays,
  getTrainingPlanReferences, validateTrainingPlanForm,
} from '../features/trainingPlan/trainingPlan'

const props = defineProps({
  item: { type: Object, required: true }, userId: { type: String, required: true },
  mode: { type: String, required: true }, conversationId: { type: String, default: '' },
})
const emit = defineEmits(['saved'])
const source = props.item.draft || {}
const form = reactive({
  goal: source.goal || '', level: source.level || 'BEGINNER', durationDays: source.durationDays || 7,
  minutesPerDay: source.minutesPerDay || 30,
  currentProblems: Array.isArray(source.currentProblems) ? source.currentProblems.join('，') : (source.currentProblems || ''),
  comfortableRange: source.comfortableRange || '',
  vocalCondition: source.vocalCondition || 'NORMAL', proceedDespiteDiscomfort: false,
})
const errors = reactive({})
const loading = ref(false)
const exporting = ref(false)
const requestError = ref('')
const result = ref(props.item.result || null)
const showRiskConfirm = ref(false)
const normalMinutes = ref(Number(source.minutesPerDay) || 30)
const phaseGrid = ref(null)
const phaseScrollbar = ref(null)
const days = computed(() => mergeTrainingDays(flattenTrainingDays(result.value)))
const references = computed(() => getTrainingPlanReferences(result.value))
const conditionLabel = computed(() => ({
  NORMAL: '嗓音状态正常', MILD_PAIN_OR_HOARSENESS: '轻微疼痛或嘶哑',
  SIGNIFICANT_DISCOMFORT: '嗓音较为不适',
})[result.value?.vocalCondition || form.vocalCondition] || '')
const resultCondition = computed(() => result.value?.vocalCondition || form.vocalCondition)
function syncPhaseScroll(source) {
  const target = source === phaseGrid.value ? phaseScrollbar.value : phaseGrid.value
  if (target && source && Math.abs(target.scrollLeft - source.scrollLeft) > 1) target.scrollLeft = source.scrollLeft
}
function displayNotice(notice) {
  return String(notice || '').replace(/BEGINNER/g, '入门').replace(/INTERMEDIATE/g, '进阶').replace(/ADVANCED/g, '高级')
}
const minutesLocked = computed(() => form.vocalCondition === 'MILD_PAIN_OR_HOARSENESS'
  || form.vocalCondition === 'SIGNIFICANT_DISCOMFORT')

watch(() => form.vocalCondition, (condition) => {
  form.proceedDespiteDiscomfort = false
  if (condition === 'MILD_PAIN_OR_HOARSENESS') form.minutesPerDay = 15
  else if (condition === 'SIGNIFICANT_DISCOMFORT') form.minutesPerDay = 10
  else if (condition === 'NORMAL') form.minutesPerDay = normalMinutes.value
})

watch(() => form.minutesPerDay, (minutes) => {
  if (!minutesLocked.value && Number.isFinite(Number(minutes))) normalMinutes.value = Number(minutes)
})

async function submit() {
  Object.keys(errors).forEach((key) => delete errors[key])
  Object.assign(errors, validateTrainingPlanForm(form))
  if (!form.vocalCondition) errors.vocalCondition = '请选择当前嗓音状态'
  if (Object.keys(errors).length) return
  if (form.vocalCondition === 'SIGNIFICANT_DISCOMFORT' && !form.proceedDespiteDiscomfort) {
    showRiskConfirm.value = true
    return
  }
  loading.value = true
  requestError.value = ''
  try {
    const response = await createConversationTrainingPlan({
      userId: props.userId, mode: props.mode, conversationId: props.conversationId || null,
      userMessage: props.item.userMessage,
      request: buildTrainingPlanRequest(form, props.conversationId || null),
    })
    result.value = response.result
    if (response.result?.status === 'SUCCESS') emit('saved', response.conversationId, response.result)
    else requestError.value = response.result?.questions?.map((item) => item.question).join('；')
      || response.result?.issues?.map((item) => item.message).join('；') || '计划未通过校验，请修改后重试'
  } catch (error) { requestError.value = error.message || '训练计划生成失败，请稍后重试' }
  finally { loading.value = false }
}

function continueWithMinimumIntensity() {
  form.proceedDespiteDiscomfort = true
  showRiskConfirm.value = false
  submit()
}

async function downloadPdf() {
  if (!result.value?.plan) return
  exporting.value = true
  requestError.value = ''
  try {
    const artifact = await exportTrainingPlanPdf(result.value.plan)
    const link = document.createElement('a')
    link.href = artifact.downloadUrl
    link.download = artifact.fileName
    link.click()
  } catch (error) { requestError.value = error.message || 'PDF 导出失败' }
  finally { exporting.value = false }
}
</script>

<template>
  <article class="training-card">
    <template v-if="!result?.plan">
      <header class="card-heading"><strong>定制你的训练计划</strong><small>确认信息后，我会结合知识库生成并验收计划</small></header>
      <form @submit.prevent="submit">
        <label class="wide"><span>训练目标 <b class="required-mark">*</b></span><input v-model="form.goal" list="chat-training-goal-options" placeholder="可自由输入，或选择常见训练目标" /><datalist id="chat-training-goal-options"><option value="声带闭合训练" /><option value="气息支撑与稳定性" /><option value="高音稳定与音域拓展" /><option value="换声区衔接" /><option value="节奏感与演唱表现力" /><option value="减少喉部紧张与挤压" /></datalist><small v-if="errors.goal">{{ errors.goal }}</small></label>
        <label><span>当前水平 <b class="required-mark">*</b></span><select v-model="form.level"><option value="BEGINNER">入门</option><option value="INTERMEDIATE">进阶</option><option value="ADVANCED">高级</option></select></label>
        <label><span>训练周期 <b class="required-mark">*</b></span><span class="input-with-unit"><input v-model.number="form.durationDays" type="number" min="1" max="30" /><em>天</em></span><small v-if="errors.durationDays">{{ errors.durationDays }}</small></label>
        <label><span>每天时长 <b class="required-mark">*</b></span><span class="input-with-unit"><input v-model.number="form.minutesPerDay" type="number" min="5" max="120" :disabled="minutesLocked" /><em>分钟</em></span><small v-if="errors.minutesPerDay">{{ errors.minutesPerDay }}</small></label>
        <label class="wide"><span>当前问题 <b class="required-mark">*</b></span><textarea v-model="form.currentProblems" rows="3" placeholder="例如：高音漏气、换声区紧张，可用逗号分隔" /><small v-if="errors.currentProblems">{{ errors.currentProblems }}</small></label>
        <label><span>舒适音域</span><input v-model="form.comfortableRange" placeholder="可选，如 C3-G4" /></label>
        <label class="risk-field"><span>嗓音状态 <b class="required-mark">*</b></span><select v-model="form.vocalCondition"><option value="NORMAL">正常</option><option value="MILD_PAIN_OR_HOARSENESS">有轻微疼痛或嘶哑</option><option value="SIGNIFICANT_DISCOMFORT">较为不适</option></select><small v-if="errors.vocalCondition">{{ errors.vocalCondition }}</small></label>
        <p v-if="form.vocalCondition === 'MILD_PAIN_OR_HOARSENESS'" class="risk-note is-mild">将按每天最多 15 分钟生成低强度计划，并增加休息与停止条件。</p>
        <p v-if="form.vocalCondition === 'SIGNIFICANT_DISCOMFORT'" class="risk-note is-high"><strong>建议先暂停练唱并充分休息。</strong> 当前状态下只提供每天最多 10 分钟的最低强度恢复计划；如果疼痛、嘶哑或发声困难持续或加重，请酌情考虑就医。</p>
        <p v-if="requestError" class="training-error" role="alert">{{ requestError }}</p>
        <footer class="card-actions"><button type="submit" :disabled="loading">{{ loading ? '正在生成…' : '生成计划' }}</button><button type="button" disabled title="计划生成后可导出">导出 PDF</button></footer>
      </form>
    </template>

    <template v-else>
      <header class="plan-heading">
        <div class="plan-meta"><span>{{ result.plan.durationDays }} 天</span><span>每天 {{ result.plan.minutesPerDay }} 分钟</span><span v-if="conditionLabel">{{ conditionLabel }}</span></div>
        <h2>{{ result.plan.title }}</h2><p>{{ result.plan.goal }}</p>
      </header>
      <aside v-if="resultCondition && resultCondition !== 'NORMAL'" class="risk-banner" :class="{ 'is-high': resultCondition === 'SIGNIFICANT_DISCOMFORT' }" role="note">
        <strong>{{ resultCondition === 'SIGNIFICANT_DISCOMFORT' ? '最低强度恢复计划' : '低强度训练计划' }}</strong>
        <span>优先休息；任何疼痛加重、明显嘶哑或发声困难都应立即停止，并酌情寻求专业医疗帮助。</span>
      </aside>
      <section v-if="result.plan.phases?.length" class="phase-grid" aria-label="训练阶段">
        <div v-if="result.plan.phases.length > 2" class="phase-scrollbar-wrap">
          <span>左右拖动查看更多阶段</span>
          <div ref="phaseScrollbar" class="phase-scrollbar" aria-label="阶段卡片横向滚动条" @scroll="syncPhaseScroll(phaseScrollbar)">
            <div class="phase-scrollbar-content" :style="{ width: `calc(${result.plan.phases.length * 50}% + ${result.plan.phases.length * 5 - 10}px)` }"></div>
          </div>
        </div>
        <div ref="phaseGrid" class="phase-grid-scroller" @scroll="syncPhaseScroll(phaseGrid)">
        <article v-for="phase in result.plan.phases" :key="phase.name">
          <div class="phase-overview">
            <small>第 {{ phase.startDay }}–{{ phase.endDay }} 天</small>
            <strong>{{ phase.name }}</strong>
            <p>{{ phase.goal }}</p>
            <ul><li v-for="criterion in phase.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ul>
          </div>
          <div v-if="phase.exercises?.length" class="phase-actions">
            <span class="phase-actions-title">阶段动作</span>
            <div v-for="exercise in phase.exercises" :key="`${phase.name}-${exercise.name}`" class="phase-action">
              <header><span>{{ exerciseCategoryLabels[exercise.category] || exercise.category }}</span><strong>{{ exercise.name }}</strong><small>{{ exercise.minutes }} 分钟 · {{ exercise.sets }} 组</small></header>
              <ol><li v-for="instruction in exercise.instructions" :key="instruction">{{ instruction }}</li></ol>
              <p v-if="exercise.stopConditions?.length" class="phase-stop"><b>停止条件</b>{{ exercise.stopConditions.join('；') }}</p>
            </div>
          </div>
        </article>
        </div>
      </section>
      <section v-if="result.plan.days?.length" class="day-list" aria-label="每日训练安排">
        <details v-for="day in days" :key="day.startDay" name="training-days" :open="day.startDay === 1" class="day-card">
          <summary><span>第 {{ day.startDay }}{{ day.endDay > day.startDay ? `-${day.endDay}` : '' }} 天</span><strong>{{ day.goal }}</strong><small>{{ day.phaseName }}</small></summary>
          <div class="day-content">
            <article v-for="exercise in day.exercises" :key="`${day.day}-${exercise.category}-${exercise.name}`" class="exercise-card">
              <header><span>{{ exerciseCategoryLabels[exercise.category] || exercise.category }}</span><strong>{{ exercise.name }}</strong><small>{{ exercise.minutes }} 分钟 · {{ exercise.sets }} 组</small></header>
              <ol><li v-for="instruction in exercise.instructions" :key="instruction">{{ instruction }}</li></ol>
              <p class="stop-condition"><strong>停止条件</strong>{{ exercise.stopConditions.join('；') }}</p>
            </article>
            <p v-if="day.checkpoint" class="checkpoint"><strong>当日检查</strong>{{ day.checkpoint }}</p>
          </div>
        </details>
      </section>
      <section v-if="result.plan.safetyNotices?.length" class="safety-list"><strong>安全提醒</strong><ul><li v-for="notice in result.plan.safetyNotices" :key="notice">{{ displayNotice(notice) }}</li></ul></section>
      <section v-if="references.length" class="reference-list"><strong>参考资料（内部知识库）</strong><span v-for="reference in references" :key="reference.documentId || reference.url">{{ reference.title }}</span></section>
      <p v-if="requestError" class="training-error" role="alert">{{ requestError }}</p>
      <footer class="card-actions"><button type="button" :disabled="exporting" @click="downloadPdf">{{ exporting ? '正在导出…' : '导出 PDF' }}</button></footer>
    </template>

    <div v-if="showRiskConfirm" class="risk-dialog-backdrop">
      <section class="risk-dialog" role="alertdialog" aria-modal="true" aria-labelledby="risk-dialog-title">
        <span class="risk-mark" aria-hidden="true">!</span><h2 id="risk-dialog-title">当前状态不适合常规发声训练</h2>
        <p>建议先暂停练唱、充分休息，并根据症状酌情考虑就医。如果仍要继续，系统只会生成每天最多 10 分钟的最低强度恢复计划。</p>
        <div><button type="button" class="secondary" @click="showRiskConfirm = false">暂不生成</button><button type="button" class="danger" @click="continueWithMinimumIntensity">仍要生成最低强度计划</button></div>
      </section>
    </div>
  </article>
</template>

<style scoped>
.training-card{position:relative;width:min(760px,100%);padding:22px;border:1px solid var(--line);border-radius:20px;background:var(--surface);box-shadow:0 16px 38px rgba(40,46,70,.08);color:var(--ink)}
.card-heading{display:grid;gap:5px;margin-bottom:18px}.card-heading strong{font-size:18px}.card-heading small,.plan-heading p{color:var(--muted);font-size:12px}
form{display:grid;grid-template-columns:1fr 1fr;gap:14px}label{display:grid;gap:6px;font-size:12px;font-weight:650}.wide,.risk-note,.training-error,.card-actions{grid-column:1/-1}input,select,textarea{width:100%;min-height:42px;padding:10px 12px;border:1px solid var(--line-strong);border-radius:11px;background:var(--surface);color:var(--ink);font:inherit;font-weight:400}textarea{resize:vertical}label small,.training-error{color:#b34438}.risk-note{margin:0;padding:11px 13px;border-radius:11px;font-size:12px;line-height:1.6}.risk-note.is-mild{background:#fff8e8;color:#76551f}
.required-mark{color:#b34438}
.input-with-unit{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;border:1px solid var(--line-strong);border-radius:11px;background:var(--surface);overflow:hidden}.input-with-unit input{border:0;border-radius:0}.input-with-unit em{padding:0 12px;color:var(--muted);font-size:11px;font-style:normal}.input-with-unit:has(input:disabled){background:var(--surface-soft)}.input-with-unit input:disabled{background:transparent;color:var(--muted);cursor:not-allowed}.risk-note.is-high{background:#fff0ed;color:#812d24}.risk-note strong{display:block;margin-bottom:2px}
.card-actions{display:flex;gap:10px;margin-top:3px}.card-actions button,.risk-dialog button{min-height:42px;padding:10px 16px;border:1px solid var(--line-strong);border-radius:11px;background:var(--ink);color:var(--surface);font-weight:700;cursor:pointer}.card-actions button:disabled{cursor:not-allowed;opacity:.42}
.plan-heading{margin-bottom:18px}.plan-heading h2{margin:10px 0 5px;font-size:23px;line-height:1.25}.plan-heading p{margin:0}.plan-meta{display:flex;flex-wrap:wrap;gap:7px}.plan-meta span{padding:5px 9px;border-radius:999px;background:var(--surface-soft);color:var(--muted);font-size:11px;font-weight:700}
.risk-banner{display:grid;gap:5px;margin:0 0 18px;padding:14px;border-left:4px solid #d49a2f;border-radius:10px;background:#fff8e8;color:#6c4a13}.risk-banner.is-high{border-color:#bf4a3b;background:#fff0ed;color:#812d24}.risk-banner span{font-size:12px;line-height:1.6}
.phase-grid{margin-bottom:18px}.phase-scrollbar-wrap{display:flex;align-items:center;gap:10px;margin:0 2px 6px;color:var(--accent-dark);font-size:10px;font-weight:700}.phase-scrollbar{flex:1;height:14px;overflow-x:auto;overflow-y:hidden}.phase-scrollbar-content{height:1px}.phase-grid-scroller{display:flex;gap:10px;overflow-x:auto;padding-bottom:3px;scroll-snap-type:x proximity;scrollbar-width:none}.phase-grid-scroller::-webkit-scrollbar{display:none}.phase-grid-scroller article{flex:0 0 calc((100% - 10px)/2);display:grid;grid-template-rows:245px auto;gap:6px;padding:14px;border-radius:13px;background:var(--surface-soft);scroll-snap-align:start}.phase-overview{display:grid;gap:6px;min-height:0;overflow:auto}.phase-grid small{color:var(--muted);font-size:10px}.phase-grid p,.phase-grid li{margin:0;color:var(--muted);font-size:11px;line-height:1.55}.phase-grid ul{margin:2px 0 0;padding-left:17px}.phase-actions{display:grid;gap:8px;margin-top:7px;padding-top:10px;border-top:1px solid rgba(203,216,210,.8)}.phase-actions-title{color:var(--accent-dark);font-size:11px;font-weight:750}.phase-action{display:grid;gap:5px;padding:10px 11px;border-radius:10px;background:rgba(255,255,255,.58)}.phase-action header{display:grid;grid-template-columns:auto minmax(0,1fr);gap:4px 8px;align-items:baseline}.phase-action header span{color:var(--accent-dark);font-size:10px;font-weight:750}.phase-action header strong{font-size:12px}.phase-action header small{grid-column:2;color:var(--muted);font-size:10px}.phase-action ol{margin:3px 0 0;padding-left:18px}.phase-action li{font-size:11px;line-height:1.65}.phase-stop{display:grid;grid-template-columns:auto 1fr;gap:6px;margin:3px 0 0;padding-top:7px;border-top:1px solid rgba(203,216,210,.65);color:#874034!important;font-size:11px!important;line-height:1.6}.phase-stop b{font-weight:750}
.day-list{display:grid;gap:10px}.day-card{border:1px solid var(--line);border-radius:14px;overflow:hidden}.day-card summary{display:grid;grid-template-columns:70px minmax(0,1fr) auto;gap:10px;align-items:center;padding:15px;cursor:pointer}.day-card summary span{color:var(--accent-dark);font-size:11px;font-weight:750}.day-card summary strong{font-size:13px}.day-card summary small{color:var(--muted);font-size:10px}.day-content{display:grid;gap:10px;padding:0 14px 14px}.exercise-card{padding:14px;border-radius:12px;background:var(--surface-soft)}.exercise-card header{display:grid;grid-template-columns:76px minmax(0,1fr) auto;gap:8px;align-items:center}.exercise-card header span{color:var(--accent-dark);font-size:10px;font-weight:750}.exercise-card header small,.exercise-card li{color:var(--muted);font-size:11px}.exercise-card ol{margin:12px 0 0;padding-left:20px;line-height:1.65}.stop-condition,.checkpoint{display:grid;grid-template-columns:72px 1fr;gap:8px;margin:12px 0 0;padding-top:10px;border-top:1px solid var(--line);color:#874034;font-size:11px;line-height:1.55}.checkpoint{margin:0;padding:12px;border:0;border-radius:10px;background:#edf4f1;color:#49665b}
.safety-list,.reference-list{margin-top:16px;padding:15px;border-radius:12px;background:#fff8e8;color:#704f1e;font-size:11px}.safety-list ul{margin:7px 0 0;padding-left:18px;line-height:1.65}.reference-list{display:flex;flex-wrap:wrap;gap:8px 14px;background:var(--surface-soft);color:var(--ink)}.reference-list a{color:var(--accent-dark)}
.risk-dialog-backdrop{position:absolute;inset:0;z-index:5;display:grid;place-items:center;padding:20px;border-radius:20px;background:rgba(20,24,34,.58);backdrop-filter:blur(3px)}.risk-dialog{width:min(460px,100%);padding:24px;border-radius:16px;background:var(--surface);box-shadow:0 24px 60px rgba(0,0,0,.28)}.risk-mark{display:grid;place-items:center;width:38px;height:38px;border-radius:50%;background:#fff0ed;color:#a63327;font-size:22px;font-weight:800}.risk-dialog h2{margin:13px 0 8px;font-size:20px}.risk-dialog p{margin:0;color:var(--muted);font-size:13px;line-height:1.7}.risk-dialog div{display:flex;justify-content:flex-end;gap:9px;margin-top:20px}.risk-dialog .secondary{background:var(--surface);color:var(--ink)}.risk-dialog .danger{border-color:#a63327;background:#a63327;color:#fff}
@media(max-width:640px){.training-card{padding:16px}form{grid-template-columns:1fr}.phase-scrollbar-wrap{display:grid;grid-template-columns:1fr;gap:2px;margin-inline:0}.phase-grid-scroller article{flex-basis:88%;grid-template-rows:auto auto}.phase-overview{overflow:visible}.wide,.risk-note,.training-error,.card-actions{grid-column:1}.day-card summary{grid-template-columns:58px 1fr}.day-card summary small{grid-column:2}.exercise-card header{grid-template-columns:1fr}.risk-dialog div{flex-direction:column}.risk-dialog button{width:100%}}
</style>
