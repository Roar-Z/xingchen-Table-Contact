﻿<script setup>
/**
 * 新建任务页 —— 智能体工作台（3 个阶段）
 *
 * 1 交接任务：源表 + 目标模板 + 一句话目标一次交给智能体
 * 2 核对方案：智能体轨迹、决策摘要、字段映射（人工闸门）
 * 3 交付结果：执行进度、结果预览、下载
 *
 * 说明：后端点位都集中在 api.js，本文件只负责页面状态和交互，
 * 后续替换成别的智能体服务时改 api.js 即可，页面不用重写。
 */
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElInput,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTabPane,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTabs,
  ElUpload
} from 'element-plus'
import 'element-plus/dist/index.css'
import Icon from '../components/Icon.vue'
import ResultTable from '../components/ResultTable.vue'
import BridgeAgent from '../components/BridgeAgent.vue'
import { api, apiState } from '../api'
import {
  railMemory,
  railMemoryRequest,
  railState,
  railSteps,
  railTools,
  railTone,
  railVisible
} from '../agentRail'
import { useToast } from '../toast'
import { clearTopbar, setTopbarSegment, useTopbar, useTopbarSegments } from '../topbar'

// 组件名要和 App.vue 里 keep-alive 的 include 一致，否则切页面会重新挂载、任务全丢
defineOptions({ name: 'AgentWorkbench' })

const router = useRouter()
const { show } = useToast()

// 页面标题和说明统一放到顶栏，页面正文不再重复一遍
const TOPBAR = {
  title: '智能体工作台',
  desc: '把源表、目标模板和一句话目标交给智能体，它会自己读表、查记忆、规划映射，你只需要核对方案。'
}

/*
 * 两种工作模式：
 * standard —— 源表 + 目标模板，逐列核对，适合明确的转换任务；
 * agent    —— 上传整个数据集（zip / 文件夹），一句话业务目标，智能体自己找表、对齐、计算、导出。
 * 模式记在 sessionStorage 里，刷新页面还停在原来那一页。
 */
const MODE_KEY = 'huibiao.mode.v1'
const workspaceMode = ref('standard')
try {
  if (sessionStorage.getItem(MODE_KEY) === 'agent') workspaceMode.value = 'agent'
} catch (e) {
  // 隐私模式下 sessionStorage 不可用，用默认模式即可
}

const TOPBAR_AGENT = {
  title: '智能体工作台 · 跨系统桥接',
  desc: '整个数据集（压缩包或文件夹）交上来，说一句业务目标，智能体自己找表、对齐字段、跨表计算，输出标准表和异常清单。'
}

// 模式切换放在顶栏右侧，页面顶部不再占一整条
const MODE_SEGMENTS = [
  {
    key: 'standard',
    label: '标准模式',
    hint: '源表 + 目标模板，逐列核对，适合明确的转换任务'
  },
  {
    key: 'agent',
    label: '智能体模式',
    badge: '核心',
    hint: '上传压缩包或文件夹，说一句业务目标，自己找表、对齐字段、出标准表'
  }
]

// 顶栏被 keep-alive 收回后要重新挂上模式切换，挂载、激活各调一次
function mountTopbar() {
  useTopbar(workspaceMode.value === 'agent' ? TOPBAR_AGENT : TOPBAR)
  useTopbarSegments(MODE_SEGMENTS, switchMode)
  setTopbarSegment(workspaceMode.value)
}

function switchMode(mode) {
  if (workspaceMode.value === mode) return
  workspaceMode.value = mode
  try {
    sessionStorage.setItem(MODE_KEY, mode)
  } catch (e) {
    // 同上
  }
  mountTopbar()
  railVisible.value = mode !== 'agent'
}

// 首次渲染就要把标题和模式切换挂上去，不能等 onMounted
mountTopbar()

/* ============ 区块 1：文件 ============ */
const sourceFiles = ref([]) // 源数据表，允许多个
const templateFile = ref(null) // 目标模板，只认一个；导出列完全按它生成
const sourceUploadCount = ref(0)
const templateUploadCount = ref(0)
// 两个上传区各管各的状态，避免传源表时模板区也跟着显示“正在上传”
const sourceUploading = computed(() => sourceUploadCount.value > 0)
const templateUploading = computed(() => templateUploadCount.value > 0)

/* ============ 区块 2：映射 ============ */
const analyzing = ref(false)
const analyzeError = ref('')
const intent = ref(null)
const mappings = ref([])
const mappingDraft = ref({ target: '', file_id: '', column: '' })
const mappingConfirmed = ref(false)
const schemeName = ref('')
const activeScheme = ref('')
const schemes = ref([])

/* ---------------- 智能体：执行轨迹 + 经验记忆 ---------------- */
const agentSteps = ref([])
const agentFixes = ref([])
const agentHandoff = ref([])
const agentExperienceLabel = ref('')
const agentMode = ref('rules')
const agentFallback = ref(false)
const agentSummary = ref('')
const agentCollapsed = ref(false)
const agentExperiences = ref([])
const memoryVisible = ref(false)
// 业务口径改过之后，旧方案就和输入不一致了，必须由用户再点一次按钮才重新规划
const planStale = ref(false)

/* ============ 区块 3：规则 ============ */
const brief = ref('')
const isolate = ref(true) // 脏数据隔离
const strictFormat = ref(false) // 严格格式校验

/* ============ 区块 4/5：运行与结果 ============ */
const running = ref(false)
const task = ref(null)
const runError = ref('')
const resultTab = ref('ok')

// 结果表的自然语言微调：改完会作为一条记录保存，重算时一起重放
const editInput = ref('')
const editBusy = ref(false)
const editError = ref('')

const editLog = computed(() => task.value?.config?.edits || [])
const editChanged = computed(() =>
  editLog.value.reduce((sum, item) => sum + (Number(item.changed_rows) || 0), 0)
)
const editSamples = computed(() => {
  const columns = outputColumns.value
  if (!columns.length) return []
  const first = columns[0]
  const numeric = columns.find((name) => /小时|时长|数量|金额|天数/.test(name)) || columns[columns.length - 1]
  return [
    `第 2 行的${numeric}改成 8`,
    `把「${first}」为空的记录删掉`,
    `按${numeric}降序排序`
  ]
})

let pollTimer = null
let analyzeSeq = 0
// 「试试」里的句子只负责填进输入框，由用户确认后再提交，不直接发给智能体
const editInputRef = ref(null)

/* ---------------- 规划进度 ----------------
 * 后端是一次性返回结果的，拿不到逐步骤实时进度；这里用“阶段文案 + 渐进进度条 + 已等待秒数”
 * 让等待过程可感知，进度封顶 92%，等真结果回来才结束，不会假装跑满。
 */
const planElapsed = ref(0)
let planTimer = null

/*
 * 进度条、阶段文案、左侧工具条共用这一张表：
 * until = 该阶段结束秒数，percent = 该阶段结束时的进度，reveal = 到这个阶段已经亮出的工具条数。
 * 三者同源，避免出现“左边 6 条全亮着、右边还停在检索映射”的对不上。
 */
const PLAN_STAGES = [
  { until: 1.5, text: '读表：表头、行数与样例值', percent: 28, reveal: 1 },
  { until: 3, text: '对齐目标模板的列和顺序', percent: 52, reveal: 2 },
  { until: 5.5, text: '检索历史配置，校验映射与必填项', percent: 80, reveal: 4 },
  { until: Number.POSITIVE_INFINITY, text: '汇总方案与待确认项', percent: 92, reveal: 6 }
]

const planStageIndex = computed(() => {
  const index = PLAN_STAGES.findIndex((item) => planElapsed.value < item.until)
  return index === -1 ? PLAN_STAGES.length - 1 : index
})

const planStageText = computed(() => PLAN_STAGES[planStageIndex.value].text)

// 按阶段区间插值，进度条走到的百分比和当前阶段文案始终一致（封顶 92%，真结果回来才收尾）
const planPercent = computed(() => {
  let prevPercent = 6
  let from = 0
  for (const stage of PLAN_STAGES) {
    if (planElapsed.value < stage.until) {
      const span = Number.isFinite(stage.until) ? stage.until - from : 4
      const ratio = span > 0 ? Math.min(1, (planElapsed.value - from) / span) : 1
      return Math.min(92, prevPercent + (stage.percent - prevPercent) * ratio)
    }
    prevPercent = stage.percent
    from = stage.until
  }
  return 92
})

function startPlanProgress() {
  stopPlanProgress()
  planElapsed.value = 0
  railReveal.value = 1
  planTimer = setInterval(() => {
    planElapsed.value += 0.2
    const next = revealCountOf(planElapsed.value)
    if (next !== railReveal.value) railReveal.value = next
  }, 200)
}

function stopPlanProgress() {
  if (planTimer) {
    clearInterval(planTimer)
    planTimer = null
  }
}

/* ============ 分步引导（3 阶段） ============ */
// 原先把「上传 / 映射 / 规则 / 执行 / 下载」拆成 5 步，交流成本太高；
// 智能体工作台按“交接 → 核对 → 交付”三段走，规则并进交接阶段的对话里。
const stepList = [
  { no: 1, title: '交接任务', sub: '文件 + 目标' },
  { no: 2, title: '核对方案', sub: '轨迹 + 映射' },
  { no: 3, title: '交付结果', sub: '执行 + 下载' }
]
const step = ref(1)

const advancedOpen = ref(false)
const fieldsOpen = ref(false)

// 当前阶段的核心条件没满足时，不允许往下走
function gatePassed(no) {
  // 第一步的闸门不是“文件齐了”，而是“方案出来了”：还在读表或口径改过时，
  // 下一步按钮必须点不动，等规划真正结束再由 startPlanning 自动跳过去
  if (no === 1) {
    return !!sourceFiles.value.length && !!templateFile.value
      && !!intent.value && !planStale.value && !analyzing.value
  }
  if (no === 2) return !!intent.value && mappingConfirmed.value
  return true
}

// 按顺序走过的最远一步；配合下面的条件，决定哪些步骤可以点
const reached = ref(1)

const gateLimit = computed(() => {
  if (!sourceFiles.value.length || !templateFile.value) return 1
  if (!intent.value || !mappingConfirmed.value) return 2
  return 3
})

const maxStep = computed(() => Math.min(reached.value, gateLimit.value))

const canLeave = computed(() => gatePassed(step.value))

watch(maxStep, (max) => {
  if (step.value > max) step.value = max
})

function goStep(no) {
  if (no < 1 || no > maxStep.value) return
  step.value = no
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function nextStep() {
  if (!canLeave.value || step.value >= 3) return
  step.value += 1
  reached.value = Math.max(reached.value, step.value)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function prevStep() {
  if (step.value > 1) goStep(step.value - 1)
}

const nextLabel = computed(() => {
  // 规划还在跑的时候按钮是禁用的，文案要说明“不用你点，完成会自动进入下一步”
  if (step.value === 1 && analyzing.value) return '规划完成后自动进入下一步'
  const next = stepList.find((item) => item.no === step.value + 1)
  return next ? `下一步：${next.title}` : ''
})

const stepHint = computed(() => {
  if (step.value === 1) {
    if (!sourceFiles.value.length && !templateReady.value) return '把源表和目标模板交给智能体，它才读得到数据'
    if (!sourceFiles.value.length) return '还缺源数据表'
    if (!templateReady.value) return '还缺目标模板，导出列由它决定'
    if (analyzing.value) return '智能体正在读表，完成后会自动进入核对方案'
    if (!intent.value) return analyzeError.value || '点「交给智能体规划」，智能体才会读表出方案'
    if (planStale.value) return '业务口径改过了，点「交给智能体规划」重新出方案'
    return '智能体已出方案，进入下一步核对'
  }
  if (step.value === 2 && !mappingConfirmed.value) {
    return intent.value ? '核对轨迹和映射，点「确认方案」后才能执行' : '智能体还在规划，稍等一下'
  }
  if (step.value === 2) return '方案已确认，进入下一步让智能体执行'
  if (step.value === 3 && !task.value) return '点「让智能体执行」，处理完成后在这里下载'
  if (step.value === 3 && task.value) {
    return task.value.status === 'warning' ? '已完成，建议先看异常日志再下载' : '结果已生成，可直接下载'
  }
  return ''
})

/* ---------------- 智能体状态（右侧状态轨） ---------------- */
const agentStateText = computed(() => {
  if (task.value && ['queued', 'running'].includes(task.value.status)) return '执行中'
  if (task.value && ['done', 'warning'].includes(task.value.status)) return '已交付'
  if (running.value) return '执行中'
  if (analyzing.value) return '规划中'
  if (mappingConfirmed.value) return '方案已确认'
  if (intent.value) return '等待核对'
  if (sourceFiles.value.length) return '读取中'
  return '待交接'
})

const agentStateTone = computed(() => {
  if (agentStateText.value === '已交付') return 'ok'
  if (['执行中', '规划中', '读取中'].includes(agentStateText.value)) return 'busy'
  if (agentStateText.value === '等待核对' || agentStateText.value === '方案已确认') return 'wait'
  return 'idle'
})

// 规划阶段按耗时逐条亮出能力，不要一进页面就把 4 条全摆出来
// 只在“该亮第几条”变化时才更新，避免每 200ms 的计时把导航栏刷一遍
const railReveal = ref(0)

// 左侧工具条亮到第几条，和进度条的阶段用同一张表；当前阶段文案提到的事就是正在亮的那条
function revealCountOf(elapsed) {
  const index = PLAN_STAGES.findIndex((item) => elapsed < item.until)
  return PLAN_STAGES[index === -1 ? PLAN_STAGES.length - 1 : index].reveal
}

// 导航栏里只展示“这一轮要用到的能力”，不堆术语
const agentRailTools = computed(() => {
  const all = [
    { key: 'inspect_sources', label: '读表', hint: '表头 / 行数 / 样例' },
    { key: 'detect_intent', label: '理解目标', hint: '场景与业务口径' },
    { key: 'recall_experience', label: '查记忆', hint: '复用跑过的配置' },
    { key: 'validate_mapping', label: '校验映射', hint: '必填 / 表达式合法性' },
    { key: 'auto_fix', label: '自动补全', hint: '拼接与换算' },
    { key: 'handoff_human', label: '用户确认', hint: '方案经用户确认后执行' }
  ]
  // 还没开始规划时一条都不列，等真的跑起来再逐条出现
  if (!agentSteps.value.length) {
    if (!analyzing.value) return []
    const count = Math.min(railReveal.value || 1, all.length)
    return all.slice(0, count).map((item, index) => ({
      ...item,
      state: index === count - 1 ? 'busy' : 'done'
    }))
  }
  const done = new Set(agentSteps.value.map((item) => item.tool))
  return all.map((item) => ({
    ...item,
    state: done.has(item.key) ? 'done' : 'skip'
  }))
})

const planHeadline = computed(() => {
  if (!intent.value) return '还没有可核对的方案'
  const source = sourceFiles.value.length
  const cols = targetColumns.value.length
  const parts = [
    `读了 ${source} 张源表、${totalSourceFields.value} 个可用字段`,
    `按 ${intent.value.scene_name || '当前场景'} 规划 ${cols} 个目标列`
  ]
  if (agentExperienceLabel.value) parts.push('命中了历史经验并优先复用')
  if (agentFixes.value.length) parts.push(`自动补全 ${agentFixes.value.length} 处`)
  if (missingRequired.value) parts.push(`还有 ${missingRequired.value} 个必填列需要你处理`)
  return parts.join('，') + '。'
})

const planTiles = computed(() => {
  if (!intent.value) return []
  return [
    {
      key: 'source',
      label: '读到的源表',
      value: String(sourceFiles.value.length),
      unit: '张',
      note: `${totalSourceFields.value} 个字段可用`
    },
    {
      key: 'target',
      label: '目标列',
      value: String(targetColumns.value.length),
      unit: '列',
      note: `已映射 ${mappedCount.value} 列`
    },
    {
      key: 'memory',
      label: '记忆命中',
      value: agentExperienceLabel.value ? '是' : '否',
      unit: '',
      note: agentExperienceLabel.value ? '优先复用这条经验' : '首次处理这类表'
    },
    {
      key: 'handoff',
      label: '待用户确认',
      value: String(agentHandoff.value.length || missingRequired.value),
      unit: '处',
      note: agentHandoff.value.length ? '见下方待确认清单' : '方案经用户确认后方可执行'
    }
  ]
})

/* 清空这一单：文件、方案、结果和本地现场一起清掉，方便重新交接 */
function resetAll() {
  stopPolling()
  stopPlanProgress()
  sourceFiles.value = []
  templateFile.value = null
  brief.value = ''
  isolate.value = true
  strictFormat.value = false
  intent.value = null
  mappings.value = []
  mappingConfirmed.value = false
  analyzeError.value = ''
  planStale.value = false
  resetAgent()
  resetResult()
  step.value = 1
  reached.value = 1
  try {
    sessionStorage.removeItem(STORE_KEY)
  } catch (e) {
    // 存储不可用时忽略，内存状态已经清空
  }
  show('已清空，可以重新交接一单')
}

const quickRules = [
  {
    key: 'leave',
    label: '年假抵扣 · 不足转事假',
    text:
      '把 OA 导出的请假单和年假余额表按工号关联，先扣剩余年假，年假不足的部分转成事假，' +
      '最后按 HR 最低版本缺勤模板导出，工号对不上的行单独记异常。'
  },
  {
    key: 'overtime',
    label: '按部门时间段筛选加班',
    text:
      '筛选总装车间 2026 年 4 月 1 日到 4 月 30 日的加班记录，按 HR 加班导入模板输出，' +
      '申请加班为空或者不是数字的行放进异常日志。'
  }
]

/* ---------------- 派生状态 ---------------- */

const templateReady = computed(() => !!templateFile.value)
const templateLabel = computed(() => (templateFile.value ? templateFile.value.name : '未上传'))

const targetColumns = computed(() => intent.value?.target_template?.columns || [])
const requiredTargets = computed(() => {
  const map = {}
  ;(intent.value?.mapping || []).forEach((item) => {
    map[item.target] = item.required
  })
  return map
})

// 左侧字段直接用上传时解析的结果，解析失败也能看到表头
const fieldGroups = computed(() =>
  sourceFiles.value.map((file) => ({
    file_id: file.file_id,
    name: file.name,
    columns: file.columns || [],
    sample: file.sample || {}
  }))
)

const totalSourceFields = computed(() =>
  fieldGroups.value.reduce((sum, group) => sum + group.columns.length, 0)
)

// 这些目标字段由后端按固定值写入，不需要选源列（与 LeaveSettlementService 保持一致）
const FIXED_VALUES = { 免打卡: '是' }

function isFixedRow(row) {
  return !!(row && FIXED_VALUES[row.target] && !row.file_id && !row.column)
}

// 只有文件还在、源字段确实存在，这条映射才算数
function mappingValid(row) {
  if (!row || !row.file_id) return false
  if (row.expr) return exprValid(row.expr, row.file_id)
  const file = sourceFiles.value.find((item) => item.file_id === row.file_id)
  return !!file && (file.columns || []).includes(row.column)
}

/* ---------------- 拼接 / 派生表达式 ----------------
 * 源表列不够用时，目标列可以写成 `{起始日期} {起始时间}`、`加班`、`{天}*8`，
 * 校验规则和后端 ColumnExpressions 一致：花括号里必须是这张表真实存在的列。
 */
function columnsOfFile(fileId) {
  const file = sourceFiles.value.find((item) => item.file_id === fileId)
  return file ? file.columns || [] : []
}

function exprPlaceholders(expr) {
  return Array.from(expr.matchAll(/\{([^{}]+)\}/g)).map((item) => item[1].trim())
}

function exprValid(expr, fileId) {
  const text = (expr || '').trim()
  if (!text) return false
  const used = exprPlaceholders(text)
  if (!used.length) return true // 纯常量，比如「加班」「是」
  const columns = columnsOfFile(fileId)
  return used.every((name) => columns.includes(name))
}

function exprErrorOf(expr, fileId) {
  const text = (expr || '').trim()
  if (!text) return '表达式不能为空'
  const missing = exprPlaceholders(text).filter((name) => !columnsOfFile(fileId).includes(name))
  return missing.length ? `源表里没有这些列：${missing.join('、')}` : ''
}

// 用每列第一个样例值渲染一遍，让用户在上报前就看出结果长什么样
function exprPreviewOf(expr, fileId) {
  const text = (expr || '').trim()
  if (!text) return ''
  const file = sourceFiles.value.find((item) => item.file_id === fileId)
  const sample = file?.sample || {}
  const multiply = text.match(/^\{([^{}]+)\}\s*\*\s*(\d+(?:\.\d+)?)$/)
  if (multiply) {
    const value = Number(String(sample[multiply[1].trim()] ?? '').replace(/[^\d.-]/g, ''))
    return Number.isFinite(value) ? String(Math.round(value * Number(multiply[2]) * 10) / 10) : '（该列不是数字）'
  }
  // 日期 + 时刻：合并成一个标准日期时间，和业务实际输出保持一致
  const splice = text.match(/^\{([^{}]+)\}\s*\+\s*\{([^{}]+)\}$/)
  if (splice) {
    const date = String(sample[splice[1].trim()] ?? `【${splice[1].trim()}】`).trim()
    const time = String(sample[splice[2].trim()] ?? `【${splice[2].trim()}】`).trim()
    return `${date} ${time}`.trim()
  }
  return text
    .replace(/\{([^{}]+)\}/g, (all, name) => String(sample[name.trim()] ?? `【${name.trim()}】`))
    .replace(/["']/g, '')
    .trim()
}

const editableTargets = computed(() => targetColumns.value.filter((target) => !FIXED_VALUES[target]))
const mappedCount = computed(() => mappings.value.filter(mappingValid).length)
// 来源统计：0 条就说明模型这次没给出可用映射，界面上直接讲清楚，别让人以为 AI 没调用
const aiMappingCount = computed(() => mappings.value.filter((row) => row.origin === 'ai').length)

// 必填项按“模板需要的列”算，避免用户直接删掉整行后漏检
const missingRequired = computed(() =>
  targetColumns.value
    .filter((target) => requiredTargets.value[target] && !FIXED_VALUES[target])
    .filter((target) => !mappingValid(mappings.value.find((row) => row.target === target)))
    .length
)

// 映射内容一变，之前的确认就作废，必须重新人工过一遍
const mappingSignature = computed(() =>
  mappings.value
    .map((row) => `${row.target}|${row.file_id || ''}|${row.column || ''}|${row.expr || ''}`)
    .sort()
    .join(';')
)

watch(mappingSignature, () => {
  mappingConfirmed.value = false
})

const canConfirmMapping = computed(
  () => !!intent.value && targetColumns.value.length > 0 && !mappingConfirmed.value
)

function confirmMapping() {
  if (!intent.value) {
    show('还没有识别结果，先上传源表和模板', 'err')
    return
  }
  if (!mappings.value.some(mappingValid)) {
    show('至少先映射一个目标字段', 'err')
    return
  }
  if (missingRequired.value > 0) {
    show(`还有 ${missingRequired.value} 个必填字段没有映射，补齐后再确认`, 'err')
    return
  }
  mappingConfirmed.value = true
  show('方案已确认，可以交给智能体执行')
}

// 右侧：目标模板需要的字段 + 当前挂到了哪里
const targetRows = computed(() =>
  targetColumns.value.map((target) => {
    const row = mappings.value.find((item) => item.target === target)
    const file = row ? sourceFiles.value.find((item) => item.file_id === row.file_id) : null
    if (isFixedRow(row)) {
      return { target, required: false, mapped: true, source: `固定值「${FIXED_VALUES[target]}」` }
    }
    if (row && row.expr && mappingValid(row)) {
      return {
        target,
        required: requiredTargets.value[target] ?? false,
        mapped: true,
        source: `拼接「${row.expr}」→ ${exprPreviewOf(row.expr, row.file_id)}`
      }
    }
    return {
      target,
      required: requiredTargets.value[target] ?? false,
      mapped: mappingValid(row),
      source: mappingValid(row) ? `${file.name} / ${row.column}` : '待映射'
    }
  })
)

// 导出列一律以目标模板为准，没有模板就不调用后端解析
const templateFileId = computed(() => templateFile.value?.file_id || '')

// 后端在解析时给的处理提示，比如模板列没有数据来源、表头下移等
const intentNotes = computed(() => intent.value?.notes || [])

/* ---------------- 智能体轨迹展示 ---------------- */
const agentToolCount = computed(() => new Set(agentSteps.value.map((item) => item.tool).filter(Boolean)).size)

const agentElapsedText = computed(() => {
  const total = agentSteps.value.reduce((sum, item) => sum + (item.elapsed_ms || 0), 0)
  if (!total) return '不到 1 秒'
  return total < 1000 ? `${total} ms` : `${(total / 1000).toFixed(1)} 秒`
})

const agentModeLabel = computed(() =>
  agentMode.value === 'llm' ? '模型自主编排 · 工具调用' : '规则工具链 · 离线兜底'
)

// 本轮配置编排实际消耗的 token，供应商未上报时显示为空
const agentTokens = ref(0)
const agentModelCalls = ref(0)
const agentTokenText = computed(() => {
  if (!agentTokens.value) return ''
  const calls = agentModelCalls.value ? ` · ${agentModelCalls.value} 次模型调用` : ''
  return `${agentTokens.value.toLocaleString('zh-CN')} token${calls}`
})

const AGENT_STATUS = {
  ok: '完成',
  warn: '需关注',
  fail: '失败',
  skip: '跳过'
}

function agentStatusLabel(status) {
  return AGENT_STATUS[status] || '完成'
}

function agentStepTime(ms) {
  if (!ms) return ''
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

function resetAgent() {
  agentSteps.value = []
  agentFixes.value = []
  agentHandoff.value = []
  agentExperienceLabel.value = ''
  agentMode.value = 'rules'
  agentFallback.value = false
  agentSummary.value = ''
  agentTokens.value = 0
  agentModelCalls.value = 0
}

async function loadAgentExperience() {
  try {
    agentExperiences.value = (await api.agentExperience()) || []
  } catch (err) {
    // 记忆库是增强能力，接口不可用时不影响主流程
    agentExperiences.value = []
  }
}

async function forgetExperience(row) {
  if (!row || !window.confirm(`确定让智能体忘记经验「${row.template_name || row.scene || row.id}」？`)) return
  try {
    await api.deleteAgentExperience(row.id)
    await loadAgentExperience()
    show('已从经验库移除')
  } catch (err) {
    show(err.message, 'err')
  }
}

// 勾选后一次删掉多条：接口是单条删除，这里按顺序逐条调，失败的不静默吞掉
const memorySelection = ref([])

async function forgetSelectedExperiences() {
  const rows = [...memorySelection.value]
  if (!rows.length) return
  if (!window.confirm(`确定让智能体忘记选中的 ${rows.length} 条经验？`)) return
  let removed = 0
  let failed = 0
  for (const row of rows) {
    try {
      await api.deleteAgentExperience(row.id)
      removed += 1
    } catch (err) {
      failed += 1
    }
  }
  memorySelection.value = []
  await loadAgentExperience()
  if (failed) show(`已移除 ${removed} 条，还有 ${failed} 条没删掉`, 'err')
  else show(`已从经验库移除 ${removed} 条`)
}

// 弹窗关掉就清掉勾选，下次打开不会带着上次的选择
watch(memoryVisible, (open) => {
  if (!open) memorySelection.value = []
})

const prereqMissing = computed(() => {
  const list = []
  if (!sourceFiles.value.length) list.push('还没有上传源数据表')
  if (!templateReady.value) list.push('还没有上传目标模板')
  return list
})

const runHints = computed(() => {
  const list = [...prereqMissing.value]
  if (sourceFiles.value.length && templateReady.value && !intent.value) {
    list.push(analyzeError.value || '正在识别表头和处理场景，稍等一下')
  }
  if (intent.value && !mappingConfirmed.value) {
    list.push('字段映射还没有确认，先回到第 2 步核对 AI 推荐结果')
  }
  if (strictFormat.value && missingRequired.value > 0) {
    list.push(`严格格式校验：还有 ${missingRequired.value} 个必填字段没有映射`)
  }
  return list
})

const canRun = computed(() => {
  if (running.value) return false
  if (prereqMissing.value.length) return false
  if (!intent.value || !mappingConfirmed.value) return false
  if (strictFormat.value && missingRequired.value > 0) return false
  return true
})

const progress = computed(() => task.value?.progress ?? 0)
const lastLog = computed(() => {
  const logs = task.value?.logs || []
  return logs.length ? logs[logs.length - 1].message : '正在处理'
})

const outputColumns = computed(() => task.value?.output_columns || [])
// 任务正在跑：结果表先让位给进度，跑完再挂载分页表格
const taskRunning = computed(() => !!task.value && ['queued', 'running'].includes(task.value.status))
const anomalyRows = computed(() => task.value?.anomalies || [])

const resultArtifact = computed(() => {
  const list = task.value?.artifacts || []
  return list.find((item) => /\.xls$/i.test(item.name)) || list.find((item) => /\.xlsx$/i.test(item.name)) || null
})
const resultUrl = computed(() =>
  task.value && resultArtifact.value ? api.downloadUrl(task.value.id, resultArtifact.value.name) : ''
)

const statCards = computed(() => {
  if (!task.value) return []
  const defs =
    task.value.scene === 'leave'
      ? [
          { key: 'source_rows', label: '源表行数', unit: '行' },
          { key: 'candidate_rows', label: '命中条件', unit: '行' },
          { key: 'output_rows', label: '输出行数', unit: '行' },
          { key: 'annual_hours', label: '年假抵扣', unit: '小时' },
          { key: 'personal_hours', label: '事假合计', unit: '小时' },
          { key: 'anomaly_rows', label: '异常行', unit: '行', warn: true }
        ]
      : [
          { key: 'source_rows', label: '源表行数', unit: '行' },
          { key: 'candidate_rows', label: '命中条件', unit: '行' },
          { key: 'output_rows', label: '输出行数', unit: '行' },
          { key: 'total_hours', label: '加班合计', unit: '小时' },
          { key: 'skipped_rows', label: '条件外跳过', unit: '行' },
          { key: 'anomaly_rows', label: '异常行', unit: '行', warn: true }
        ]
  const stats = task.value.stats || {}
  return defs
    .filter((item) => stats[item.key] !== undefined)
    .map((item) => ({
      ...item,
      value: formatNumber(stats[item.key]),
      warn: item.warn && Number(stats[item.key]) > 0
    }))
})

const statusText = computed(() => {
  const status = task.value?.status
  if (status === 'warning') return '已完成，建议先看异常日志'
  if (status === 'failed') return '处理失败'
  if (status === 'done') return '已完成'
  return '处理中'
})

/* ---------------- 小工具 ---------------- */

function sizeText(bytes) {
  if (!bytes) return '—'
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function rowCount(file) {
  return file.sheets && file.sheets[0] ? file.sheets[0].rows : 0
}

function formatNumber(value) {
  if (typeof value === 'number') {
    return Number.isInteger(value) ? value.toLocaleString('zh-CN') : value.toFixed(1)
  }
  return value === null || value === undefined || value === '' ? '—' : value
}

function sampleOfGroup(group, column) {
  const value = group.sample?.[column]
  return value === undefined || value === '' ? '—' : value
}

// 映射表里按“来源文件 + 源字段”取样例值
function sampleOf(row) {
  const file = sourceFiles.value.find((item) => item.file_id === row.file_id)
  if (!file || !row.column) return '—'
  const value = file.sample?.[row.column]
  return value === undefined || value === '' ? '—' : value
}

function columnsOf(fileId) {
  const file = sourceFiles.value.find((item) => item.file_id === fileId)
  return file ? file.columns || [] : []
}

function sourceRefs() {
  return sourceFiles.value.map((file) => ({ file_id: file.file_id, role: file.role || 'auto' }))
}

function resetResult() {
  task.value = null
  runError.value = ''
  resultTab.value = 'ok'
  editInput.value = ''
  editError.value = ''
  stopPolling()
}

/* ============ 区块 1：上传 ============ */

// 后端对接点位：POST /api/files/upload（multipart: file + role）
async function uploadSource(options) {
  const file = options.file
  if (!/\.(xlsx|xls|csv)$/i.test(file.name)) {
    show('只支持 .xlsx / .xls / .csv', 'err')
    throw new Error('格式不支持')
  }
  if (file.size > 30 * 1024 * 1024) {
    show(`${file.name} 超过 30MB`, 'err')
    throw new Error('文件过大')
  }
  sourceUploadCount.value += 1
  try {
    const meta = await api.upload(file, 'auto')
    // 同一个文件再次上传时后端会覆盖旧记录（不新增），这里再拦一道，避免列表出现两条一样的源表
    const existed = sourceFiles.value.find((item) => item.file_id === meta.file_id)
    if (existed) {
      // 后端会覆盖旧记录并重新解析，这里把新元数据换进列表，保证表头/行数是最新的
      Object.assign(existed, meta)
      show(`${meta.name} 已覆盖原有记录并重新解析，源表里只保留一份`)
      return
    }
    sourceFiles.value.push(meta)
    resetResult()
    // 源表变了，之前的方案作废；点「交给智能体规划」才会重新读表
    invalidatePlan()
    if (meta.duplicate) {
      show(`${meta.name} 与已有文件内容相同，已覆盖旧记录并重新解析`)
    } else {
      show(`${meta.name} 解析完成，识别到 ${(meta.columns || []).length} 个表头`)
    }
  } catch (err) {
    show(err.message, 'err')
    throw err
  } finally {
    sourceUploadCount.value -= 1
  }
}

// 模板只用来确定导出格式，单独以 target_template 角色登记，不参与源表处理
async function uploadTemplate(options) {
  templateUploadCount.value += 1
  try {
    const meta = await api.upload(options.file, 'target_template')
    const sameTemplate = templateFile.value && templateFile.value.file_id === meta.file_id
    templateFile.value = meta
    resetResult()
    // 模板换了，目标列也跟着换，旧映射作废，等用户点按钮重新规划
    if (sourceFiles.value.length) invalidatePlan()
    if (meta.duplicate) {
      show(`${meta.name} 和之前上传的模板内容相同，已覆盖旧模板记录并重新解析${sameTemplate ? '，模板内容没有变化' : ''}`)
    } else {
      show(`目标模板已解析，共 ${(meta.columns || []).length} 列`)
    }
  } catch (err) {
    show(err.message, 'err')
    throw err
  } finally {
    templateUploadCount.value -= 1
  }
}

function removeSource(fileId) {
  sourceFiles.value = sourceFiles.value.filter((item) => item.file_id !== fileId)
  resetResult()
  if (sourceFiles.value.length) {
    invalidatePlan()
  } else {
    intent.value = null
    mappings.value = []
    analyzeError.value = ''
    planStale.value = false
    resetAgent()
  }
}

function removeTemplate() {
  templateFile.value = null
  intent.value = null
  mappings.value = []
  analyzeError.value = ''
  planStale.value = false
  resetAgent()
  resetResult()
}

/* ============ 区块 2：字段映射 ============ */

/**
 * 文件或业务口径变了，旧方案就不再对应当前输入。
 * 这里只作废，不自动重跑：什么时候发给智能体，由用户点按钮决定。
 */
function invalidatePlan() {
  intent.value = null
  mappings.value = []
  mappingConfirmed.value = false
  analyzeError.value = ''
  planStale.value = false
  resetAgent()
}


// 后端对接点位：POST /api/agent/plan（智能体工具链：读表 → 识别 → 查记忆 → 校验 → 自修 → 交人）
async function analyzeNow(silent = false) {
  if (!sourceFiles.value.length || !templateReady.value) {
    intent.value = null
    mappings.value = []
    resetAgent()
    return
  }
  const seq = ++analyzeSeq
  analyzing.value = true
  startPlanProgress()
  // 重新识别意味着映射会重排，之前的确认自动失效
  mappingConfirmed.value = false
  const payload = {
    brief: brief.value.trim(),
    files: sourceRefs(),
    template_file_id: templateFileId.value
  }
  try {
    const plan = await api.agentPlan(payload)
    if (seq !== analyzeSeq) return
    applyAgentPlan(plan)
    if (!silent) {
      const stepText = agentSteps.value.length ? `，智能体执行 ${agentSteps.value.length} 步` : ''
      show(`已识别：${intent.value?.scene_name || '当前场景'}${stepText}`)
    }
  } catch (err) {
    if (seq !== analyzeSeq) return
    // 智能体编排接口不可用时退回原来的解析接口，页面主流程不中断
    try {
      const data = await api.analyze(payload)
      if (seq !== analyzeSeq) return
      resetAgent()
      agentFallback.value = true
      planStale.value = false
      intent.value = data
      analyzeError.value = ''
      applySuggestedMappings(data.mapping || [])
      if (!silent) show(`已识别：${data.scene_name}（智能体轨迹接口暂不可用）`, 'err')
    } catch (fallbackErr) {
      if (seq !== analyzeSeq) return
      intent.value = null
      mappings.value = []
      resetAgent()
      analyzeError.value = fallbackErr.message
      if (!silent) show(fallbackErr.message, 'err')
    }
  } finally {
    if (seq === analyzeSeq) {
      analyzing.value = false
      stopPlanProgress()
    }
  }
}

// 交接阶段的主动作：已有方案就直接核对，没有才让智能体规划
async function startPlanning() {
  if (!sourceFiles.value.length || !templateReady.value) {
    show(stepHint.value || '先把源表和目标模板交给智能体', 'err')
    return
  }
  // 只有用户点了这个按钮才把表发给智能体：文件或口径改过就重跑，否则直接进核对
  if (!intent.value || planStale.value) {
    await analyzeNow(true)
  }
  if (intent.value) {
    const scene = intent.value.scene_name || '当前场景'
    const cols = targetColumns.value.length
    const mapped = mappedCount.value
    const steps = agentSteps.value.length
    show(
      `规划成功：${scene} · 已映射 ${mapped}/${cols} 列` + (steps ? ` · 智能体执行 ${steps} 步` : '') + '，已进入核对方案'
    )
    step.value = 2
    reached.value = Math.max(reached.value, 2)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } else {
    show(analyzeError.value || '智能体没有给出可用的方案，换份表或补充一句业务口径再试', 'err')
  }
}

/**
 * 智能体的返回结构和原 /analyze 只差一段轨迹：
 * intent 仍然是前端熟悉的字段，这里只多做“把轨迹和经验也存下来”这一步。
 */
function applyAgentPlan(plan) {
  const data = plan?.intent || plan || {}
  resetAgent()
  intent.value = data
  analyzeError.value = ''
  agentSteps.value = Array.isArray(plan?.steps) ? plan.steps : []
  agentFixes.value = Array.isArray(plan?.fixes) ? plan.fixes : []
  agentHandoff.value = Array.isArray(plan?.handoff) ? plan.handoff : []
  agentExperienceLabel.value = plan?.experience_label || ''
  agentMode.value = plan?.mode || (data.parser === 'rules+langchain4j' ? 'llm' : 'rules')
  agentSummary.value = plan?.summary || ''
  agentFallback.value = !!plan?.fallback
  agentTokens.value = (plan?.tokens_in || 0) + (plan?.tokens_out || 0)
  agentModelCalls.value = plan?.model_calls || 0
  planStale.value = false
  // 命中过历史经验，刷新一下记忆库计数，方便评审看到“越用越熟”
  if (plan?.experience_id) loadAgentExperience()
  applySuggestedMappings(data.mapping || [])
}

/**
 * 重新推荐时保留用户手工改过的行，避免一解析就把人工映射冲掉。
 */
function applySuggestedMappings(list) {
  const manual = new Map(mappings.value.filter((row) => row.manual).map((row) => [row.target, row]))
  const next = list.map((item) => {
    if (manual.has(item.target)) {
      const kept = manual.get(item.target)
      manual.delete(item.target)
      return { ...kept, required: item.required }
    }
    return {
      target: item.target,
      required: item.required,
      file_id: item.file_id || '',
      column: item.column || '',
      expr: item.expression || '',
      confidence: item.confidence || 0,
      origin: item.origin || 'rules',
      manual: false
    }
  })
  // 用户自己补的目标字段不在推荐列表里，也要留下
  manual.forEach((row) => next.push(row))
  mappings.value = next
  mappingConfirmed.value = false
}

function onFilePick(row) {
  row.column = ''
  row.manual = true
  resetResult()
}

function onColumnPick(row) {
  row.manual = true
  resetResult()
}

function removeMapping(index) {
  mappings.value.splice(index, 1)
  resetResult()
}

function addMapping() {
  const draft = mappingDraft.value
  const target = draft.target.trim()
  if (!target) {
    show('先选目标字段', 'err')
    return
  }
  if (!draft.file_id || !draft.column) {
    show('再选来源文件和源字段', 'err')
    return
  }
  const exists = mappings.value.find((row) => row.target === target)
  if (exists) {
    exists.file_id = draft.file_id
    exists.column = draft.column
    exists.manual = true
  } else {
    mappings.value.push({
      target,
      required: !!requiredTargets.value[target],
      file_id: draft.file_id,
      column: draft.column,
      confidence: 1,
      manual: true
    })
  }
  mappingDraft.value = { target: '', file_id: '', column: '' }
  resetResult()
  show(`已把「${draft.column}」映射到「${target}」`)
}

function resetDraftFile(fileId) {
  // 换文件时把已选字段清掉，避免出现“文件里没有这个字段”的组合
  mappingDraft.value.column = ''
}

/* ---------------- 拼接 / 派生字段编辑器 ---------------- */
const exprDialog = ref({ visible: false, target: '', fileId: '', fileIndex: -1, text: '' })

const exprDialogError = computed(() =>
  exprDialog.value.visible ? exprErrorOf(exprDialog.value.text, exprDialog.value.fileId) : ''
)

const exprDialogPreview = computed(() =>
  exprDialog.value.visible && !exprDialogError.value
    ? exprPreviewOf(exprDialog.value.text, exprDialog.value.fileId)
    : ''
)

function openExprDialog(row) {
  if (!sourceFiles.value.length) {
    show('先上传源数据表', 'err')
    return
  }
  const index = mappings.value.findIndex((item) => item.target === row.target)
  exprDialog.value = {
    visible: true,
    target: row.target,
    fileId: row.file_id || sourceFiles.value[0].file_id,
    fileIndex: index,
    text: row.expr || ''
  }
}

function insertPlaceholder(column) {
  const current = exprDialog.value.text
  exprDialog.value.text = current && !current.endsWith(' ')
    ? `${current} {${column}}`
    : `${current}{${column}}`
}

function applyExpr() {
  const dialog = exprDialog.value
  if (exprDialogError.value) {
    show(exprDialogError.value, 'err')
    return
  }
  const text = dialog.text.trim()
  const row = mappings.value[dialog.fileIndex]
  if (!row) {
    dialog.visible = false
    return
  }
  row.expr = text
  row.file_id = dialog.fileId
  // 有表达式时不再依赖单个源列，清掉避免两套取值规则打架
  row.column = ''
  row.manual = true
  row.origin = 'manual'
  dialog.visible = false
  resetResult()
  show(`已给「${dialog.target}」配置拼接规则：${text}`)
}

function clearExpr() {
  const row = mappings.value[exprDialog.value.fileIndex]
  if (row) {
    row.expr = ''
    row.manual = true
    row.origin = 'manual'
    resetResult()
  }
  exprDialog.value.visible = false
  show('已清除拼接规则，请重新选择源字段')
}

/* ---------------- 映射方案（服务端保存，浏览器本地兜底） ---------------- */

const LOCAL_SCHEME_KEY = 'huibiao.mapping.schemes'

function readLocalSchemes() {
  try {
    const raw = localStorage.getItem(LOCAL_SCHEME_KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch (err) {
    return []
  }
}

function writeLocalSchemes() {
  try {
    localStorage.setItem(LOCAL_SCHEME_KEY, JSON.stringify(schemes.value))
  } catch (err) {
    // 本地只是兜底，写不进去不影响主流程
  }
}

function normalizeScheme(item) {
  return {
    id: item.id || `local-${item.name}`,
    name: item.name,
    scene: item.scene || '',
    template_name: item.template_name || item.templateName || '',
    saved_at: item.saved_at || item.savedAt || '',
    mapping: (item.mapping || []).map((row) => ({
      target: row.target,
      file: row.file_name || row.fileName || row.file || '',
      column: row.column,
      expr: row.expr || ''
    }))
  }
}

async function loadSchemes() {
  try {
    const list = await api.mappingSchemes()
    schemes.value = (list || []).map(normalizeScheme)
  } catch (err) {
    // 后端不可用时用本机缓存，至少保证同一个浏览器里能继续演示
    schemes.value = readLocalSchemes().map(normalizeScheme)
  }
}

function fileNameOf(fileId) {
  const file = sourceFiles.value.find((item) => item.file_id === fileId)
  return file ? file.name : ''
}

async function saveScheme() {
  const rows = mappings.value.filter(mappingValid)
  if (!rows.length) {
    show('还没有可保存的映射', 'err')
    return
  }
  const name = schemeName.value.trim() || `${intent.value?.scene_name || '映射'}方案`
  const payload = {
    name,
    scene: intent.value?.scene || '',
    template_name: templateLabel.value,
    mapping: rows.map((row) => ({
      target: row.target,
      file_name: fileNameOf(row.file_id),
      column: row.column || '',
      expr: row.expr || ''
    }))
  }
  try {
    const saved = await api.saveMappingScheme(payload)
    schemeName.value = saved.name
    await loadSchemes()
    activeScheme.value = saved.id
    show(`映射方案「${saved.name}」已保存到服务器`)
  } catch (err) {
    schemes.value = [
      normalizeScheme({
        id: `local-${Date.now()}`,
        name,
        scene: payload.scene,
        template_name: payload.template_name,
        saved_at: new Date().toLocaleString('zh-CN'),
        mapping: payload.mapping
      }),
      ...schemes.value.filter((item) => item.name !== name)
    ].slice(0, 20)
    writeLocalSchemes()
    activeScheme.value = schemes.value[0].id
    show(`服务器没响应，方案「${name}」暂存在本机浏览器`, 'err')
  }
}

function loadScheme(id) {
  const scheme = schemes.value.find((item) => item.id === id)
  if (!scheme) return
  let skipped = 0
  ;(scheme.mapping || []).forEach((item) => {
    let file = sourceFiles.value.find((f) => f.name === item.file)
    // 文件改过名字时，退一步按“哪个源表里有这列 / 这些列”来找
    if (!file && item.column) file = sourceFiles.value.find((f) => (f.columns || []).includes(item.column))
    if (!file && item.expr) {
      const used = exprPlaceholders(item.expr)
      file = sourceFiles.value.find((f) => used.every((name) => (f.columns || []).includes(name)))
    }
    const fileId = file ? file.file_id : ''
    if (!fileId) skipped += 1
    const exists = mappings.value.find((row) => row.target === item.target)
    if (exists) {
      exists.file_id = fileId
      exists.column = item.column || ''
      exists.expr = item.expr || ''
      exists.manual = true
      exists.origin = 'scheme'
    } else {
      mappings.value.push({
        target: item.target,
        required: !!requiredTargets.value[item.target],
        file_id: fileId,
        column: item.column || '',
        expr: item.expr || '',
        confidence: 1,
        origin: 'scheme',
        manual: true
      })
    }
  })
  mappingConfirmed.value = false
  resetResult()
  show(
    skipped
      ? `已加载方案「${scheme.name}」，${skipped} 条源文件对不上，请手动补`
      : `已加载方案「${scheme.name}」，请核对后确认`
  )
}

async function removeScheme() {
  if (!activeScheme.value) return
  const scheme = schemes.value.find((item) => item.id === activeScheme.value)
  if (!scheme) return
  if (String(scheme.id).startsWith('local-')) {
    schemes.value = schemes.value.filter((item) => item.id !== scheme.id)
    writeLocalSchemes()
  } else {
    try {
      await api.deleteMappingScheme(scheme.id)
      await loadSchemes()
    } catch (err) {
      show(err.message, 'err')
      return
    }
  }
  activeScheme.value = ''
  show(`已删除方案「${scheme.name}」`)
}

/* 把这一页的智能体进度同步到左侧导航栏；离开这一页就收起，不把上一单的状态带出去 */
watch(
  () => [
    agentStateText.value,
    agentStateTone.value,
    agentSteps.value.length,
    agentRailTools.value,
    agentExperiences.value.length
  ],
  () => {
    railState.value = agentStateText.value
    railTone.value = agentStateTone.value
    railSteps.value = agentSteps.value.length
    railTools.value = agentRailTools.value
    railMemory.value = agentExperiences.value.length
  },
  { immediate: true, deep: true }
)

// 导航栏里点「管理」→ 打开这一页的经验记忆弹窗
watch(railMemoryRequest, (n) => {
  if (n) memoryVisible.value = true
})

/* ---------- 断点续看：刷新 / 直接输地址后，回到工作台还能接着看 ----------
 * 站内切页已经由 App.vue 的 keep-alive 保住内存状态；这里再把“现场”写进
 * sessionStorage，覆盖 F5 刷新、开发时热更新这几种情况。
 * 只存元数据、规划和任务 ID，结果行不存（结果表按 task_id 从后端分页拉）。
 */
const STORE_KEY = 'huibiao.workbench.v1'
const STORE_TTL = 12 * 60 * 60 * 1000
let saveTimer = null

function saveSnapshot() {
  try {
    sessionStorage.setItem(STORE_KEY, JSON.stringify({
      at: Date.now(),
      sourceFiles: sourceFiles.value,
      templateFile: templateFile.value,
      brief: brief.value,
      isolate: isolate.value,
      strictFormat: strictFormat.value,
      intent: intent.value,
      mappings: mappings.value,
      mappingConfirmed: mappingConfirmed.value,
      planStale: planStale.value,
      agentSteps: agentSteps.value,
      agentFixes: agentFixes.value,
      agentHandoff: agentHandoff.value,
      agentExperienceLabel: agentExperienceLabel.value,
      agentMode: agentMode.value,
      agentFallback: agentFallback.value,
      agentSummary: agentSummary.value,
      taskId: task.value?.id || '',
      step: step.value,
      reached: reached.value,
      resultTab: resultTab.value
    }))
  } catch (e) {
    // 隐私模式或存储被禁用时静默跳过，不影响正常使用
  }
}

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(saveSnapshot, 300)
}

function restoreSnapshot() {
  let saved = null
  try {
    saved = JSON.parse(sessionStorage.getItem(STORE_KEY) || 'null')
  } catch (e) {
    saved = null
  }
  if (!saved || !saved.at || Date.now() - saved.at > STORE_TTL) {
    sessionStorage.removeItem(STORE_KEY)
    return
  }
  sourceFiles.value = Array.isArray(saved.sourceFiles) ? saved.sourceFiles : []
  templateFile.value = saved.templateFile || null
  brief.value = saved.brief || ''
  isolate.value = saved.isolate !== false
  strictFormat.value = !!saved.strictFormat
  intent.value = saved.intent || null
  mappings.value = Array.isArray(saved.mappings) ? saved.mappings : []
  mappingConfirmed.value = !!saved.mappingConfirmed
  planStale.value = !!saved.planStale
  agentSteps.value = saved.agentSteps || []
  agentFixes.value = saved.agentFixes || []
  agentHandoff.value = saved.agentHandoff || []
  agentExperienceLabel.value = saved.agentExperienceLabel || ''
  agentMode.value = saved.agentMode || 'rules'
  agentFallback.value = !!saved.agentFallback
  agentSummary.value = saved.agentSummary || ''
  resultTab.value = saved.resultTab || 'ok'
  // reached 必须先于 step 恢复，否则 maxStep 的 watch 会把 step 压回第 1 步
  reached.value = Math.min(3, Math.max(1, Number(saved.reached) || 1))
  step.value = Math.min(reached.value, Math.max(1, Number(saved.step) || 1))
  if (saved.taskId) {
    api.task(saved.taskId)
      .then((data) => {
        task.value = data
        if (['queued', 'running'].includes(data.status)) poll(data.id)
      })
      .catch(() => {
        // 任务已被清理（换机器、后端数据目录重置）时当作没有现场，不打扰用户
      })
  }
}

watch(
  () => [
    sourceFiles.value, templateFile.value, brief.value, isolate.value, strictFormat.value,
    intent.value, mappings.value, mappingConfirmed.value, planStale.value,
    agentSteps.value, agentFixes.value, agentHandoff.value, agentSummary.value,
    task.value?.id, step.value, reached.value, resultTab.value
  ],
  scheduleSave,
  { deep: true }
)

onMounted(() => {
  restoreSnapshot()
  mountTopbar()
  // 智能体模式不需要“待交接/等待核对”这条状态轨，右侧留给区块本身
  railVisible.value = workspaceMode.value !== 'agent'
  loadSchemes()
  loadAgentExperience()
})

// 被 keep-alive 缓存后，离开页面只会触发 deactivated（不会卸载），
// 所以要在这里把顶栏和右侧状态轨交还出去，回来时再重新挂上，
// 中间的轮询继续跑，任务跑完了切回来能直接看到结果。
onActivated(() => {
  mountTopbar()
  railVisible.value = workspaceMode.value !== 'agent'
})

onDeactivated(() => {
  // 切走前把最新的现场落一次盘，别等 300ms 的防抖
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  saveSnapshot()
  clearTopbar()
  railVisible.value = false
})

/* ============ 区块 3：业务规则 ============ */

function applyQuickRule(rule) {
  brief.value = rule.text
  resetResult()
  // 只把口径填进去，发不发由用户点「交给智能体规划」决定
  planStale.value = !!intent.value
}

function onBriefInput() {
  resetResult()
  // 改了口径不再自动重跑智能体，旧方案标为过期，等用户确认后再规划
  planStale.value = !!intent.value
}

/* ============ 区块 4：执行 ============ */

function configPayload() {
  return {
    scene: intent.value?.scene,
    template_file_id: templateFileId.value,
    isolate_anomaly: isolate.value,
    strict_format: strictFormat.value,
    mapping: mappings.value
      .filter(mappingValid)
      .map((row) => ({
        target: row.target,
        file_id: row.file_id,
        column: row.column || '',
        expr: row.expr || ''
      }))
  }
}

// 后端对接点位：POST /api/tasks 创建任务，任务在后端异步执行
async function run() {
  if (!canRun.value) {
    show(runHints.value[0] || '配置还没完成', 'err')
    return
  }
  stopPolling()
  running.value = true
  runError.value = ''
  editError.value = ''
  editInput.value = ''
  task.value = null
  resultTab.value = 'ok'
  // 点执行就切到交付阶段，进度和结果都在同一屏
  step.value = 3
  reached.value = 3
  try {
    const created = await api.createTask({
      name: '',
      brief: brief.value.trim(),
      files: sourceRefs(),
      config: configPayload()
    })
    task.value = created
    await poll(created.id)
  } catch (err) {
    runError.value = err.message
  } finally {
    running.value = false
  }
}

// 后端对接点位：GET /api/tasks/{id} 轮询进度
function poll(id) {
  return new Promise((resolve) => {
    const tick = async () => {
      try {
        const data = await api.task(id)
        task.value = data
        if (['queued', 'running'].includes(data.status)) {
          pollTimer = setTimeout(tick, 1200)
        } else {
          pollTimer = null
          // 这次跑完智能体可能刚学了一条经验，把记忆库计数刷新一下
          if (data.output_rows > 0) loadAgentExperience()
          resolve()
        }
      } catch (err) {
        runError.value = err.message
        pollTimer = null
        resolve()
      }
    }
    tick()
  })
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

/* ============ 区块 5：下载 ============ */

function downloadResult() {
  if (!resultUrl.value) return
  const link = document.createElement('a')
  link.href = resultUrl.value
  link.download = ''
  document.body.appendChild(link)
  link.click()
  link.remove()
}

/**
 * 异常日志按前端生成 csv，方便业务直接丢进 Excel 复核；
 * 后端另外还会产出一份异常日志 xlsx，可在任务详情里下载。
 */
function downloadAnomaliesCsv() {
  const rows = anomalyRows.value
  if (!rows.length) return
  const escape = (value) => `"${String(value ?? '').replace(/"/g, '""')}"`
  const header = ['源文件', '行号', '异常类型', '原因', '原始内容']
  const body = rows.map((item) => [item.source, item.row, item.type, item.reason, item.raw])
  const csv = '\ufeff' + [header, ...body].map((line) => line.map(escape).join(',')).join('\r\n')

  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `异常复核_${task.value?.id || '任务'}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/**
 * 一句话改结果：后端负责把话翻译成操作（改值 / 加减 / 删行 / 排序 / 加列），
 * 再按原源表重算、重放、重新导出，页面只负责把结果和修改记录展示出来。
 */
// 点「试试」里的句子只是填词，真正的提交交给「让智能体改」或回车，
// 避免点一下就把请求发给大模型、用户还不知道改了什么。
function useEditSample(text) {
  if (editBusy.value) return
  editInput.value = text
  nextTick(() => editInputRef.value?.focus?.())
}

async function submitEdit(text) {
  const instruction = String(text ?? editInput.value).trim()
  if (!instruction) {
    show('先用一句话说清楚要改什么，比如「第 3 行起始时间改成 09:00」', 'err')
    return
  }
  if (!task.value || editBusy.value || running.value) {
    show('等这一单跑完再改结果', 'err')
    return
  }
  const id = task.value.id
  stopPolling()
  editBusy.value = true
  editError.value = ''
  try {
    const res = await api.reviseTask(id, instruction)
    if (res.task) task.value = res.task
    editInput.value = ''
    step.value = 3
    reached.value = 3
    show(res.revision?.summary ? `已按你的话改结果：${res.revision.summary}` : '已按你的话改结果，正在重算')
    await poll(id)
  } catch (err) {
    editError.value = err.message
    show(err.message, 'err')
  } finally {
    editBusy.value = false
  }
}

async function undoEdit() {
  if (!task.value || editBusy.value) return
  const id = task.value.id
  stopPolling()
  editBusy.value = true
  editError.value = ''
  try {
    task.value = await api.undoTaskEdit(id)
    show('已撤销最近一条修改，正在按原结果重算')
    await poll(id)
  } catch (err) {
    editError.value = err.message
    show(err.message, 'err')
  } finally {
    editBusy.value = false
  }
}

onBeforeUnmount(() => {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  saveSnapshot()
  stopPolling()
  stopPlanProgress()
  clearTopbar()
  railVisible.value = false
})
</script>

<template>
  <div class="page agentdesk">
    <div v-if="apiState.online === false" class="banner banner-err">
      后端没有连上，文件解析和任务执行都不可用。先在 backend 目录运行 start.cmd，再刷新页面。
    </div>

    <!-- 模式切换在顶栏右侧；窄屏顶栏是隐藏的，这里兜个底 -->
    <div class="ad-mode-line">
      <button
        class="ad-mode-pill"
        :class="{ 'is-on': workspaceMode === 'standard' }"
        type="button"
        @click="switchMode('standard')"
      >
        标准模式
      </button>
      <button
        class="ad-mode-pill"
        :class="{ 'is-on': workspaceMode === 'agent' }"
        type="button"
        @click="switchMode('agent')"
      >
        智能体模式<b class="ad-core">核心</b>
      </button>
    </div>

    <BridgeAgent v-if="workspaceMode === 'agent'" />

    <div v-if="workspaceMode === 'standard'" class="ad-layout">
      <div class="ad-work">
        <!-- 阶段条：3 段走完一单 -->
        <ol class="ad-stages">
          <li
            v-for="item in stepList"
            :key="item.no"
            class="ad-stage"
            :class="{
              'is-current': step === item.no,
              'is-done': item.no < reached && step !== item.no,
              'is-locked': item.no > maxStep
            }"
            @click="goStep(item.no)"
          >
            <span class="ad-stage-no">
              <Icon v-if="item.no < reached && step !== item.no" name="check" :size="12" :stroke="2.8" />
              <template v-else>{{ item.no }}</template>
            </span>
            <span class="ad-stage-text">
              <b>{{ item.title }}</b>
              <em>{{ item.sub }}</em>
            </span>
          </li>
        </ol>

        <!-- ================= 阶段 1：交接 ================= -->
        <section v-show="step === 1" class="ad-card ad-handoff">
          <header class="ad-card-head">
            <div>
              <span class="ad-kicker">01 · 交接</span>
              <h2>把这一单交给智能体</h2>
              <p>源表和目标模板你来给，读表、理解口径、写映射由它完成。</p>
            </div>
            <div class="ad-head-tools">
              <span class="ad-head-state" :class="`is-${agentStateTone}`">
                <i />{{ agentStateText }}
              </span>
              <!-- 现场会跨刷新保留，所以给一个明确的“重开一单”出口 -->
              <button
                v-if="sourceFiles.length || task"
                class="ad-ghost"
                title="清空当前文件、规划和结果，重新交接一单"
                @click="resetAll"
              >
                清空重来
              </button>
            </div>
          </header>

          <div class="ad-card-body">
            <div class="ad-slot-grid">
              <el-upload
                class="ad-drop"
                drag
                multiple
                :show-file-list="false"
                :http-request="uploadSource"
                accept=".xlsx,.xls,.csv"
                :disabled="sourceUploading"
              >
                <div class="ad-drop-inner">
                  <span class="ad-drop-icon"><Icon name="upload" :size="19" /></span>
                  <b>{{ sourceUploading ? '正在读表…' : '源数据表' }}</b>
                  <span>拖入或点击选择，可多选 · .xlsx / .xls / .csv</span>
                </div>
              </el-upload>

              <el-upload
                v-if="!templateFile"
                class="ad-drop ad-drop-tpl"
                drag
                :show-file-list="false"
                :http-request="uploadTemplate"
                accept=".xlsx,.xls,.csv"
                :disabled="templateUploading"
              >
                <div class="ad-drop-inner">
                  <span class="ad-drop-icon"><Icon name="file" :size="18" /></span>
                  <b>{{ templateUploading ? '正在解析模板…' : '目标模板' }}</b>
                  <span>决定导出列与顺序，单个文件</span>
                </div>
              </el-upload>

              <div v-else class="ad-tpl-card">
                <div class="ad-tpl-head">
                  <span class="ad-drop-icon"><Icon name="file" :size="17" /></span>
                  <div>
                    <b>{{ templateFile.name }}</b>
                    <span>{{ (templateFile.columns || []).length }} 列 · {{ sizeText(templateFile.size) }}</span>
                  </div>
                  <button class="ad-icon-x" title="移除模板" @click="removeTemplate">
                    <Icon name="close" :size="13" />
                  </button>
                </div>
                <div class="ad-tpl-cols">
                  <span v-for="column in templateFile.columns" :key="column">{{ column }}</span>
                </div>
                <p class="ad-tpl-note">导出文件的列名和顺序完全跟随这个模板</p>
              </div>
            </div>

            <div v-if="sourceFiles.length" class="ad-sources">
              <div v-for="file in sourceFiles" :key="file.file_id" class="ad-source">
                <div class="ad-source-head">
                  <Icon name="table" :size="14" />
                  <b>{{ file.name }}</b>
                  <span>{{ rowCount(file) }} 行 · {{ (file.columns || []).length }} 列</span>
                  <button class="ad-icon-x" title="移除" @click="removeSource(file.file_id)">
                    <Icon name="close" :size="13" />
                  </button>
                </div>
                <div class="ad-source-chips">
                  <span v-for="column in file.columns" :key="column">{{ column }}</span>
                </div>
              </div>
            </div>

            <!-- 对话式规则：和智能体说一句业务口径，不必填表 -->
            <div class="ad-composer">
              <span class="ad-composer-line" aria-hidden="true"></span>
              <div class="ad-composer-body">
                <div class="ad-composer-head">
                  <b>这次要怎么算？</b>
                  <span>可留空，智能体按表头自行推断</span>
                </div>
                <el-input
                  v-model="brief"
                  type="textarea"
                  :rows="3"
                  resize="none"
                  placeholder="例如：把 OA 请假单和年假余额表按工号关联，先扣年假，不足部分转事假，按 HR 缺勤模板导出，工号对不上的行记异常。"
                  @input="onBriefInput"
                />
                <div class="ad-chips">
                  <button v-for="rule in quickRules" :key="rule.key" class="ad-chip" @click="applyQuickRule(rule)">
                    {{ rule.label }}
                  </button>
                </div>
                <div class="ad-composer-foot">
                  <span class="ad-muted">
                    {{ sourceFiles.length }} 张源表 · {{ templateReady ? '模板已就位' : '待上传模板' }}
                  </span>
                  <button
                    class="ad-primary"
                    :disabled="!sourceFiles.length || !templateReady || analyzing"
                    @click="startPlanning"
                  >
                    <Icon name="spark" :size="14" />
                    {{
                      analyzing
                        ? '智能体规划中…'
                        : planStale && intent
                          ? '重新规划'
                          : intent
                            ? '进入核对方案'
                            : '交给智能体规划'
                    }}
                  </button>
                </div>
              </div>
            </div>

            <!-- 规划过程：后端一次性返回，这里用阶段文案 + 渐进进度条 + 已等秒数让等待可感知 -->
            <div v-if="analyzing" class="ad-plan-progress">
              <div class="ad-pp-head">
                <span class="ad-pp-spin"><Icon name="refresh" :size="12" /></span>
                <b>智能体正在规划</b>
                <span class="ad-pp-stage">{{ planStageText }}</span>
                <span class="ad-pp-time">已等 {{ Math.round(planElapsed) }}s</span>
              </div>
              <div class="ad-pp-track">
                <i :style="{ width: `${planPercent}%` }"></i>
              </div>
            </div>

            <!-- 规划完成后的常驻提示：toast 一闪而过，这里留一条可回看的成功状态 -->
            <div v-if="intent && !analyzing" class="ad-plan-done" :class="{ 'is-stale': planStale }">
              <Icon :name="planStale ? 'alert' : 'check'" :size="14" />
              <span v-if="planStale">
                业务口径已修改，当前方案还是旧口径的结果；确认无误后点右侧按钮重新规划
              </span>
              <span v-else>
                方案已生成：{{ intent.scene_name || '当前场景' }} · 已映射
                <b>{{ mappedCount }}/{{ targetColumns.length }}</b> 列<template v-if="agentSteps.length">
                  · 智能体执行 <b>{{ agentSteps.length }}</b> 步</template>
              </span>
              <button class="ad-link" @click="startPlanning">
                {{ planStale ? '重新规划 →' : '进入核对方案 →' }}
              </button>
            </div>

            <div class="ad-advanced">
              <button class="ad-link" @click="advancedOpen = !advancedOpen">
                <Icon :name="advancedOpen ? 'down' : 'right'" :size="12" />
                高级开关
                <span>脏数据隔离 · 严格格式校验</span>
              </button>
              <div v-show="advancedOpen" class="ad-advanced-body">
                <label class="ad-switch">
                  <el-switch v-model="isolate" @change="resetResult" />
                  <span>
                    <b>脏数据隔离</b>
                    <em>校验失败的行单独进异常日志，正常行照常输出</em>
                  </span>
                </label>
                <label class="ad-switch">
                  <el-switch v-model="strictFormat" />
                  <span>
                    <b>严格格式校验</b>
                    <em>必填列没映射齐就不允许执行，避免被 HR 系统退回</em>
                  </span>
                </label>
              </div>
            </div>
          </div>
        </section>


        <!-- ================= 阶段 2：核对 ================= -->
        <section v-show="step === 2" class="ad-card ad-plan">
          <header class="ad-card-head">
            <div>
              <span class="ad-kicker">02 · 核对</span>
              <h2>智能体给出的方案</h2>
              <p>{{ planHeadline }}</p>
            </div>
            <div class="ad-head-tags">
              <span class="ad-badge" :class="agentMode === 'llm' ? 'is-ai' : 'is-rule'">{{ agentModeLabel }}</span>
              <span v-if="agentExperienceLabel" class="ad-badge is-mem">记忆命中</span>
              <span v-if="agentFallback" class="ad-badge is-warn">基础识别兜底</span>
            </div>
          </header>

          <div class="ad-card-body">
            <div v-if="!sourceFiles.length || !templateReady" class="ad-empty">
              先把源表和目标模板交上来，智能体才出得了方案。
            </div>

            <template v-else>
              <div class="ad-tiles">
                <div v-for="tile in planTiles" :key="tile.key" class="ad-tile">
                  <span>{{ tile.label }}</span>
                  <b>{{ tile.value }}<em>{{ tile.unit }}</em></b>
                  <small>{{ tile.note }}</small>
                </div>
              </div>

              <!-- 自动补全 / 交人清单 / 处理提示 -->
              <div v-if="agentFixes.length || agentHandoff.length || intentNotes.length" class="ad-decision">
                <div v-if="agentFixes.length" class="ad-decision-col is-fix">
                  <b>它自己补了 {{ agentFixes.length }} 处</b>
                  <ul>
                    <li v-for="text in agentFixes" :key="text">{{ text }}</li>
                  </ul>
                </div>
                <div v-if="agentHandoff.length" class="ad-decision-col is-human">
                  <b>需要你拍板</b>
                  <ul>
                    <li v-for="text in agentHandoff" :key="text">{{ text }}</li>
                  </ul>
                </div>
                <div v-if="intentNotes.length" class="ad-decision-col">
                  <b>处理提示</b>
                  <ul>
                    <li v-for="note in intentNotes" :key="note">{{ note }}</li>
                  </ul>
                </div>
              </div>

              <!-- 智能体轨迹：用了什么工具、依据什么、结论是什么 -->
              <div class="ad-trace">
                <button class="ad-trace-head" type="button" @click="agentCollapsed = !agentCollapsed">
                  <span class="ad-trace-title">
                    <Icon name="terminal" :size="14" />
                    <b>智能体执行轨迹</b>
                    <em>{{ agentToolCount }} 个工具 · {{ agentSteps.length }} 步 · {{ agentElapsedText }}</em>
                    <em v-if="agentTokenText" class="ad-trace-token">{{ agentTokenText }}</em>
                  </span>
                  <span class="ad-link">{{ agentCollapsed ? '展开' : '收起' }}</span>
                </button>
                <p v-if="agentSummary" class="ad-trace-summary">{{ agentSummary }}</p>
                <ol v-show="!agentCollapsed" class="ad-trace-list">
                  <li v-for="item in agentSteps" :key="item.index" class="ad-trace-step" :class="`is-${item.status || 'ok'}`">
                    <span class="ad-trace-dot">{{ item.index }}</span>
                    <div class="ad-trace-main">
                      <div class="ad-trace-line">
                        <b>{{ item.title }}</b>
                        <code>{{ item.tool }}</code>
                        <em v-if="item.elapsed_ms">{{ agentStepTime(item.elapsed_ms) }}</em>
                        <span>{{ agentStatusLabel(item.status) }}</span>
                      </div>
                      <p v-if="item.detail">{{ item.detail }}</p>
                      <ul v-if="item.items && item.items.length">
                        <li v-for="text in item.items" :key="text">{{ text }}</li>
                      </ul>
                    </div>
                  </li>
                </ol>
                <p v-if="!agentSteps.length" class="ad-trace-empty">
                  {{ analyzeError || '智能体还没有产出轨迹' }}
                </p>
              </div>

              <!-- 映射：人工闸门 -->
              <div class="ad-map-head">
                <div class="ad-map-count">
                  <span class="ad-kicker">字段映射</span>
                  <b>{{ mappedCount }} / {{ editableTargets.length }} 列已映射</b>
                  <!-- 说清“来源”：AI 一条都没采纳时明确写出来，避免被误读成 AI 没跑 -->
                  <span class="ad-map-src" :class="{ 'is-rule': !aiMappingCount }">
                    {{ aiMappingCount ? `AI 采纳 ${aiMappingCount} 列` : '本次由规则匹配，AI 未给出可用映射' }}
                  </span>
                  <em v-if="missingRequired">还有 {{ missingRequired }} 个必填未完成</em>
                </div>
                <div class="ad-map-actions">
                  <span class="ad-confirm-state" :class="{ ok: mappingConfirmed }">
                    {{ mappingConfirmed ? '已确认' : '待人工确认' }}
                  </span>
                  <button class="ad-ghost" :disabled="!sourceFiles.length || !templateReady || analyzing" @click="analyzeNow(false)">
                    重新规划
                  </button>
                  <button class="ad-primary" :disabled="!canConfirmMapping" @click="confirmMapping">
                    {{ mappingConfirmed ? '方案已确认' : '确认方案' }}
                  </button>
                </div>
              </div>

              <button class="ad-link ad-fields-toggle" type="button" @click="fieldsOpen = !fieldsOpen">
                <Icon :name="fieldsOpen ? 'down' : 'right'" :size="12" />
                查看源表字段（{{ totalSourceFields }}）
              </button>
              <div v-show="fieldsOpen" class="ad-fields">
                <div v-for="group in fieldGroups" :key="group.file_id" class="ad-field-group">
                  <b>{{ group.name }}</b>
                  <div class="ad-field-chips">
                    <span v-for="column in group.columns" :key="column">
                      {{ column }}<i>{{ sampleOfGroup(group, column) }}</i>
                    </span>
                  </div>
                </div>
              </div>

          <el-table :data="mappings" border size="small" max-height="360" class="nt-map-table">
            <el-table-column label="目标字段" min-width="120">
              <template #default="{ row }">
                <span class="nt-td-target">
                  {{ row.target }}<em v-if="row.required" class="nt-req">*</em>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="来源文件" min-width="150">
              <template #default="{ row }">
                <el-select
                  v-model="row.file_id"
                  size="small"
                  placeholder="选择文件"
                  clearable
                  @change="onFilePick(row)"
                >
                  <el-option v-for="file in sourceFiles" :key="file.file_id" :label="file.name" :value="file.file_id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="源字段 / 拼接规则" min-width="170">
              <template #default="{ row }">
                <div v-if="row.expr" class="nt-expr-cell">
                  <code class="nt-expr-text" :title="row.expr">{{ row.expr }}</code>
                  <el-button link type="primary" size="small" @click="openExprDialog(row)">编辑</el-button>
                </div>
                <el-select
                  v-else
                  v-model="row.column"
                  size="small"
                  placeholder="选择字段"
                  clearable
                  :disabled="!row.file_id"
                  @change="onColumnPick(row)"
                >
                  <el-option v-for="column in columnsOf(row.file_id)" :key="column" :label="column" :value="column" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="样例值" min-width="110">
              <template #default="{ row }">
                <span class="nt-sample">
                  {{ isFixedRow(row) ? FIXED_VALUES[row.target]
                    : row.expr ? exprPreviewOf(row.expr, row.file_id) : sampleOf(row) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="96">
              <template #default="{ row }">
                <el-tag v-if="isFixedRow(row)" size="small" type="success" effect="plain">固定值</el-tag>
                <el-tag v-else-if="row.origin === 'scheme'" size="small" type="success" effect="plain">
                  {{ row.expr ? '方案拼接' : '方案' }}
                </el-tag>
                <el-tag v-else-if="row.manual" size="small" type="info" effect="plain">
                  {{ row.expr ? '手动拼接' : '手动' }}
                </el-tag>
                <el-tag v-else-if="row.confidence" size="small" :type="row.origin === 'ai' ? 'success' : ''" effect="plain">
                  {{ row.origin === 'ai' ? (row.expr ? 'AI 拼接' : 'AI') : (row.expr ? '规则拼接' : '规则') }}
                  {{ Math.round(row.confidence * 100) }}%
                </el-tag>
                <span v-else class="nt-muted">待确认</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="118" align="center">
              <template #default="{ row, $index }">
                <el-button link type="primary" size="small" @click="openExprDialog(row)">拼接</el-button>
                <el-button link type="danger" size="small" @click="removeMapping($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="nt-add">
            <span class="nt-add-label">新增映射</span>
            <el-select v-model="mappingDraft.target" size="small" placeholder="目标字段" filterable clearable class="nt-w-sm">
              <el-option v-for="item in targetRows" :key="item.target" :label="item.target" :value="item.target" />
            </el-select>
            <el-select
              v-model="mappingDraft.file_id"
              size="small"
              placeholder="来源文件"
              clearable
              class="nt-w-md"
              @change="resetDraftFile(mappingDraft.file_id)"
            >
              <el-option v-for="file in sourceFiles" :key="file.file_id" :label="file.name" :value="file.file_id" />
            </el-select>
            <el-select
              v-model="mappingDraft.column"
              size="small"
              placeholder="源字段"
              clearable
              :disabled="!mappingDraft.file_id"
              class="nt-w-md"
            >
              <el-option v-for="column in columnsOf(mappingDraft.file_id)" :key="column" :label="column" :value="column" />
            </el-select>
            <el-button size="small" @click="addMapping">添加</el-button>
          </div>

          <div class="nt-expr-hint">
            源表列不够用时，点行尾的「拼接」：<code>{列名}</code> 取源表列，花括号外原样保留（如
            <code>{起始日期} {起始时间}</code>），常量直接写文字（如 <code>加班</code>），换算写
            <code>{天}*8</code>。日期和时刻分成两列时，用 <code>{起始日期} + {起始时间}</code>
            合并成标准日期时间（<code>2026-04-27 20:00</code>），AI 推荐里也会自动给出这类拼接，
            确认前可以改。
            下拉框 hover 时右侧出现「×」，可以把来源文件和源字段清空为该行的默认值。
          </div>

          <div class="nt-scheme">
            <span class="nt-add-label">映射方案</span>
            <el-input v-model="schemeName" size="small" placeholder="给方案起个名字" class="nt-w-sm" />
            <el-button size="small" @click="saveScheme">保存当前映射</el-button>
            <el-select
              v-model="activeScheme"
              size="small"
              placeholder="加载已保存方案"
              class="nt-w-sm"
              @change="loadScheme"
            >
              <el-option
                v-for="item in schemes"
                :key="item.id"
                :label="item.saved_at ? `${item.name}（${item.saved_at}）` : item.name"
                :value="item.id"
              />
            </el-select>
            <el-button v-if="activeScheme" size="small" @click="removeScheme">删除方案</el-button>
            <span class="nt-scheme-tip">方案保存在服务器（data/mapping-schemes.json），换浏览器或换同事也能加载。</span>
          </div>
        </template>
      </div>

      <!-- 拼接规则编辑器：源表没有可用字段时，用多列组合出一个目标值 -->
      <el-dialog
        v-model="exprDialog.visible"
        title="拼接 / 派生字段"
        width="560px"
        append-to-body
        class="nt-expr-dialog"
      >
        <div class="nt-expr-body">
          <p class="nt-expr-desc">
            给目标列「<strong>{{ exprDialog.target }}</strong>」配取值规则：<code>{列名}</code> 取源表列，
            花括号外原样保留，常量直接写文字，换算写 <code>{天}*8</code>。
          </p>
          <div class="nt-expr-files">
            <span class="nt-add-label">取数文件</span>
            <el-select v-model="exprDialog.fileId" size="small" class="nt-w-md">
              <el-option v-for="file in sourceFiles" :key="file.file_id" :label="file.name" :value="file.file_id" />
            </el-select>
          </div>
          <div class="nt-expr-cols">
            <span class="nt-add-label">插入源字段</span>
            <el-button
              v-for="column in columnsOfFile(exprDialog.fileId)"
              :key="column"
              size="small"
              plain
              @click="insertPlaceholder(column)"
            >
              {{ column }}
            </el-button>
          </div>
          <el-input
            v-model="exprDialog.text"
            type="textarea"
            :rows="3"
            placeholder='例如 {起始日期} {起始时间}、加班、{天}*8'
          />
          <div v-if="exprDialogError" class="nt-expr-error">{{ exprDialogError }}</div>
          <div v-else class="nt-expr-preview">样例结果：{{ exprDialogPreview || '（空）' }}</div>
        </div>
        <template #footer>
          <el-button size="small" @click="clearExpr">清除规则</el-button>
          <el-button size="small" @click="exprDialog.visible = false">取消</el-button>
          <el-button size="small" type="primary" :disabled="!!exprDialogError" @click="applyExpr">应用</el-button>
        </template>
      </el-dialog>

      <!-- 智能体的长期记忆：成功跑过的配置会留在这里，下次先复用 -->
      <el-dialog v-model="memoryVisible" title="智能体经验记忆" width="800px" append-to-body>
        <p class="nt-memory-desc">
          每跑成功一单，智能体会把「场景 + 目标模板 + 源表列 + 映射」记成一条经验。
          下次遇到同类表，先按经验补齐映射缺口；这里可以查看命中次数，也可以让智能体忘掉过期配置。
        </p>
        <el-table
          :data="agentExperiences"
          border
          size="small"
          max-height="360"
          @selection-change="(rows) => (memorySelection = rows)"
        >
          <el-table-column type="selection" width="44" align="center" />
          <el-table-column prop="scene" label="场景" width="96" />
          <el-table-column prop="template_name" label="目标模板" min-width="150" />
          <el-table-column label="复用次数" width="86" align="center">
            <template #default="{ row }">{{ row.wins }} 次</template>
          </el-table-column>
          <el-table-column prop="note" label="最近任务" min-width="130" show-overflow-tooltip />
          <el-table-column prop="saved_at" label="更新时间" width="160" />
          <el-table-column label="操作" width="72" align="center">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="forgetExperience(row)">忘记</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span class="nt-memory-empty">还没有积累经验，跑成功一单就会记在这里。</span>
          </template>
        </el-table>
        <template #footer>
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="!memorySelection.length"
            @click="forgetSelectedExperiences()"
          >
            删除所选{{ memorySelection.length ? `（${memorySelection.length}）` : '' }}
          </el-button>
          <el-button size="small" @click="memoryVisible = false">关闭</el-button>
        </template>
      </el-dialog>
    </section>

        <!-- ================= 阶段 3：交付 ================= -->
        <section v-show="step === 3" class="ad-card ad-deliver">
          <header class="ad-card-head">
            <div>
              <span class="ad-kicker">03 · 交付</span>
              <h2>{{ task ? statusText : '让智能体开始处理这一单' }}</h2>
              <p>
                {{ task
                  ? `源表 ${sourceFiles.length} 张 · 模板 ${templateLabel} · 已映射 ${mappedCount} 个字段`
                  : `源表 ${sourceFiles.length} 张 · 模板 ${templateLabel} · 已映射 ${mappedCount} 个字段，确认后即可执行` }}
              </p>
            </div>
            <div class="ad-run-actions">
              <button class="ad-primary ad-primary-lg" :disabled="!canRun" @click="run">
                <Icon name="spark" :size="15" />
                {{ running ? '智能体执行中…' : task ? '重新执行' : '让智能体执行' }}
              </button>
            </div>
          </header>

          <div class="ad-card-body">
            <div v-if="runHints.length" class="ad-hints">
              <Icon name="alert" :size="14" />
              <div>
                <p v-for="hint in runHints" :key="hint">{{ hint }}</p>
              </div>
            </div>

            <div v-if="runError" class="ad-hints is-bad">
              <Icon name="alert" :size="14" />
              <div><p>{{ runError }}</p></div>
            </div>

            <div v-if="running || (task && ['queued', 'running'].includes(task.status))" class="ad-progress">
              <div class="ad-progress-head">
                <b>{{ progress }}%</b>
                <span>{{ lastLog }}</span>
              </div>
              <div class="ad-progress-bar"><i :style="{ width: `${progress}%` }" /></div>
            </div>

            <div v-if="!task && !running" class="ad-deliver-empty">
              <div class="ad-deliver-preview">
                <span><Icon name="table" :size="16" /> 结果表</span>
                <b>按 {{ templateLabel }} 的表头与日期格式导出</b>
              </div>
              <div class="ad-deliver-preview">
                <span><Icon name="book" :size="16" /> 异常日志</span>
                <b>脏数据单独列出，不影响正常行交付</b>
              </div>
            </div>

            <template v-if="task && !running">
              <div class="ad-result-bar">
                <span class="ad-tag" :class="task.status === 'warning' ? 'is-warn' : task.status === 'failed' ? 'is-bad' : 'is-ok'">
                  {{ statusText }}
                </span>
                <span class="ad-muted">{{ task.id }} · {{ task.scene_name }} · 输出 {{ task.output_rows }} 行</span>
                <button class="ad-link" @click="router.push(`/tasks/${task.id}`)">查看任务记录</button>
              </div>

              <div v-if="task.status === 'failed'" class="ad-hints is-bad">
                <Icon name="alert" :size="14" />
                <div><p>{{ task.error || '任务执行失败，没有生成结果文件' }}</p></div>
              </div>

              <div v-if="statCards.length" class="ad-tiles ad-tiles-stats">
                <div v-for="item in statCards" :key="item.key" class="ad-tile" :class="{ 'is-warn': item.warn }">
                  <span>{{ item.label }}</span>
                  <b>{{ item.value }}<em>{{ item.unit }}</em></b>
                </div>
              </div>

              <el-tabs v-model="resultTab" class="ad-tabs">
                <el-tab-pane label="处理成功的结果表" name="ok">
                  <ResultTable
                    v-if="task && outputColumns.length && (!taskRunning || editBusy)"
                    :task-id="task.id"
                    :columns="outputColumns"
                    :refresh-key="editLog.length"
                  />
                  <div v-else-if="taskRunning" class="ad-empty">
                    智能体正在处理，结果出来后可在这里搜索、翻页。
                  </div>
                  <div v-else class="ad-empty">这次没有产生可输出的行，先看异常数据。</div>
                </el-tab-pane>

                <el-tab-pane v-if="isolate" :label="`异常数据（${anomalyRows.length}）`" name="bad">
                  <div v-if="anomalyRows.length">
                    <el-table :data="anomalyRows" border size="small" max-height="300" class="nt-result-table">
                      <el-table-column prop="source" label="源文件" min-width="170" show-overflow-tooltip />
                      <el-table-column prop="row" label="行号" width="76" />
                      <el-table-column label="异常类型" width="110">
                        <template #default="{ row }">
                          <el-tag size="small" type="warning" effect="plain">{{ row.type }}</el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column prop="reason" label="错误原因" min-width="240" show-overflow-tooltip />
                      <el-table-column prop="raw" label="原始内容" min-width="180" show-overflow-tooltip />
                    </el-table>
                    <p class="ad-note">异常行不影响其他行输出，回原表按行号核对即可。</p>
                  </div>
                  <div v-else class="ad-empty">本次没有异常行。</div>
                </el-tab-pane>
              </el-tabs>

              <!-- 先看结果再改结果：改完立刻在表格里看到变化，最后再去下载 -->
              <div
                v-if="['done', 'warning'].includes(task.status) || editBusy"
                class="ad-edit"
              >
                <div class="ad-edit-head">
                  <div>
                    <span class="ad-kicker">一句话改结果</span>
                    <b>说人话就行，智能体自己识别要改哪一行、哪一列</b>
                  </div>
                  <div class="ad-edit-meta">
                    <span v-if="editLog.length">已改 {{ editLog.length }} 处 · 影响 {{ editChanged }} 行</span>
                    <button v-if="editLog.length" class="ad-link" :disabled="editBusy" @click="undoEdit">
                      <Icon name="left" :size="12" />撤销最近一条
                    </button>
                  </div>
                </div>

                <div v-if="editLog.length" class="ad-edit-log">
                  <span v-for="(item, index) in editLog" :key="item.id || index" class="ad-edit-chip">
                    <em>{{ index + 1 }}</em>{{ item.summary || item.instruction }}
                  </span>
                </div>

                <div class="ad-edit-compose">
                  <el-input
                    ref="editInputRef"
                    v-model="editInput"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 4 }"
                    resize="none"
                    :disabled="editBusy"
                    placeholder="比如：第 3 行起始时间改成 09:00 / 姓名为张三的行删掉 / 合计小时全部加 1 / 按合计小时降序排序"
                    @keydown.enter.exact.prevent="submitEdit()"
                  />
                  <button class="ad-primary" :disabled="editBusy || !editInput.trim()" @click="submitEdit()">
                    <Icon name="spark" :size="14" />
                    {{ editBusy ? '智能体改写中…' : '让智能体改' }}
                  </button>
                </div>

                <div v-if="editSamples.length" class="ad-edit-samples">
                  <span>试试：</span>
                  <button
                    v-for="text in editSamples"
                    :key="text"
                    class="ad-chip"
                    :disabled="editBusy"
                    title="点一下填进输入框，改完再点「让智能体改」"
                    @click="useEditSample(text)"
                  >
                    {{ text }}
                  </button>
                </div>

                <p v-if="editError" class="ad-edit-err">{{ editError }}</p>
                <p class="ad-note">
                  每句话都会存成一条修改记录，跟着这套源表重算，表格和下载的 Excel 始终是同一份；
                  修改有先后顺序（先排序再改第 3 行就不一样了），所以只能从最近一条往回撤。
                </p>
              </div>

              <div class="ad-deliver-cards">
                <div class="ad-deliver-card">
                  <span class="ad-deliver-icon"><Icon name="download" :size="16" /></span>
                  <div>
                    <b>结果表</b>
                    <span>{{ resultArtifact ? `${resultArtifact.name} · ${resultArtifact.size}` : '本次没有产出结果文件' }}</span>
                  </div>
                  <button class="ad-ghost" :disabled="!resultUrl" @click="downloadResult">下载</button>
                </div>
                <div class="ad-deliver-card">
                  <span class="ad-deliver-icon"><Icon name="book" :size="16" /></span>
                  <div>
                    <b>异常日志</b>
                    <span>{{ isolate ? `异常 ${anomalyRows.length} 行，供人工复核` : '已关闭脏数据隔离' }}</span>
                  </div>
                  <button class="ad-ghost" :disabled="!(isolate && anomalyRows.length)" @click="downloadAnomaliesCsv">
                    下载 CSV
                  </button>
                </div>
              </div>
              <p class="ad-note">
                结果表已按 HR 老系统的表头和日期格式导出，导入前不要再用 Excel 另存为其它格式。
              </p>
            </template>
          </div>
        </section>

        <!-- 阶段导航 -->
        <div class="ad-foot">
          <span class="ad-foot-hint">{{ stepHint }}</span>
          <div class="ad-foot-actions">
            <button class="ad-ghost" :disabled="step === 1" @click="prevStep">
              <Icon name="left" :size="13" />上一步
            </button>
            <button v-if="step < 3" class="ad-primary" :disabled="!canLeave" @click="nextStep">
              {{ nextLabel }}<Icon name="right" :size="13" />
            </button>
            <template v-else>
              <button class="ad-ghost" @click="resetAll">再交一单</button>
              <button class="ad-primary" @click="router.push('/tasks')">查看任务记录</button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>


<style scoped>
/* 主色对齐站内其它页面，Element Plus 的组件变量只在这一页生效 */
.nt {
  --el-color-primary: #2b5fd9;
  --el-color-primary-light-3: #6b8fe4;
  --el-color-primary-light-5: #95afec;
  --el-color-primary-light-7: #bfcff6;
  --el-color-primary-light-8: #d5dffa;
  --el-color-primary-light-9: #eaeffb;
  --el-color-primary-dark-2: #224cae;
  --el-border-radius-base: 6px;
  --el-font-size-base: 13px;
  --el-text-color-primary: #1f2329;
  --el-text-color-regular: #43474e;
  --el-text-color-secondary: #7a808a;
  --el-border-color: #e2e5ea;
  --el-border-color-light: #e8eaee;
  --el-border-color-lighter: #eef0f3;
  --el-fill-color-light: #f7f8fa;
  --el-font-family: "Microsoft YaHei", "PingFang SC", "Helvetica Neue", Arial, sans-serif;
}

.nt {
  display: flex;
  flex-direction: column;
  /* 一屏放得下步骤条 + 当前内容 + 底部导航，不来回滚动 */
  min-height: calc(100vh - 128px);
}

.nt .panel {
  margin-bottom: 14px;
}

/* ---------- 步骤条 ---------- */
.nt-steps {
  display: flex;
  align-items: center;
  margin: 0 0 14px;
  padding: 10px 18px;
  background: #fff;
  border: 1px solid #e2e5ea;
  border-radius: 8px;
  list-style: none;
}

.nt-step {
  display: flex;
  gap: 8px;
  align-items: center;
  color: #8b919b;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}

.nt-step + .nt-step::before {
  width: 36px;
  height: 1px;
  margin: 0 16px;
  background: #e3e7ed;
  content: "";
}

/* 前一步做完了，连接线跟着变蓝，进度一眼能看出来 */
.nt-step.done + .nt-step::before {
  background: #2b5fd9;
}

.nt-step:hover:not(.locked) .nt-step-title {
  color: #2b5fd9;
}

.nt-step.locked {
  cursor: not-allowed;
  opacity: 0.55;
}

.nt-step-no {
  display: grid;
  flex: none;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #f1f2f5;
  color: #8b919b;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.nt-step.done .nt-step-no {
  background: #e8efff;
  color: #2b5fd9;
}

.nt-step.current {
  color: #1f2329;
  font-weight: 600;
}

.nt-step.current .nt-step-no {
  background: #2b5fd9;
  color: #fff;
}

/* ---------- 当前步骤的内容卡 ---------- */
.nt .nt-step-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  margin-bottom: 0;
}

.nt-step-panel > .panel-body {
  flex: 1;
  padding: 22px 20px 26px;
}

/* ---------- 底部导航 ---------- */
.nt-step-foot {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 14px;
  padding: 10px 18px;
  background: #fff;
  border: 1px solid #e2e5ea;
  border-radius: 8px;
}

.nt-step-hint {
  color: #a96400;
  font-size: 12px;
  line-height: 1.6;
}

.nt-step-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.nt-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-right: 8px;
  border-radius: 50%;
  background: #eaeffb;
  color: #2b5fd9;
  font-size: 12px;
  font-weight: 600;
  vertical-align: 1px;
}

/* ---------- 上传 ---------- */
.nt :deep(.el-upload) {
  display: block;
  width: 100%;
}

.nt :deep(.el-upload-dragger) {
  padding: 18px 16px;
  border: 1px dashed #ccd3dd;
  border-radius: 6px;
  background: #fbfcfe;
  transition: border-color 0.15s, background 0.15s;
}

.nt :deep(.el-upload-dragger:hover) {
  border-color: #2b5fd9;
  background: #f7f9fe;
}

/* 源表拖拽区给足高度；模板拖拽区比它略矮，但保留可点击的留白 */
.nt :deep(.el-upload:not(.nt-upload-sm) .el-upload-dragger) {
  padding: 36px 16px;
}

/* 注意：自定义 class 挂在 el-upload 根 div 上，和 .el-upload 不是同一个元素 */
.nt :deep(.nt-upload-sm .el-upload-dragger) {
  min-height: 104px;
  padding: 40px 16px;
}

.nt-drop {
  display: flex;
  flex-direction: column;
  gap: 5px;
  align-items: center;
  color: #43474e;
  font-size: 13px;
  line-height: 1.6;
}

.nt-drop b {
  font-weight: 600;
}

.nt-drop-icon {
  display: inline-flex;
  color: #7a808a;
}

.nt-drop-sub {
  color: #8b919b;
  font-size: 12px;
}

.nt-upload-sm {
  margin-top: 10px;
}

.nt-drop-sm {
  flex-direction: row;
  justify-content: center;
  gap: 8px;
  font-size: 12px;
}

.nt-files {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.nt-file {
  padding: 8px 10px;
  border: 1px solid #e5e8ee;
  border-radius: 6px;
  background: #fff;
}

.nt-file-head {
  display: flex;
  gap: 8px;
  align-items: center;
  color: #5b616b;
  font-size: 12px;
}

.nt-file-name {
  color: #1f2329;
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}

.nt-file-meta {
  color: #8b919b;
  white-space: nowrap;
}

.nt-file-head .el-button {
  margin-left: auto;
}

.nt-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 7px;
}

.nt-template {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #eef0f3;
}

.nt-template-head {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: baseline;
  margin-bottom: 8px;
  font-size: 13px;
}

.nt-template-head span {
  color: #8b919b;
  font-size: 12px;
}

.nt-template-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 6px;
}

.nt-template-hint {
  color: #8b919b;
  font-size: 12px;
  line-height: 1.6;
}

/* ---------- 映射 ---------- */
.nt-map-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 10px;
  font-size: 12px;
  color: #5b616b;
}

.nt-map-mode {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  color: #2b5fd9;
}

.nt-map-count {
  color: #8b919b;
}

.nt-map-count em {
  color: #c8821a;
  font-style: normal;
}

/* 人工确认状态：未确认是橙色提醒，确认后变绿，避免用户误以为已经过关 */
.nt-map-state {
  padding: 2px 8px;
  border-radius: 999px;
  background: #fff4e5;
  color: #b7701a;
  font-size: 12px;
}

.nt-map-state.ok {
  background: #e8f7ee;
  color: #1a7a44;
}

.nt-map-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.nt-map-bar .el-button {
  margin-left: 0;
}

/* 后端解析提示：条数不多，用小圆点列表，不占太多高度 */
.nt-notes {
  margin: 0 0 10px;
  padding: 8px 12px;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  background: #fbfcfe;
  color: #5b616b;
  font-size: 12px;
  line-height: 1.7;
  list-style: none;
}

.nt-notes li {
  position: relative;
  padding-left: 12px;
}

.nt-notes li::before {
  position: absolute;
  top: 8px;
  left: 2px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #b6bcc6;
  content: '';
}

/* ---------- 智能体执行轨迹 ---------- */
.nt-agent {
  margin: 10px 0 12px;
  overflow: hidden;
  border: 1px solid #dfe6f5;
  border-radius: 8px;
  background: linear-gradient(180deg, #f8faff 0%, #fff 46%);
}

.nt-agent-head {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  padding: 9px 12px;
  border-bottom: 1px solid #e9eef9;
}

.nt-agent-title {
  display: flex;
  gap: 7px;
  align-items: center;
  flex-wrap: wrap;
  color: #2b5fd9;
}

.nt-agent-title b {
  color: #1f2329;
  font-size: 13px;
}

.nt-agent-meta {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-left: auto;
  color: #8b919b;
  font-size: 12px;
}

.nt-agent-meta .el-button {
  margin-left: 0;
}

.nt-agent-body {
  padding: 8px 12px 10px;
}

.nt-agent-steps {
  position: relative;
  margin: 0;
  padding: 0;
  list-style: none;
}

.nt-agent-steps::before {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 10px;
  width: 1px;
  background: #e3e9f4;
  content: '';
}

.nt-agent-step {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 6px 0 9px;
}

.nt-agent-dot {
  position: relative;
  z-index: 1;
  display: inline-flex;
  flex: 0 0 21px;
  align-items: center;
  justify-content: center;
  width: 21px;
  height: 21px;
  border: 1px solid #d5e0f7;
  border-radius: 50%;
  background: #edf3ff;
  color: #2b5fd9;
  font-size: 11px;
  font-weight: 600;
}

.nt-agent-step.is-warn .nt-agent-dot {
  border-color: #f0ddb8;
  background: #fff7e8;
  color: #b7701a;
}

.nt-agent-step.is-fail .nt-agent-dot {
  border-color: #f0cece;
  background: #fff1f1;
  color: #c43f3f;
}

.nt-agent-step.is-skip .nt-agent-dot {
  border-color: #e2e5ea;
  background: #f5f6f8;
  color: #9aa0a8;
}

.nt-agent-line {
  flex: 1;
  min-width: 0;
  padding-top: 1px;
}

.nt-agent-step-head {
  display: flex;
  gap: 7px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 12px;
}

.nt-agent-step-head b {
  color: #1f2329;
  font-size: 13px;
  font-weight: 600;
}

.nt-agent-phase {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f2f5fb;
  color: #5b6475;
  font-size: 11px;
}

.nt-agent-tool {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef2ff;
  color: #3652c8;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.nt-agent-time {
  color: #9aa0a8;
  font-size: 11px;
  font-style: normal;
}

.nt-agent-status {
  margin-left: auto;
  color: #1f7a4d;
  font-size: 11px;
}

.nt-agent-step.is-warn .nt-agent-status {
  color: #b7701a;
}

.nt-agent-step.is-fail .nt-agent-status {
  color: #c43f3f;
}

.nt-agent-step.is-skip .nt-agent-status {
  color: #9aa0a8;
}

.nt-agent-detail {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.65;
}

.nt-agent-items {
  margin: 4px 0 0;
  padding-left: 15px;
  color: #8b919b;
  font-size: 12px;
  line-height: 1.7;
}

.nt-agent-insights {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 8px;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px dashed #e4e9f4;
}

.nt-agent-insight {
  padding: 7px 10px;
  border: 1px solid #e5e8ee;
  border-radius: 6px;
  background: #fff;
  color: #5b616b;
  font-size: 12px;
  line-height: 1.7;
}

.nt-agent-insight b {
  display: block;
  margin-bottom: 2px;
  color: #1f2329;
  font-size: 12px;
}

.nt-agent-insight p,
.nt-agent-insight ul {
  margin: 0;
}

.nt-agent-insight ul {
  padding-left: 15px;
}

.nt-agent-insight.is-good {
  border-color: #cdebd8;
  background: #f5fcf7;
}

.nt-agent-insight.is-fix {
  border-color: #f0e0c2;
  background: #fffbf2;
}

.nt-agent-insight.is-human {
  border-color: #d9e3f8;
  background: #f7faff;
}

.nt-agent-memory {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 10px;
  padding: 7px 10px;
  border: 1px dashed #dbe4f8;
  border-radius: 6px;
  background: #f8faff;
  color: #53617a;
  font-size: 12px;
}

.nt-agent-memory span {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}

.nt-memory-desc {
  margin: 0 0 10px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.8;
}

.nt-memory-empty {
  color: #8b929e;
  font-size: 12px;
}

.nt-map-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 12px;
}

.nt-box {
  border: 1px solid #e5e8ee;
  border-radius: 6px;
  overflow: hidden;
}

.nt-box-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 10px;
  border-bottom: 1px solid #eef0f3;
  background: #fafbfc;
  color: #43474e;
  font-size: 12px;
  font-weight: 600;
}

.nt-box-head em {
  color: #8b919b;
  font-style: normal;
  font-weight: 400;
}

.nt-box-body {
  max-height: 320px;
  overflow: auto;
  padding: 4px 0;
}

.nt-group-name {
  padding: 5px 10px 3px;
  color: #8b919b;
  font-size: 12px;
}

.nt-field,
.nt-target {
  display: flex;
  gap: 10px;
  justify-content: space-between;
  align-items: center;
  padding: 4px 10px;
  font-size: 12px;
  line-height: 1.6;
}

.nt-field-name,
.nt-target-name {
  color: #1f2329;
  white-space: nowrap;
}

.nt-field-sample {
  overflow: hidden;
  color: #8b919b;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 62%;
}

.nt-target-state {
  overflow: hidden;
  color: #c8821a;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 62%;
}

.nt-target.ok .nt-target-state {
  color: #1f7a4d;
}

.nt-req {
  margin-left: 2px;
  color: #d64541;
  font-style: normal;
}

.nt-map-table {
  margin-top: 4px;
}

.nt-td-target {
  color: #1f2329;
  font-weight: 600;
}

.nt-sample {
  color: #7a808a;
}

.nt-muted {
  color: #8b919b;
}

.nt :deep(.el-table th.el-table__cell) {
  background: #f7f8fa;
  color: #4a4f57;
  font-weight: 600;
}

.nt :deep(.el-table .el-select) {
  width: 100%;
}

.nt-add,
.nt-scheme,
.nt-quick {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 10px;
}

.nt-add-label {
  color: #7a808a;
  font-size: 12px;
  white-space: nowrap;
}

.nt-w-sm {
  width: 150px;
}

.nt-w-md {
  width: 210px;
}

.nt-scheme {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #e5e8ee;
}

.nt-scheme-tip {
  color: #9aa0a8;
  font-size: 12px;
}

/* ---------- 拼接 / 派生字段 ---------- */
.nt-expr-hint {
  margin-top: 8px;
  color: #8b919b;
  font-size: 12px;
  line-height: 1.9;
}

.nt-expr-hint code,
.nt-expr-desc code {
  padding: 1px 4px;
  border-radius: 4px;
  background: #f2f4f8;
  color: #3d5afe;
  font-size: 11px;
}

.nt-expr-cell {
  display: flex;
  gap: 6px;
  align-items: center;
}

.nt-expr-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 6px;
  border-radius: 4px;
  background: #f2f4f8;
  color: #3d5afe;
  font-size: 12px;
}

.nt-expr-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.nt-expr-desc {
  margin: 0;
  color: #5b616b;
  font-size: 13px;
  line-height: 1.8;
}

.nt-expr-files,
.nt-expr-cols {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.nt-expr-preview {
  color: #1f9d55;
  font-size: 12px;
  word-break: break-all;
}

.nt-expr-error {
  color: #d64545;
  font-size: 12px;
}

/* ---------- 规则 ---------- */
.nt-quick {
  margin: 0 0 10px;
}

.nt-hint {
  margin: 8px 0 0;
  color: #8b919b;
  font-size: 12px;
  line-height: 1.7;
}

.nt-switches {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #eef0f3;
}

.nt-switch-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.nt-switch-title {
  color: #1f2329;
  font-size: 13px;
}

.nt-switch-desc {
  margin-top: 2px;
  color: #8b919b;
  font-size: 12px;
  line-height: 1.7;
}

/* ---------- 运行与结果 ---------- */
.nt-run {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid #e5e8ee;
  border-radius: 6px;
  background: #fbfcfe;
  font-size: 13px;
}

.nt-run-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: #1f2329;
}

.nt-run-info .nt-muted {
  font-size: 12px;
}

.nt-run-hint {
  margin-top: 10px;
}

.nt-progress {
  margin-top: 12px;
}

.nt-progress-line {
  display: flex;
  gap: 10px;
  margin-top: 4px;
  color: #8b919b;
  font-size: 12px;
}

.nt-result-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 14px;
  font-size: 12px;
}

.nt-result-bar .el-button {
  margin-left: auto;
}

.nt-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: 8px;
  margin-top: 10px;
}

.nt-stat {
  padding: 9px 12px;
  border: 1px solid #e5e8ee;
  border-radius: 6px;
  background: #fff;
}

.nt-stat-label {
  color: #8b919b;
  font-size: 12px;
}

.nt-stat-value {
  margin-top: 3px;
  color: #1f2329;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
}

.nt-stat-value span {
  margin-left: 3px;
  color: #9aa0a8;
  font-size: 11px;
  font-weight: 400;
}

.nt-stat.warn .nt-stat-value {
  color: #c8821a;
}

.nt-tabs {
  margin-top: 6px;
}

.nt-table-note {
  margin: 6px 0 0;
  color: #8b919b;
  font-size: 12px;
}

.nt :deep(.el-tabs__header) {
  margin-bottom: 10px;
}

.nt :deep(.el-tabs__item) {
  height: 34px;
  font-size: 13px;
}

/* ---------- 下载 ---------- */
.nt-download {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.nt-download-meta {
  color: #8b919b;
  font-size: 12px;
}

.nt-empty {
  padding: 18px 0;
  color: #8b919b;
  font-size: 12px;
  line-height: 1.8;
  text-align: center;
}

.nt-empty b {
  display: block;
  color: #5b616b;
  font-size: 13px;
  font-weight: 600;
}

.nt-empty p {
  margin: 4px 0 0;
}

.nt-empty-sm {
  padding: 12px 10px;
  text-align: left;
}

.nt .el-button + .el-button {
  margin-left: 0;
}

@media (max-width: 960px) {
  .nt-map-grid,
  .nt-switches {
    grid-template-columns: 1fr;
  }

  .nt-run {
    flex-direction: column;
    align-items: flex-start;
  }

  .nt-w-sm,
  .nt-w-md {
    width: 100%;
  }
}
/* ================= 智能体工作台 ================= */
.agentdesk {
  --el-color-primary: #2b5fd9;
  --el-color-primary-light-3: #6b8fe4;
  --el-color-primary-light-5: #95afec;
  --el-color-primary-light-7: #bfcff6;
  --el-color-primary-light-8: #d5dffa;
  --el-color-primary-light-9: #eaeffb;
  --el-color-primary-dark-2: #224cae;
  --el-border-radius-base: 6px;
  --el-font-size-base: 13px;
  --el-text-color-primary: #1f2329;
  --el-text-color-regular: #43474e;
  --el-text-color-secondary: #7a808a;
  --el-border-color: #e2e5ea;
  --el-border-color-light: #e8eaee;
  --el-border-color-lighter: #eef0f3;
  --el-fill-color-light: #f7f8fa;
  --el-font-family: "Microsoft YaHei", "PingFang SC", "Helvetica Neue", Arial, sans-serif;

  --ad-ink: #141922;
  --ad-ink-2: #4b5563;
  --ad-ink-3: #8b929e;
  --ad-line: #e4e8ef;
  --ad-line-soft: #eef1f6;
  --ad-blue: #2b5fd9;
  --ad-blue-soft: #eef3ff;
  --ad-paper: #f6f7f9;
  --ad-mono: ui-monospace, SFMono-Regular, "SF Mono", Consolas, "Liberation Mono", monospace;

  display: block;
  /* 去掉右轨后正文变宽了，这里收窄一点，单行文字不超过一屏的三分之二更好读 */
  max-width: 1080px;
  padding-bottom: 8px;
}

.agentdesk :where(button) {
  font-family: inherit;
}

/* ---------- 布局：工作区 + 智能体状态轨 ---------- */
.ad-layout {
  display: block;
}

/* ---------- 模式切换（窄屏兜底，宽屏在顶栏右侧） ---------- */
.ad-mode-line {
  display: none;
  gap: 6px;
  align-items: center;
  margin-bottom: 12px;
}

.ad-mode-pill {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--ad-line);
  border-radius: 999px;
  background: #fff;
  color: var(--ad-ink-3);
  font-size: 12px;
  transition: border-color 0.18s ease, background 0.18s ease, color 0.18s ease;
}

.ad-mode-pill.is-on {
  border-color: #c9d8f8;
  background: var(--ad-blue-soft);
  color: var(--ad-blue);
  font-weight: 600;
}

/* 顶栏在 720px 以下整条隐藏，模式切换得在这里露出来 */
@media (max-width: 720px) {
  .ad-mode-line {
    display: flex;
  }
}

/* 智能体模式的“核心”标签：高亮，但只占一个小角，不抢标题 */
.ad-core {
  padding: 1px 6px;
  border: 1px solid #f0d9b5;
  border-radius: 999px;
  background: #fff5e6;
  color: #a96400;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.5;
}

.ad-work {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

/* ---------- 阶段条 ---------- */
.ad-stages {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin: 0 0 14px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--ad-line);
  border-radius: 10px;
  background: #fff;
  list-style: none;
}

.ad-stage {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 13px 18px 13px 20px;
  color: var(--ad-ink-3);
  cursor: pointer;
  user-select: none;
  transition: background 0.18s ease;
}

.ad-stage + .ad-stage::before {
  position: absolute;
  top: 16px;
  bottom: 16px;
  left: 0;
  width: 1px;
  background: var(--ad-line);
  content: "";
}

.ad-stage:hover:not(.is-locked) {
  background: #fafbfd;
}

.ad-stage.is-locked {
  cursor: not-allowed;
  opacity: 0.5;
}

.ad-stage.is-current {
  background: linear-gradient(180deg, #f8faff 0%, #fff 100%);
}

.ad-stage.is-current::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--ad-blue);
  content: "";
}

.ad-stage-no {
  display: grid;
  flex: none;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--ad-line);
  border-radius: 50%;
  background: #fff;
  color: var(--ad-ink-3);
  font-family: var(--ad-mono);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.ad-stage.is-current .ad-stage-no {
  border-color: var(--ad-blue);
  background: var(--ad-blue);
  color: #fff;
}

.ad-stage.is-done .ad-stage-no {
  border-color: #c9d8f8;
  background: var(--ad-blue-soft);
  color: var(--ad-blue);
}

.ad-stage-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.ad-stage-text b {
  color: var(--ad-ink-2);
  font-size: 13px;
  font-weight: 600;
}

.ad-stage.is-current .ad-stage-text b {
  color: var(--ad-ink);
}

.ad-stage-text em {
  color: var(--ad-ink-3);
  font-size: 11px;
  font-style: normal;
}

/* ---------- 主卡片 ---------- */
.ad-card {
  overflow: hidden;
  border: 1px solid var(--ad-line);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(20 25 34 / 3%);
}

.ad-card-head {
  display: flex;
  gap: 18px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 24px 18px;
  border-bottom: 1px solid var(--ad-line-soft);
  background: linear-gradient(180deg, #fcfcfe 0%, #fff 78%);
}

/* 状态胶囊 + 「清空重来」并排，右对齐 */
.ad-head-tools {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
  align-items: center;
}

.ad-kicker {
  display: inline-block;
  color: var(--ad-ink-3);
  font-family: var(--ad-mono);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.ad-card-head h2 {
  margin: 6px 0 5px;
  color: var(--ad-ink);
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -0.01em;
  line-height: 1.3;
}

.ad-card-head p {
  max-width: 620px;
  margin: 0;
  color: var(--ad-ink-2);
  font-size: 13px;
  line-height: 1.75;
}

.ad-head-state {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  padding: 5px 10px;
  border: 1px solid var(--ad-line);
  border-radius: 999px;
  background: #fff;
  color: var(--ad-ink-2);
  font-size: 11.5px;
  white-space: nowrap;
}

.ad-head-state i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #bcc2cc;
}

.ad-head-state.is-busy i {
  background: #2b5fd9;
  box-shadow: 0 0 0 3px rgb(43 95 217 / 14%);
  animation: adPulse 1.4s ease-in-out infinite;
}

.ad-head-state.is-ok {
  border-color: #cfe9da;
  background: #f5fcf8;
  color: #1f7a4d;
}

.ad-head-state.is-ok i {
  background: #2fa26b;
}

.ad-head-state.is-wait {
  border-color: #f0e0c2;
  background: #fffbf2;
  color: #a9701c;
}

.ad-head-state.is-wait i {
  background: #d99a2b;
}

@keyframes adPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.ad-head-tags {
  display: flex;
  flex: none;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.ad-badge {
  padding: 4px 9px;
  border: 1px solid var(--ad-line);
  border-radius: 5px;
  background: #fff;
  color: var(--ad-ink-2);
  font-family: var(--ad-mono);
  font-size: 10.5px;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.ad-badge.is-ai {
  border-color: #c9d8f8;
  background: var(--ad-blue-soft);
  color: var(--ad-blue);
}

.ad-badge.is-mem {
  border-color: #cfe9da;
  background: #f4fbf7;
  color: #1f7a4d;
}

.ad-badge.is-warn {
  border-color: #f0ddb8;
  background: #fffaf1;
  color: #a9701c;
}

.ad-card-body {
  padding: 20px 24px 24px;
}

/* ---------- 阶段 1：交接 ---------- */
.ad-slot-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: 12px;
}

.ad-drop :deep(.el-upload) {
  display: block;
  width: 100%;
}

.ad-drop :deep(.el-upload-dragger) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: auto;
  padding: 0;
  border: 1px dashed #cfd5df;
  border-radius: 10px;
  background:
    repeating-linear-gradient(135deg, rgb(43 95 217 / 2.2%) 0 8px, transparent 8px 16px),
    #fbfcfe;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.ad-drop :deep(.el-upload-dragger:hover) {
  border-color: var(--ad-blue);
  background: var(--ad-blue-soft);
}

.ad-drop-inner {
  display: flex;
  gap: 4px;
  align-items: center;
  flex-direction: column;
  padding: 22px 16px;
  text-align: center;
}

.ad-drop-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  margin-bottom: 4px;
  border: 1px solid #d9e2f5;
  border-radius: 9px;
  background: #fff;
  color: var(--ad-blue);
}

.ad-drop-inner b {
  color: var(--ad-ink);
  font-size: 13px;
  font-weight: 600;
}

.ad-drop-inner span {
  color: var(--ad-ink-3);
  font-size: 11.5px;
  line-height: 1.6;
}

.ad-tpl-card {
  padding: 14px 16px;
  border: 1px solid var(--ad-line);
  border-radius: 10px;
  background: #fff;
}

.ad-tpl-head {
  display: flex;
  gap: 10px;
  align-items: center;
}

.ad-tpl-head .ad-drop-icon {
  margin: 0;
  background: #f8faff;
}

.ad-tpl-head div {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.ad-tpl-head b {
  overflow: hidden;
  color: var(--ad-ink);
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ad-tpl-head span {
  color: var(--ad-ink-3);
  font-size: 11px;
}

.ad-tpl-cols {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  max-height: 92px;
  margin-top: 10px;
  overflow: auto;
}

.ad-tpl-cols span,
.ad-source-chips span,
.ad-field-chips span {
  padding: 2px 7px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 4px;
  background: #f7f9fc;
  color: var(--ad-ink-2);
  font-size: 11px;
  line-height: 1.7;
}

.ad-tpl-note {
  margin: 9px 0 0;
  color: var(--ad-ink-3);
  font-size: 11px;
}

.ad-icon-x {
  display: grid;
  flex: none;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #a2a8b3;
  cursor: pointer;
}

.ad-icon-x:hover {
  background: #fff1f1;
  color: #c43f3f;
}

.ad-sources {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.ad-source {
  padding: 10px 13px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 8px;
  background: #fcfdff;
}

.ad-source-head {
  display: flex;
  gap: 8px;
  align-items: center;
  color: var(--ad-ink-2);
}

.ad-source-head b {
  overflow: hidden;
  color: var(--ad-ink);
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ad-source-head span {
  margin-left: auto;
  color: var(--ad-ink-3);
  font-size: 11px;
  white-space: nowrap;
}

.ad-source-chips {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  margin-top: 7px;
}

/* 对话式规则 */
.ad-composer {
  display: flex;
  gap: 12px;
  margin-top: 14px;
  padding: 16px;
  border: 1px solid #dfe7f8;
  border-radius: 11px;
  background:
    radial-gradient(120% 100% at 0% 0%, rgb(43 95 217 / 6%) 0%, transparent 55%),
    #fbfcff;
}

/* 用一条竖细线当引线，表示“智能体在等你补充口径”，比图标克制 */
.ad-composer-line {
  flex: none;
  width: 2px;
  margin: 2px 0;
  border-radius: 2px;
  background: linear-gradient(180deg, rgb(43 95 217 / 65%) 0%, rgb(43 95 217 / 8%) 100%);
}

.ad-composer-body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 9px;
}

.ad-composer-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}

.ad-composer-head b {
  color: var(--ad-ink);
  font-size: 13px;
  font-weight: 600;
}

.ad-composer-head span {
  color: var(--ad-ink-3);
  font-size: 11px;
}

.ad-composer :deep(.el-textarea__inner) {
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 0 0 1px #dfe4ee inset;
  font-size: 13px;
  line-height: 1.8;
}

.ad-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.ad-chip {
  padding: 4px 10px;
  border: 1px solid #dbe3f3;
  border-radius: 999px;
  background: #fff;
  color: #42506a;
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.ad-chip:hover {
  border-color: var(--ad-blue);
  background: var(--ad-blue-soft);
  color: var(--ad-blue);
}

.ad-composer-foot {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

/* 按钮 */
.ad-primary,
.ad-ghost {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 16px;
  border-radius: 7px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.16s ease;
}

.ad-primary {
  border: 1px solid var(--ad-blue);
  background: var(--ad-blue);
  color: #fff;
  box-shadow: 0 3px 8px rgb(43 95 217 / 20%);
}

.ad-primary:hover:not(:disabled) {
  border-color: #224cae;
  background: #224cae;
}

.ad-primary:disabled,
.ad-ghost:disabled {
  cursor: not-allowed;
  opacity: 0.45;
  box-shadow: none;
}

.ad-primary-lg {
  height: 40px;
  padding: 0 20px;
  font-size: 13px;
}

.ad-ghost {
  border: 1px solid #d8dde7;
  background: #fff;
  color: var(--ad-ink-2);
}

.ad-ghost:hover:not(:disabled) {
  border-color: #b9c3d4;
  color: var(--ad-ink);
}

.ad-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ad-blue);
  font-size: 11.5px;
  cursor: pointer;
}

.ad-link:hover {
  text-decoration: underline;
}

.ad-muted {
  color: var(--ad-ink-3);
  font-size: 11.5px;
}

/* 规划进度条：先让用户看到“在读表/在对齐/在校验”，再让进度条慢慢逼近 92% */
.ad-plan-progress {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #d8e3f8;
  border-radius: 8px;
  background: #f7f9ff;
}

.ad-pp-head {
  display: flex;
  gap: 8px;
  align-items: center;
  color: var(--ad-ink-2);
  font-size: 12.5px;
}

.ad-pp-head b {
  flex: none;
  font-weight: 600;
}

.ad-pp-spin {
  display: inline-grid;
  flex: none;
  place-items: center;
  color: var(--accent);
  animation: adSpin 1s linear infinite;
}

@keyframes adSpin {
  to { transform: rotate(360deg); }
}

.ad-pp-stage {
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ad-pp-time {
  flex: none;
  margin-left: auto;
  color: var(--accent);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.ad-pp-track {
  height: 4px;
  margin-top: 9px;
  overflow: hidden;
  border-radius: 4px;
  background: #e3ebfb;
}

.ad-pp-track i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #4a86f0, #2b5fd9);
  transition: width 0.2s linear;
}

/* 规划完成的常驻提示条 */
.ad-plan-done {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 9px 12px;
  border: 1px solid #cfe6da;
  border-radius: 8px;
  background: var(--ok-soft);
  color: var(--ad-ink-2);
  font-size: 12.5px;
  line-height: 1.5;
}

.ad-plan-done > svg {
  flex: none;
  color: var(--ok);
}

.ad-plan-done b {
  color: var(--ok);
  font-weight: 600;
}

/* 口径改过但还没重新规划：提示条转成琥珀色，避免用户误以为这份方案是最新的 */
.ad-plan-done.is-stale {
  border-color: #f0ddbc;
  background: var(--warn-soft);
}

.ad-plan-done.is-stale > svg,
.ad-plan-done.is-stale b {
  color: var(--warn);
}

.ad-plan-done .ad-link {
  flex: none;
  margin-left: auto;
  color: var(--accent);
  font-weight: 500;
}

/* 高级开关 */
.ad-advanced {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--ad-line);
}

.ad-advanced .ad-link {
  color: var(--ad-ink-2);
}

.ad-advanced .ad-link span {
  color: var(--ad-ink-3);
  font-weight: 400;
}

.ad-advanced-body {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.ad-switch {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 8px;
  background: #fcfdff;
  cursor: pointer;
}

.ad-switch span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ad-switch b {
  color: var(--ad-ink);
  font-size: 12px;
  font-weight: 600;
}

.ad-switch em {
  color: var(--ad-ink-3);
  font-size: 11px;
  font-style: normal;
  line-height: 1.6;
}

/* ---------- 阶段 2：核对 ---------- */
.ad-tiles {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.ad-tile {
  padding: 12px 14px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 9px;
  background: #fcfdff;
}

.ad-tile > span {
  display: block;
  color: var(--ad-ink-3);
  font-size: 11px;
}

.ad-tile b {
  display: flex;
  gap: 3px;
  align-items: baseline;
  margin: 2px 0 1px;
  color: var(--ad-ink);
  font-family: var(--ad-mono);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.2;
}

.ad-tile b em {
  color: var(--ad-ink-3);
  font-size: 11px;
  font-style: normal;
  letter-spacing: 0;
}

.ad-tile small {
  display: block;
  overflow: hidden;
  color: var(--ad-ink-3);
  font-size: 10.5px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ad-tile.is-warn {
  border-color: #f0e0c2;
  background: #fffbf2;
}

.ad-tile.is-warn b {
  color: #a9701c;
}

.ad-decision {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 9px;
  margin-bottom: 14px;
}

.ad-decision-col {
  padding: 10px 12px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 8px;
  background: #fcfdff;
}

.ad-decision-col b {
  display: block;
  margin-bottom: 3px;
  color: var(--ad-ink);
  font-size: 11.5px;
  font-weight: 600;
}

.ad-decision-col ul {
  margin: 0;
  padding-left: 14px;
  color: var(--ad-ink-2);
  font-size: 11.5px;
  line-height: 1.7;
}

.ad-decision-col.is-fix {
  border-color: #f0e0c2;
  background: #fffcf4;
}

.ad-decision-col.is-human {
  border-color: #d9e3f8;
  background: #f7faff;
}

/* 深色轨迹面板：和浅色工作台形成对比，一眼看出“它在干活” */
.ad-trace {
  margin-bottom: 16px;
  overflow: hidden;
  border: 1px solid #1d2836;
  border-radius: 10px;
  background:
    radial-gradient(120% 120% at 0% 0%, rgb(74 134 240 / 12%) 0%, transparent 50%),
    #101722;
}

.ad-trace-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 14px;
  border: 0;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
  background: transparent;
  cursor: pointer;
}

.ad-trace-title {
  display: flex;
  gap: 7px;
  align-items: center;
  color: #9fb6e6;
}

.ad-trace-title b {
  color: #eaf0fb;
  font-size: 12.5px;
  font-weight: 600;
}

.ad-trace-title em {
  color: #7d8ba4;
  font-family: var(--ad-mono);
  font-size: 10.5px;
  font-style: normal;
}

/* token 成本单独一条，和耗时区分开，方便一眼看到模型开销 */
.ad-trace-title .ad-trace-token {
  padding: 1px 6px;
  border-radius: 999px;
  background: rgb(93 140 255 / 12%);
  color: #9fb6e6;
}

.ad-trace-head .ad-link {
  color: #8ea6d6;
}

.ad-trace-list {
  margin: 0;
  padding: 12px 14px 14px;
  list-style: none;
}

.ad-trace-step {
  position: relative;
  display: flex;
  gap: 10px;
  padding-bottom: 12px;
}

.ad-trace-step:last-child {
  padding-bottom: 0;
}

.ad-trace-step::before {
  position: absolute;
  top: 20px;
  bottom: 0;
  left: 9px;
  width: 1px;
  background: rgb(255 255 255 / 9%);
  content: "";
}

.ad-trace-step:last-child::before {
  display: none;
}

.ad-trace-dot {
  position: relative;
  z-index: 1;
  display: grid;
  flex: none;
  place-items: center;
  width: 19px;
  height: 19px;
  border: 1px solid rgb(255 255 255 / 16%);
  border-radius: 50%;
  background: #16202e;
  color: #8ea6d6;
  font-family: var(--ad-mono);
  font-size: 10px;
}

.ad-trace-step.is-ok .ad-trace-dot {
  border-color: rgb(66 211 146 / 38%);
  color: #5fd3a0;
}

.ad-trace-step.is-warn .ad-trace-dot {
  border-color: rgb(233 180 76 / 40%);
  color: #e9b44c;
}

.ad-trace-step.is-skip .ad-trace-dot {
  border-color: rgb(255 255 255 / 10%);
  color: #6b778a;
}

.ad-trace-main {
  min-width: 0;
  flex: 1;
}

.ad-trace-line {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.ad-trace-line b {
  color: #e5ecf9;
  font-size: 12px;
  font-weight: 500;
}

.ad-trace-line code {
  padding: 1px 5px;
  border-radius: 3px;
  background: rgb(142 166 214 / 12%);
  color: #9fb6e6;
  font-family: var(--ad-mono);
  font-size: 10px;
}

.ad-trace-line em {
  color: #6b778a;
  font-family: var(--ad-mono);
  font-size: 10px;
  font-style: normal;
}

.ad-trace-line span {
  margin-left: auto;
  color: #5fd3a0;
  font-size: 10.5px;
}

.ad-trace-step.is-warn .ad-trace-line span {
  color: #e9b44c;
}

.ad-trace-step.is-skip .ad-trace-line span {
  color: #6b778a;
}

.ad-trace-main p {
  margin: 4px 0 0;
  color: #96a3b8;
  font-size: 11.5px;
  line-height: 1.7;
}

.ad-trace-main ul {
  margin: 3px 0 0;
  padding-left: 14px;
  color: #7d8ba4;
  font-size: 11px;
  line-height: 1.7;
}

.ad-trace-empty {
  margin: 0;
  padding: 14px;
  color: #7d8ba4;
  font-size: 11.5px;
}

/* 模型跑完工具循环后的自述：让“它自己决定怎么做”这件事可见 */
.ad-trace-summary {
  margin: 0;
  padding: 10px 14px;
  border-bottom: 1px solid rgb(255 255 255 / 6%);
  color: #b9c7de;
  font-size: 11.5px;
  line-height: 1.7;
}

/* 映射区 */
.ad-map-head {
  display: flex;
  gap: 14px;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.ad-map-count {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}

.ad-map-count b {
  color: var(--ad-ink);
  font-size: 13px;
  font-weight: 600;
}

.ad-map-count em {
  color: #a9701c;
  font-size: 11.5px;
  font-style: normal;
}

/* 映射来源说明：AI 采纳时用成功色，纯规则时用中性灰，不喧宾夺主 */
.ad-map-src {
  padding: 1px 7px;
  color: #1c6b3c;
  font-size: 11.5px;
  line-height: 18px;
  border: 1px solid #b7dfc6;
  border-radius: 9px;
  background: #f2fbf5;
}

.ad-map-src.is-rule {
  color: var(--muted);
  border-color: var(--line);
  background: #f6f7f9;
}

.ad-map-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ad-confirm-state {
  padding: 3px 9px;
  border: 1px solid #f0ddb8;
  border-radius: 999px;
  background: #fffaf1;
  color: #a9701c;
  font-size: 11px;
  white-space: nowrap;
}

.ad-confirm-state.ok {
  border-color: #cfe9da;
  background: #f4fbf7;
  color: #1f7a4d;
}

.ad-fields-toggle {
  margin: 2px 0 8px;
  color: var(--ad-ink-2);
}

.ad-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 8px;
  background: #fcfdff;
}

.ad-field-group {
  min-width: 0;
}

.ad-field-group > b {
  color: var(--ad-ink-2);
  font-size: 11.5px;
  font-weight: 600;
}

.ad-field-chips {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.ad-field-chips span {
  display: inline-flex;
  gap: 5px;
  align-items: baseline;
}

.ad-field-chips i {
  color: var(--ad-ink-3);
  font-size: 10px;
  font-style: normal;
}

/* 阶段 3：交付 */
.ad-run-actions {
  flex: none;
}

.ad-hints {
  display: flex;
  gap: 9px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid #f0e0c2;
  border-radius: 8px;
  background: #fffbf2;
  color: #8a651f;
}

.ad-hints.is-bad {
  border-color: #f0cece;
  background: #fff6f6;
  color: #b13a3a;
}

.ad-hints p {
  margin: 0 0 2px;
  font-size: 11.5px;
  line-height: 1.7;
}

.ad-progress {
  margin-bottom: 14px;
}

.ad-progress-head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 6px;
}

.ad-progress-head b {
  color: var(--ad-ink);
  font-family: var(--ad-mono);
  font-size: 18px;
  font-weight: 600;
}

.ad-progress-head span {
  overflow: hidden;
  color: var(--ad-ink-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ad-progress-bar {
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: #eef1f6;
}

.ad-progress-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2b5fd9, #4a86f0);
  transition: width 0.35s ease;
}

.ad-deliver-empty {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.ad-deliver-preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  border: 1px dashed var(--ad-line);
  border-radius: 9px;
  background: #fcfdff;
}

.ad-deliver-preview span {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: var(--ad-ink-3);
  font-size: 11.5px;
}

.ad-deliver-preview b {
  color: var(--ad-ink-2);
  font-size: 12px;
  font-weight: 500;
}

.ad-result-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 9px 12px;
  border: 1px solid var(--ad-line-soft);
  border-radius: 8px;
  background: #fcfdff;
}

.ad-tag {
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.ad-tag.is-ok {
  background: #eef8f2;
  color: #1f7a4d;
}

.ad-tag.is-warn {
  background: #fff7e8;
  color: #a9701c;
}

.ad-tag.is-bad {
  background: #fff1f1;
  color: #b13a3a;
}

.ad-result-bar .ad-link {
  margin-left: auto;
}

.ad-tiles-stats {
  margin-bottom: 12px;
}

.ad-tabs :deep(.el-tabs__header) {
  margin-bottom: 10px;
}

.ad-tabs :deep(.el-tabs__item) {
  height: 34px;
  font-size: 12.5px;
}

.ad-deliver-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.ad-deliver-card {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid var(--ad-line);
  border-radius: 9px;
  background: #fff;
}

.ad-deliver-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid #d9e2f5;
  border-radius: 8px;
  background: #f7f9ff;
  color: var(--ad-blue);
}

.ad-deliver-card > div {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.ad-deliver-card b {
  color: var(--ad-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.ad-deliver-card span {
  overflow: hidden;
  color: var(--ad-ink-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ad-note {
  margin: 10px 0 0;
  color: var(--ad-ink-3);
  font-size: 11px;
  line-height: 1.7;
}

.ad-empty {
  padding: 20px 14px;
  border: 1px dashed var(--ad-line);
  border-radius: 8px;
  background: #fcfdff;
  color: var(--ad-ink-3);
  font-size: 12px;
  text-align: center;
}

/* 一句话改结果 */
.ad-edit {
  margin-top: 12px;
  padding: 14px 16px;
  border: 1px solid #dbe3f3;
  border-radius: 10px;
  background: linear-gradient(180deg, #f8faff 0%, #ffffff 62%);
}

.ad-edit-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.ad-edit-head b {
  display: block;
  margin-top: 4px;
  color: var(--ad-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.ad-edit-meta {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  color: var(--ad-ink-3);
  font-size: 11px;
  white-space: nowrap;
}

.ad-edit-log {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.ad-edit-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 4px 10px 4px 4px;
  border: 1px solid #dfe7f7;
  border-radius: 999px;
  background: #fff;
  color: #3c4a63;
  font-size: 11px;
  line-height: 1.5;
}

.ad-edit-chip em {
  display: grid;
  flex: none;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--ad-blue-soft);
  color: var(--ad-blue);
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
}

.ad-edit-compose {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-top: 10px;
}

.ad-edit-compose :deep(.el-textarea__inner) {
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  box-shadow: 0 0 0 1px #dbe3f3 inset;
}

.ad-edit-compose :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--ad-blue) inset;
}

.ad-edit-compose .ad-primary {
  flex: none;
  height: 36px;
}

.ad-edit-samples {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 8px;
  color: var(--ad-ink-3);
  font-size: 11px;
}

.ad-edit-samples .ad-chip:disabled {
  opacity: 0.5;
  cursor: default;
}

.ad-edit-err {
  margin: 8px 0 0;
  color: #b44234;
  font-size: 11.5px;
}

.ad-edit .ad-note {
  margin-top: 8px;
}

/* 阶段导航 */
.ad-foot {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  padding: 10px 16px;
  border: 1px solid var(--ad-line);
  border-radius: 10px;
  background: #fff;
}

.ad-foot-hint {
  color: var(--ad-ink-3);
  font-size: 11.5px;
  line-height: 1.6;
}

.ad-foot-actions {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
}


/* 兼容旧映射表格的观感 */
.agentdesk :deep(.nt-req) {
  margin-left: 2px;
  color: #d05252;
  font-style: normal;
}

.agentdesk :deep(.nt-expr-text) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f2f6ff;
  color: #2f55c2;
  font-family: var(--ad-mono);
  font-size: 11px;
}

.agentdesk :deep(.nt-map-table) {
  margin-bottom: 10px;
  border-radius: 8px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 820px) {
  .ad-slot-grid,
  .ad-tiles {
    grid-template-columns: 1fr 1fr;
  }

  .ad-stages {
    grid-template-columns: 1fr;
  }

  .ad-stage + .ad-stage::before {
    top: 0;
    right: 18px;
    bottom: auto;
    left: 18px;
    width: auto;
    height: 1px;
  }

  .ad-agent {
    grid-template-columns: 1fr;
  }

  .ad-card-head,
  .ad-card-body {
    padding-right: 16px;
    padding-left: 16px;
  }

  .ad-composer {
    padding: 12px;
  }

  .ad-foot {
    align-items: stretch;
    flex-direction: column;
  }

  .ad-foot-actions {
    justify-content: flex-end;
  }
}

@media (max-width: 560px) {
  .ad-slot-grid,
  .ad-tiles {
    grid-template-columns: 1fr;
  }
}
</style>
