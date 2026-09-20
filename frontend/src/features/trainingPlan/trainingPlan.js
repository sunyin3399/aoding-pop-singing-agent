const requiredFields = {
  goal: '请填写训练目标',
  level: '请选择当前水平',
  currentProblems: '请填写当前最想改善的问题',
}

export function isTrainingPlanIntent(value) {
  const text = String(value || '').replace(/\s+/g, '')
  const hasAction = /(制定|生成|安排|创建)/.test(text)
    || /给我(一个|一份|个|份)/.test(text)
    || /(帮我)?做(一个|一份|个|份)/.test(text)
    || /来(一个|一份|个|份)/.test(text)
  return hasAction && /(训练|练声|练唱)/.test(text)
}

export function isTrainingPlanShortcut(value) {
  return ['为我制定7天气息训练', '制定 30 天学习计划'].includes(String(value || '').trim())
}

export function shouldOpenTrainingPlanForm(value) {
  return isTrainingPlanShortcut(value) || isTrainingPlanIntent(value)
}

export function buildTrainingPlanDraft(content, draft = {}) {
  const days = String(content || '').match(/(\d{1,2})天/)
  return { durationDays: days ? Number(days[1]) : 7, minutesPerDay: 30, level: 'BEGINNER', ...draft }
}

export function validateTrainingPlanForm(form) {
  const errors = {}
  for (const [field, message] of Object.entries(requiredFields)) {
    if (!String(form[field] ?? '').trim()) errors[field] = message
  }
  const durationDays = Number(form.durationDays)
  const minutesPerDay = Number(form.minutesPerDay)
  if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 30) {
    errors.durationDays = '训练周期需在 1-30 天之间'
  }
  if (!Number.isInteger(minutesPerDay) || minutesPerDay < 5 || minutesPerDay > 120) {
    errors.minutesPerDay = '每日训练需在 5-120 分钟之间'
  }
  return errors
}

export function buildTrainingPlanRequest(form, conversationId) {
  const request = {
    ...form,
    goal: form.goal.trim(),
    currentProblems: form.currentProblems
      .split(/[，,\n]/)
      .map((problem) => problem.trim())
      .filter(Boolean),
    comfortableRange: form.comfortableRange?.trim() || null,
    durationDays: Number(form.durationDays),
    minutesPerDay: Number(form.minutesPerDay),
    conversationId,
    outputFormat: 'PAGE',
  }
  if (form.vocalCondition) {
    request.hasPainOrHoarseness = null
    request.vocalCondition = form.vocalCondition
    request.proceedDespiteDiscomfort = Boolean(form.proceedDespiteDiscomfort)
  } else {
    delete request.vocalCondition
    delete request.proceedDespiteDiscomfort
  }
  return request
}

export function flattenTrainingDays(result) {
  const phases = result?.plan?.phases || []
  return (result?.plan?.days || []).map((day) => ({
    ...day,
    phaseName: phases.find((phase) => day.day >= phase.startDay && day.day <= phase.endDay)?.name || '日常训练',
  }))
}

export function mergeTrainingDays(days) {
  return (days || []).reduce((merged, day) => {
    const previous = merged.at(-1)
    const sameContent = previous && previous.goal === day.goal
      && JSON.stringify(previous.exercises) === JSON.stringify(day.exercises)
    if (sameContent) {
      previous.endDay = day.day
      previous.checkpoints.push(...(day.checkpoint ? [day.checkpoint] : []))
    } else {
      merged.push({ ...day, startDay: day.day, endDay: day.day, checkpoints: day.checkpoint ? [day.checkpoint] : [] })
    }
    return merged
  }, [])
}

export function getTrainingPlanReferences(result) {
  const seen = new Set()
  return (result?.citations || []).filter((citation) => {
    const key = citation.documentId || citation.title || citation.url || citation.id
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  })
}

export const exerciseCategoryLabels = {
  WARM_UP: '热身',
  MAIN: '核心训练',
  COOL_DOWN: '放松',
}
