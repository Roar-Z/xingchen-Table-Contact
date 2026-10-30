<script setup>
// 智能体模式 —— 跨系统表格桥接
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDialog,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
  ElUpload
} from 'element-plus'
import Icon from './Icon.vue'
import {
  railDetail,
  railLessons,
  railMemory,
  railMemoryRequest,
  railScope,
  railState,
  railSteps,
  railTools,
  railTone
} from '../agentRail'
import { api, apiState } from '../api'
import { OFFLINE_HINT } from '../offline'
import { useToast } from '../toast'

const { show } = useToast()

// 离线时这一页只能引导用户去别处，切换模式由外层页面负责
const emit = defineEmits(['switch-mode'])

// 断网：扫描、规划、执行都要后端，整块换成说明卡，不留一个点了没反应的按钮
const offlineMode = computed(() => apiState.online === false)

function goStandardMode() {
  emit('switch-mode')
}

async function retryConnection() {
  try {
    await api.health()
    show('已恢复连接，智能体模式可以用了')
  } catch (e) {
    show('还是连不上服务，离线功能请到普通模式或表格智作页', 'err')
  }
}

const SNAPSHOT_KEY = 'huibiao.bridge.v1'

const dataset = ref(null)
const pendingFiles = ref([])
const pickSource = ref('files')
const datasetName = ref('')
const instruction = ref('')
// 业务目标默认收起，让上传页保持简洁；不展开就等于“交给智能体自主规划”
const goalOpen = ref(false)
const error = ref('')

// 页面上原本一口气铺开的四块内容（数据集清单、方案细节、验收标准）默认收起来：
const dataOpen = ref(false)
const detailOpen = ref(false)
const acceptAll = ref(false)
const summaryOpen = ref(false)
const flowOpen = ref(false)

// 数据集总行数：顶部运行条和折叠标题都要用
const totalRows = computed(() =>
  (dataset.value?.tables || []).reduce((sum, table) => sum + (table.rows || 0), 0)
)

// 方案摘要超过两行才需要「展开全文」，短句就不放这个按钮占地方
const summaryLong = computed(() => (plan.value?.summary || '').length > 56)

// 四个区块标题行的摘要只放一行：窄栏里放不下就省略，鼠标悬浮用 title 看到全文。
const dataBrief = computed(() => {
  const parts = [`${dataset.value?.tables?.length || 0} 张表`, `共 ${totalRows.value} 行`]
  if (dataset.value?.skipped?.length) parts.push(`${dataset.value.skipped.length} 张重复表已跳过`)
  return parts.join(' · ')
})

const goalBrief = computed(() => {
  const base = plan.value?.goal ? '目标已确定' : '智能体正在按数据结构推断'
  const count = plan.value?.acceptance?.length || 0
  return count ? `${base} · ${count} 条验收标准` : base
})

const planBrief = '它自己定的，不用确认就已经执行'

const resultBrief = computed(() => {
  const res = result.value
  if (!res) return ''
  const parts = [
    `标准表 ${res.ok_rows} 行 · 异常 ${res.anomaly_rows} 行`,
    `${res.table_count} 张表参与`,
    `${res.rounds || 1} 轮`,
    elapsedText(res.elapsed_ms)
  ]
  if (res.repairs) parts.push(`修复 ${res.repairs} 个脏值`)
  return parts.join(' · ')
})

const scanning = ref(false)
const planning = ref(false)
const running = ref(false)

const folderInput = ref(null)
const instructionRef = ref(null)
const uploadRef = ref(null)

const rulesVisible = ref(false)
const rules = ref([])
// 经验记忆：跑成功的配置自动沉淀，同类数据集下一单会先用上
const lessons = ref([])
const standardFields = ref([])
// 下拉框第一项是「自定义」：选到它就换成输入框，手动写标准字段以外的目标名
const CUSTOM_FIELD = '__custom__'
const ruleDraft = ref({ standard: 'emp_no', alias: '', custom: '' })
const customFieldRef = ref(null)

const recent = ref([])
// 「最近记录」下拉：点图标展开，点空白处收起
const recentOpen = ref(false)
const openTables = ref([])

const resultTab = ref('ok')
const okTable = ref({ columns: [], rows: [], total: 0, page: 1, size: 20 })
const anomalyTable = ref({ columns: [], rows: [], total: 0, page: 1, size: 20 })
const rowsBusy = ref(false)

// ---------------- 真实进度 ----------------
const runEvents = ref([])
const runStage = ref('')
const cancelling = ref(false)
const elapsed = ref(0)
let ticker = null
let pollTimer = null
let pollSince = 0

const tickText = computed(() => runStage.value || '正在读取数据集，判定每张表的业务归属…')

// 进度按真实事件条数走，不按等待秒数编
const progressPercent = computed(() => {
  if (!planning.value && !running.value) return 100
  return Math.min(95, 6 + runEvents.value.length * 7)
})

const lastEvent = computed(() => runEvents.value[runEvents.value.length - 1] || null)

// 还在规划阶段：最后一条事件属于 plan，或者还没收到事件
const inPlanPhase = computed(() => {
  if (!planning.value && !running.value) return false
  if (cancelling.value) return false
  return !lastEvent.value || lastEvent.value.phase === 'plan'
})

// 左栏要点亮的项：真实发生过的原子能力，按发生顺序，最后一个转圈
const liveTools = computed(() => {
  const out = []
  const byTool = new Map()
  for (const event of runEvents.value) {
    if (!event.tool) continue
    const item = byTool.get(event.tool)
    if (item) {
      if (event.detail) item.hint = event.detail
      continue
    }
    const created = { key: event.tool, label: event.label, hint: event.detail || '', state: 'done' }
    byTool.set(event.tool, created)
    out.push(created)
  }
  return out
})

function startPolling(id) {
  stopPolling()
  runEvents.value = []
  runStage.value = ''
  pollSince = 0
  const pull = async () => {
    try {
      const res = await api.bridgeEvents(id, pollSince)
      if (!res) return
      const list = res.events || []
      if (list.length) {
        runEvents.value = runEvents.value.concat(list)
        pollSince = list[list.length - 1].seq
        // 后端开始执行后，左栏就该显示“自主执行中”，不再一直挂着“规划中”
        if (list.some((item) => item.phase && item.phase !== 'plan')) planning.value = false
      }
      if (res.stage) runStage.value = res.stage
    } catch (err) {
      // 进度拉取失败不打扰用户：主请求还在跑，下一拍再试
    }
  }
  pull()
  pollTimer = window.setInterval(pull, 700)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

// 只用来显示“已等待 N 秒”，不再驱动文案；文案以真实事件为准
function startTicker() {
  stopTicker()
  elapsed.value = 0
  ticker = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
}

function stopTicker() {
  if (ticker) {
    window.clearInterval(ticker)
    ticker = null
  }
}

// ---------------- 业务目标：可选，不填就由智能体自己推断 ----------------
const exampleHints = [
  '把 HR 入职表和 ERP 薪资表桥接，计算每人应发工资，入职信息缺失的进异常清单',
  '把 OA 请假单按工号并到员工表，汇总每人请假天数与小时',
  '把 OA 加班表和 HR 花名册对起来，工号对不上的单独出一张清单'
]

function useExample(text) {
  if (planning.value || running.value) return
  instruction.value = text
  nextTick(() => instructionRef.value?.focus?.())
}

// ---------------- 上传 ----------------
function onFileChange(uploadFile) {
  const raw = uploadFile?.raw
  if (!raw) return
  addPending(raw)
}

function onFolderPick(event) {
  const files = Array.from(event.target.files || [])
  pickSource.value = 'folder'
  files.forEach(addPending)
  event.target.value = ''
}

function addPending(file) {
  if (!file.size) return
  const name = file.name || 'file'
  if (!/\.(zip|xlsx|xls|csv)$/i.test(name)) {
    show(`${name} 不是表格或压缩包，已跳过`, 'err')
    return
  }
  if (pendingFiles.value.some((item) => item.name === name && item.size === file.size)) return
  pendingFiles.value.push(file)
  if (pendingFiles.value.length === 1) {
    if (/\.zip$/i.test(name)) pickSource.value = 'zip'
    if (!datasetName.value) datasetName.value = name.replace(/\.(zip|xlsx|xls|csv)$/i, '')
  }
}

function removePending(index) {
  pendingFiles.value.splice(index, 1)
}

const pendingText = computed(() => {
  if (!pendingFiles.value.length) return ''
  const zipCount = pendingFiles.value.filter((item) => /\.zip$/i.test(item.name)).length
  return zipCount ? `${pendingFiles.value.length} 个文件（含压缩包）` : `${pendingFiles.value.length} 个表格`
})

const canScan = computed(() => pendingFiles.value.length > 0 && !scanning.value)

async function startScan() {
  if (!canScan.value) return
  scanning.value = true
  error.value = ''
  try {
    const res = await api.bridgeCreate(pendingFiles.value, datasetName.value, pickSource.value, access.value)
    dataset.value = res
    pendingFiles.value = []
    uploadRef.value?.clearFiles?.()
    resultTab.value = 'ok'
    okTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    anomalyTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    saveSnapshot()
    loadRecent()
    show(`数据集扫描完成：识别 ${res.tables?.length || 0} 张表`
      + (res.skipped?.length ? `，${res.skipped.length} 张重复表已跳过` : '')
      + '，开始自主处理')
  } catch (err) {
    error.value = err.message || '上传失败'
    show(error.value, 'err')
    return
  } finally {
    scanning.value = false
  }
  // 扫描完不再等人写指令：上传页填了业务目标就按那句跑，没填就让智能体自己定目标、自己跑完
  // （扫描状态先收掉，左栏才切到“自主规划中”）
  await autoNow(instruction.value)
}

// ---------------- 自主处理 ----------------
const plan = computed(() => dataset.value?.plan || null)
const result = computed(() => dataset.value?.result || null)

const statusText = computed(() => {
  const map = {
    scanned: '已扫描，自主处理中',
    awaiting: '方案已出，等你确认执行',
    planned: '方案已定，自主执行中',
    running: '自主执行中',
    done: result.value?.passed ? '已达标交付' : '已交付（未达标）',
    failed: '自主处理失败'
  }
  return map[dataset.value?.status] || '自主处理中'
})

const statusTone = computed(() => {
  if (dataset.value?.status === 'done') return result.value?.passed === false ? 'wait' : 'ok'
  if (dataset.value?.status === 'planned' || dataset.value?.status === 'awaiting') return 'wait'
  if (dataset.value?.status === 'failed') return 'err'
  return 'idle'
})

// 这一单的目标：智能体推断出来的那句，或用户额外补的目标
const goalText = computed(() => plan.value?.goal || dataset.value?.instruction || '')
// 验收标准：和智能体自己比对用的是同一套口径
const acceptance = computed(() => plan.value?.acceptance || [])
// 自主循环的每一轮：跑了多少、看到什么、决定了什么
const attempts = computed(() => plan.value?.attempts || [])

// ---------------- 左侧「操作步骤」状态条 ----------------
const BRIDGE_TOOLS = {
  scan_dataset: { label: '扫描检索', hint: '找出数据集里的全部表格' },
  parse_sheets: { label: '表格解析', hint: '读取表头、行数和示例内容' },
  detect_kind: { label: '业务识别', hint: '判断表格属于人事、薪酬还是考勤' },
  align_fields: { label: '字段对齐', hint: '把不同表格中含义相同的列对应起来' },
  join_tables: { label: '跨表关联', hint: '按工号或姓名把多张表拼在一起' },
  filter_rows: { label: '条件筛选', hint: '按日期、在职状态等条件保留记录' },
  compute: { label: '业务计算', hint: '汇总合计并按公式算出新列' },
  isolate_anomaly: { label: '异常隔离', hint: '把缺项、格式错误、对不上的记录单独列出' },
  standardize_export: { label: '标准化导出', hint: '按目标系统的列顺序生成可导入文件' },
  autonomy: { label: '自主复核', hint: '对照验收标准，不达标就自己改一处重跑' },
  autonomy_note: { label: '经验沉淀', hint: '把这次的对齐与目标记下来，下一单直接复用' }
}

// 规划阶段真正跑掉的原子能力：扫描、解析、业务识别、字段对齐。
// 跑完之后这几项不再有进度可言，直接算完成。
const PLAN_STAGE_TOOLS = new Set(['scan_dataset', 'parse_sheets', 'detect_kind', 'align_fields'])

const railStateText = computed(() => {
  if (scanning.value) return '扫描中'
  if (inPlanPhase.value) return '自主规划中'
  if (planning.value || running.value) return '自主执行中'
  if (error.value) return '需处理'
  if (result.value) return result.value.passed === false ? '已交付' : '已达标'
  if (dataset.value) return '待自主处理'
  return '待上传'
})

const railToneOf = computed(() => {
  const text = railStateText.value
  if (text === '已达标') return 'ok'
  if (['扫描中', '自主规划中', '自主执行中'].includes(text)) return 'busy'
  if (['已交付', '待自主处理', '需处理'].includes(text)) return 'wait'
  return 'idle'
})

const railToolsOf = computed(() => {
  // 正在跑（含重新跑）：只按后端真实写下的事件点亮，最后一个转圈。
  // 不再按秒数凭空推进——用户看到的就是它实际走到哪一步，卡住也看得出来。
  if (planning.value || running.value) {
    const live = liveTools.value
    return live.map((item, index) => ({
      key: `live-${item.key}-${index}`,
      label: item.label,
      hint: item.hint,
      state: index === live.length - 1 ? 'busy' : 'done'
    }))
  }
  const steps = plan.value?.steps || []
  if (!steps.length) return []
  const executed = dataset.value?.status === 'done' || !!result.value
  return steps.map((step, index) => {
    const meta = BRIDGE_TOOLS[step.tool] || { label: step.title || step.tool, hint: step.detail || '' }
    return {
      key: `${step.tool}-${index}`,
      label: meta.label,
      hint: step.detail || meta.hint,
      // 规划阶段那几项当时已经真跑过；跑完的一单所有步骤都算完成
      state: PLAN_STAGE_TOOLS.has(step.tool) || executed ? 'done' : 'idle'
    }
  })
})

// 状态下方的一句摘要：规划中说明正在推断什么，出方案后写清本次规模与验收线
const railDetailOf = computed(() => {
  if (inPlanPhase.value) return '正在推断业务目标、确定验收标准与执行方案'
  if (planning.value || running.value) return '正在自主执行与自检，异常偏多会自己改一处重跑'
  const current = plan.value
  if (current) {
    const parts = []
    const aligns = current.aligns?.length || 0
    const computes = current.computes?.length || 0
    const outputs = current.outputColumns?.length || 0
    if (aligns) parts.push(`对齐 ${aligns} 个字段`)
    if (computes) parts.push(`计算 ${computes} 列`)
    if (outputs) parts.push(`输出 ${outputs} 列`)
    if (result.value) {
      parts.push(`${result.value.passed ? '已达标' : '未达标'} ${result.value.okRows ?? result.value.ok_rows}/${result.value.totalRows ?? result.value.total_rows}`)
    }
    return parts.join(' · ')
  }
  if (dataset.value) return `${dataset.value.tables?.length || 0} 张表已就绪，智能体马上自主处理`
  return ''
})

// 进度、能力清单、方案摘要、规则条数一次性同步给导航栏
function syncRail() {
  if (railScope.value !== 'bridge') return
  railState.value = railStateText.value
  railTone.value = railToneOf.value
  // 规划期间方案还没定型，不显示上一版方案的步数
  railSteps.value = planning.value ? 0 : plan.value?.steps?.length || 0
  railTools.value = railToolsOf.value
  railDetail.value = railDetailOf.value
  railMemory.value = rules.value.length
  railLessons.value = lessons.value.length
}

watch(
  () => [railStateText.value, railToneOf.value, plan.value?.steps?.length, railToolsOf.value, railDetailOf.value, rules.value.length, lessons.value.length],
  syncRail,
  { immediate: true, deep: true }
)

// 导航栏点「管理」→ 打开字段规则与经验记忆
watch(railMemoryRequest, (n) => {
  if (n && railScope.value === 'bridge') rulesVisible.value = true
})

const busy = computed(() => scanning.value || planning.value || running.value)
const canAuto = computed(() => !!dataset.value && !busy.value)

// ---------------- 可执行权限：手动审批 / 自动审批 / 完全访问 ----------------
const ACCESS_MODES = [
  { key: 'manual', label: '手动审批', hint: '方案出完停住，你点「确认执行」才导出' },
  { key: 'auto', label: '自动审批', hint: '方案一出就自己跑，每轮决定写进轨迹' },
  { key: 'full', label: '完全访问', hint: '不再逐单确认，直接跑到底' }
]

const access = ref('auto')
const accessBusy = ref(false)
const approving = ref(false)
const awaiting = computed(() => dataset.value?.status === 'awaiting')
const accessHint = computed(() => ACCESS_MODES.find((item) => item.key === access.value)?.hint || '')
const canSetAccess = computed(() => !busy.value && !accessBusy.value)

watch(
  () => dataset.value?.access,
  (value) => {
    if (value) access.value = value
  },
  { immediate: true }
)

async function setAccess(mode) {
  if (access.value === mode) return
  const label = ACCESS_MODES.find((item) => item.key === mode)?.label || ''
  // 还没建单（上传页）：先记着，随上传一起提交，这样才能在方案出完时停住
  if (!dataset.value) {
    access.value = mode
    show(`可执行权限：${label}（上传后生效）`)
    return
  }
  if (!canSetAccess.value) return
  accessBusy.value = true
  try {
    const res = await api.bridgeAccess(dataset.value.id, mode)
    dataset.value = res
    access.value = res.access || mode
    show(`可执行权限：${ACCESS_MODES.find((item) => item.key === access.value)?.label || label}`)
    saveSnapshot()
  } catch (err) {
    show(err.message || '设置可执行权限失败', 'err')
  } finally {
    accessBusy.value = false
  }
  // 停在手动审批的等待态时改成自动审批：不再让用户多点一次，直接按已出的方案跑
  if (awaiting.value) await approveNow()
}

// 手动审批的确认执行：方案不用再规划，点完直接跑自主循环
async function approveNow() {
  if (!dataset.value || busy.value) return
  const id = dataset.value.id
  approving.value = true
  running.value = true
  error.value = ''
  cancelling.value = false
  startTicker()
  startPolling(id)
  try {
    const res = await api.bridgeApprove(id)
    dataset.value = res
    resultTab.value = 'ok'
    if (res.result) await loadRows('ok', 1)
    saveSnapshot()
    const r = res.result || {}
    show(`已确认执行：标准表 ${r.ok_rows ?? 0} 行，异常 ${r.anomaly_rows ?? 0} 行`)
    notifyExport(r.download_dir || '')
  } catch (err) {
    error.value = err.message || '确认执行失败'
    show(error.value, 'err')
  } finally {
    approving.value = false
    running.value = false
    cancelling.value = false
    stopTicker()
    stopPolling()
    loadRecent()
  }
}

// ---------------- 追问：只问就回答，要改就改完重跑 ----------------
const chatText = ref('')
const chatBusy = ref(false)
const talks = computed(() => dataset.value?.chat || [])
const chatHints = [
  '为什么异常这么多？',
  '这一列是怎么算的？',
  '部门列不要了',
  '只保留在职员工的记录'
]

function nowText() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

async function sendChat() {
  const word = (chatText.value || '').trim()
  if (!dataset.value || chatBusy.value || busy.value || !word) return
  const id = dataset.value.id
  chatBusy.value = true
  error.value = ''
  // 先把这句话挂到界面上，等后端回答时不至于像没反应
  dataset.value.chat = [...(dataset.value.chat || []), { role: 'user', text: word, at: nowText() }]
  chatText.value = ''
  startPolling(id)
  try {
    const res = await api.bridgeChat(id, word)
    dataset.value = res
    if (res.status === 'failed') {
      const reason = (res.notes || []).slice(-1)[0] || '按这句话改失败'
      error.value = reason
      show(reason, 'err')
      return
    }
    const last = (res.chat || []).slice(-1)[0]
    if (last?.action === 'rerun' && res.result) {
      resultTab.value = 'ok'
      await loadRows('ok', 1)
      show(last.passed
        ? `按这句话改完并重跑：正常 ${res.result.ok_rows} 行 / 异常 ${res.result.anomaly_rows} 行，已达标`
        : `按这句话改完并重跑：正常 ${res.result.ok_rows} 行 / 异常 ${res.result.anomaly_rows} 行，未达标`)
      notifyExport(res.result.download_dir || '')
    }
    saveSnapshot()
  } catch (err) {
    error.value = err.message || '追问失败'
    show(error.value, 'err')
  } finally {
    chatBusy.value = false
    stopPolling()
    loadRecent()
  }
}

// ---------------- 运行日志的技术细节：点一下展开这一步的真实参数 ----------------
const openTrace = ref([])

function traceOf(event) {
  const data = event?.data
  return data && Object.keys(data).length ? data : null
}

function toggleTrace(seq) {
  const index = openTrace.value.indexOf(seq)
  if (index >= 0) openTrace.value.splice(index, 1)
  else openTrace.value.push(seq)
}

// 全自主跑一单：一个人工步骤都没有——不给目标就由后端按数据结构推断，
async function autoNow(goal = '') {
  if (!dataset.value || busy.value) return
  const id = dataset.value.id
  planning.value = true
  running.value = true
  error.value = ''
  cancelling.value = false
  startTicker()
  startPolling(id)
  try {
    const res = await api.bridgeAuto(id, (goal || '').trim())
    dataset.value = res
    resultTab.value = 'ok'
    if (res.result) await loadRows('ok', 1)
    saveSnapshot()
    // 手动审批：方案出完就停住，这时没有结果，别按“未达标交付”提示
    if (res.status === 'awaiting') {
      show('方案已生成，按「手动审批」等你确认后再执行')
      return
    }
    // 规划阶段就被取消（或中途失败）：没有结果，只说明原因，不能弹“未达标交付”
    if (res.status === 'failed') {
      const reason = (res.notes || []).slice(-1)[0] || '自主处理失败'
      error.value = reason
      show(reason, 'err')
      return
    }
    const r = res.result || {}
    const ok = r.ok_rows ?? 0
    const bad = r.anomaly_rows ?? 0
    if (r.passed) {
      show(`自主处理完成：标准表 ${ok} 行，异常 ${bad} 行，已达到验收标准（${r.rounds || 1} 轮）`)
    } else {
      show(`自主处理完成：标准表 ${ok} 行，异常 ${bad} 行，未达验收标准，已按最优方案交付并写清原因`)
    }
    // 导出文件已同步到前端目录的 downloads：右下角只提示位置，点了才打开，不挡正文
    notifyExport(r.download_dir || '')
    if (autoDownload.value && r.passed) {
      await reveal('标准表.xlsx', false)
    } else if (autoDownload.value && !r.passed) {
      show('未达验收标准，没有自动打开标准表；原因见自主过程', 'err')
    }
  } catch (err) {
    error.value = err.message || '自主处理失败'
    show(error.value, 'err')
  } finally {
    planning.value = false
    running.value = false
    cancelling.value = false
    stopTicker()
    stopPolling()
    loadRecent()
  }
}

// 取消这一单：后端只置标记，当前这一步结束后停下，已跑出的结果照常交付
async function cancelRun() {
  if (!dataset.value || cancelling.value) return
  cancelling.value = true
  try {
    const res = await api.bridgeCancel(dataset.value.id)
    show(res?.message || '已发出取消')
  } catch (err) {
    cancelling.value = false
    show(err.message || '取消失败', 'err')
  }
}

async function loadRows(kind, page) {
  if (!dataset.value?.result) return
  rowsBusy.value = true
  try {
    const target = kind === 'anomaly' ? anomalyTable : okTable
    const res = await api.bridgeRows(dataset.value.id, { kind, page, page_size: target.value.size })
    target.value = {
      columns: res.columns || [],
      rows: res.rows || [],
      total: res.total || 0,
      page,
      size: target.value.size
    }
  } catch (err) {
    show(err.message || '读取结果失败', 'err')
  } finally {
    rowsBusy.value = false
  }
}

function changeTab(name) {
  if (name === 'anomaly' && !anomalyTable.value.rows.length) loadRows('anomaly', 1)
  if (name === 'ok' && !okTable.value.rows.length) loadRows('ok', 1)
}

// ---------------- 导出位置：文件都落在前端目录的 downloads 下 ----------------
const AUTO_DOWNLOAD_KEY = 'huibiao.bridge.autoDownload'
const EXPORT_NOTE_MS = 10000
const autoDownload = ref(false)
const exportNotice = ref(null)
let exportNoteTimer = null
try {
  autoDownload.value = localStorage.getItem(AUTO_DOWNLOAD_KEY) === '1'
} catch {
  autoDownload.value = false
}
watch(autoDownload, (value) => {
  try {
    localStorage.setItem(AUTO_DOWNLOAD_KEY, value ? '1' : '0')
  } catch {
    // 隐私模式下存不了就算了，不影响本次勾选
  }
})

function stopExportNoteTimer() {
  if (exportNoteTimer) {
    window.clearTimeout(exportNoteTimer)
    exportNoteTimer = null
  }
}

// 每次提示都是一次性的：重新计时，10 秒后自动收起
function notifyExport(dir, file = '') {
  if (!dir) return
  exportNotice.value = { dir, file }
  stopExportNoteTimer()
  exportNoteTimer = window.setTimeout(() => {
    exportNoteTimer = null
    exportNotice.value = null
  }, EXPORT_NOTE_MS)
}

// 不管是自动收起、用户点「前往文件夹」还是关掉，提示没了就把计时器一起停掉
// （看的是当前值：同一拍里又设了新提示的话，新计时器要留着）
watch(exportNotice, () => {
  if (!exportNotice.value) stopExportNoteTimer()
})

// 换数据集（上传新的或从最近记录切过去）时，上一条导出提示不该再指向旧目录
watch(() => dataset.value?.id, () => { exportNotice.value = null })

// ---------------- 导出位置设置 ----------------
const exportDir = ref({ custom: '', defaultDir: '', effectiveDir: '' })
const exportDirDraft = ref('')
const exportCustom = computed(() => !!exportDir.value.custom)

async function loadExportDir() {
  try {
    const res = await api.bridgeExportDir()
    exportDir.value = {
      custom: res.custom || '',
      defaultDir: res.default_dir || '',
      effectiveDir: res.effective_dir || ''
    }
    exportDirDraft.value = res.custom || ''
  } catch (e) {
    exportDir.value = { custom: '', defaultDir: '', effectiveDir: '' }
  }
}

// 保存导出位置。已经交付的单子会带上 dataset_id，后端只搬文件、不重跑；
async function saveExportDir(value = exportDirDraft.value) {
  if (busy.value) {
    show('正在扫描或处理，完成后才能更换导出位置', 'err')
    return
  }
  const dir = String(value || '').trim()
  const moveId = result.value ? dataset.value?.id || '' : ''
  try {
    const res = await api.bridgeSetExportDir(dir, moveId)
    exportDir.value = {
      custom: res.custom || '',
      defaultDir: res.default_dir || '',
      effectiveDir: res.effective_dir || ''
    }
    exportDirDraft.value = res.custom || ''
    if (res.error) {
      show(res.error, 'err')
      return
    }
    if (res.moved && res.moved_dir) {
      // 已交付的这一单跟着搬过去，方案与结果都不变
      if (dataset.value?.result) dataset.value.result.download_dir = res.moved_dir
      notifyExport(res.moved_dir, dataset.value?.result?.artifacts?.[0] || '')
      show(`本次导出文件已移到 ${res.moved_dir}`)
      return
    }
    show(res.custom ? `导出位置已改为 ${res.custom}` : '已恢复默认导出位置')
  } catch (err) {
    show(err.message || '保存导出位置失败', 'err')
  }
}

function resetExportDir() {
  saveExportDir('')
}

// ---------------- 目录选择器（资源管理器式） ----------------
const pickerOpen = ref(false)
const picker = ref({ path: '', parent: '', entries: [], loading: false, error: '' })
const pickerPlaces = ref({ defaultDir: '', dirs: [] })
const newFolderRef = ref(null)

// 面包屑：把 E:\DG-baijing2026\xingchen-huibiao 拆成可点的层级（盘符那级带结尾分隔符）
const pickerCrumbs = computed(() => {
  const path = picker.value.path || ''
  if (!path) return []
  const sep = path.includes('/') ? '/' : '\\'
  const parts = path.split(/[\\/]+/).filter(Boolean)
  const crumbs = []
  let acc = ''
  parts.forEach((part, index) => {
    if (index === 0) acc = /^[A-Za-z]$/.test(part) ? `${part}:${sep}` : `${part}${sep}`
    else acc = acc.endsWith(sep) ? `${acc}${part}` : `${acc}${sep}${part}`
    crumbs.push({ name: index === 0 && /^[A-Za-z]$/.test(part) ? `${part}:` : part, path: acc })
  })
  return crumbs
})

// 常用位置只在第一次打开时读一次：默认导出目录 + 各磁盘根
async function loadPlaces() {
  if (pickerPlaces.value.dirs.length) return
  try {
    const res = await api.bridgeBrowse('')
    const defaultDir = res.default_dir || ''
    pickerPlaces.value = {
      defaultDir,
      dirs: (res.entries || [])
        .filter((entry) => !defaultDir || entry.path !== defaultDir)
        .map((entry) => ({ name: entry.name, path: entry.path }))
    }
  } catch (err) {
    pickerPlaces.value = { defaultDir: '', dirs: [] }
  }
}

// 交付后换位置：结果提示里的「更改位置」直接开选择器，点「确定」就搬文件，不重跑
const relocating = ref(false)
const pickerTitle = computed(() => (relocating.value ? '更改导出位置' : '选择文件夹'))

function openRelocate() {
  openPicker()
  relocating.value = true
}

async function openPicker() {
  relocating.value = false
  pickerOpen.value = true
  newFolderOpen.value = false
  await loadPlaces()
  // 打开就落在上次选的目录；没设过就落在默认导出目录，不用从「此电脑」一层层点进去
  const start = exportDirDraft.value || exportDir.value.custom || pickerPlaces.value.defaultDir
  await loadPicker(start)
}

// 取消或关掉选择器时把「这次是来搬文件的」状态清掉，下次点「选择文件夹」不会误搬
watch(pickerOpen, (open) => {
  if (!open) relocating.value = false
})

async function loadPicker(path) {
  picker.value = { ...picker.value, loading: true, error: '' }
  try {
    const res = await api.bridgeBrowse(path || '')
    picker.value = {
      path: res.path || '',
      parent: res.parent || '',
      entries: res.entries || [],
      loading: false,
      error: res.error || ''
    }
  } catch (err) {
    picker.value = { ...picker.value, loading: false, error: err.message || '读目录失败' }
  }
}

function pickerUp() {
  if (!picker.value.path) return
  loadPicker(picker.value.parent || '')
}

// 新建文件夹：和资源管理器一样，在当前目录下建，建完直接进去
const newFolderOpen = ref(false)
const newFolderName = ref('')
const newFolderBusy = ref(false)

function toggleNewFolder() {
  newFolderOpen.value = !newFolderOpen.value
  if (newFolderOpen.value) {
    newFolderName.value = ''
    nextTick(() => newFolderRef.value?.focus?.())
  }
}

function cancelNewFolder() {
  newFolderOpen.value = false
  newFolderName.value = ''
}

async function createFolder() {
  const name = newFolderName.value.trim()
  if (!name || newFolderBusy.value) return
  if (!picker.value.path) {
    show('请先进入一个文件夹，再在里面新建', 'err')
    return
  }
  newFolderBusy.value = true
  try {
    const res = await api.bridgeMkdir(picker.value.path, name)
    if (!res.path) {
      show(res.error || '新建文件夹失败', 'err')
      return
    }
    if (res.error) show(res.error)
    newFolderOpen.value = false
    newFolderName.value = ''
    // 建完直接进入新目录，用户再点「就存到这个文件夹」即可
    await loadPicker(res.path)
  } catch (err) {
    show(err.message || '新建文件夹失败', 'err')
  } finally {
    newFolderBusy.value = false
  }
}

// 右键菜单 + 就地重命名：和资源管理器一样，右键某个文件夹改名，改完留在当前目录
const folderMenu = ref({ open: false, x: 0, y: 0, entry: null })
const renamingPath = ref('')
const renameName = ref('')
const renameBusy = ref(false)
// 输入框在 v-for 里，用函数 ref 拿元素，普通 ref 会被收成数组
const renameRef = ref(null)

function setRenameRef(el) {
  if (el) renameRef.value = el
}

function openFolderMenu(event, entry) {
  // 右键菜单要贴着鼠标出现，同时别被窗口边缘裁掉
  const x = Math.min(event.clientX, window.innerWidth - 168)
  const y = Math.min(event.clientY, window.innerHeight - 96)
  folderMenu.value = { open: true, x, y, entry }
}

function closeFolderMenu() {
  folderMenu.value = { ...folderMenu.value, open: false }
}

function startRename(entry) {
  closeFolderMenu()
  renamingPath.value = entry.path
  renameName.value = entry.name
  nextTick(() => {
    renameRef.value?.focus?.()
    renameRef.value?.select?.()
  })
}

function cancelRename() {
  renamingPath.value = ''
  renameName.value = ''
}

async function commitRename(entry) {
  const name = renameName.value.trim()
  if (!name || renameBusy.value) return
  if (name === entry.name) {
    cancelRename()
    return
  }
  renameBusy.value = true
  try {
    const res = await api.bridgeRename(entry.path, name)
    if (!res.path) {
      show(res.error || '重命名失败', 'err')
      return
    }
    cancelRename()
    // 如果改的正好是「已保存的自定义导出目录」，跟着指向新路径，别让设置失效
    const same = (a, b) => String(a || '').replace(/\//g, '\\').toLowerCase() === String(b || '').replace(/\//g, '\\').toLowerCase()
    if (same(exportDir.value.custom, entry.path)) {
      exportDirDraft.value = res.path
      saveExportDir(res.path)
    }
    await loadPicker(picker.value.path)
  } catch (err) {
    show(err.message || '重命名失败', 'err')
  } finally {
    renameBusy.value = false
  }
}

function pickFolder(path) {
  if (!path) return
  pickerOpen.value = false
  // 只填回输入框，点「保存」才真正生效（已交付的单子这时才会把文件搬过去）
  exportDirDraft.value = path
  // 从结果区的「更改位置」进来的，确定即生效：只把这一单的文件搬过去，不重新处理
  if (relocating.value) {
    relocating.value = false
    saveExportDir(path)
  }
}

// ---------------- 交付后继续对话 ----------------

// 打开导出文件所在文件夹；dismiss=true 表示这次是用户主动点的。
async function reveal(artifact = '', dismiss = true) {
  if (!dataset.value) return
  if (dismiss) {
    exportNotice.value = null
    jumpToResult(artifact)
  }
  try {
    const res = await api.bridgeReveal(dataset.value.id, artifact)
    if (res.opened) {
      if (dismiss && artifact) show(`已在文件夹中定位到 ${artifact}`)
    } else {
      // 没能自动打开（比如非 Windows）：把路径留在提示里，让用户自己复制
      notifyExport(res.dir, res.file || '')
      show(`没能自动打开文件夹，路径：${res.dir}`, 'err')
    }
  } catch (err) {
    show(err.message || '打开文件夹失败', 'err')
  }
}

// 点开目录后回到本次「交付结果」：标准表落在标准表标签，异常清单落在异常标签
const resultCardRef = ref(null)
// 已经停在 04 时滚动看不出变化，再用一次描边闪动告诉用户「要看的就是这块」
const resultFlash = ref(false)
let flashTimer = null

function jumpToResult(artifact = '') {
  if (!result.value) return
  const tab = /异常/.test(artifact) ? 'anomaly' : 'ok'
  if (resultTab.value !== tab) changeTab(tab)
  resultTab.value = tab
  nextTick(() => {
    resultCardRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
    if (flashTimer) window.clearTimeout(flashTimer)
    // 先关再开，连着点两个文件时也能重新闪一次（只把 true 再赋一遍不会重播动画）
    resultFlash.value = false
    nextTick(() => {
      resultFlash.value = true
    })
    flashTimer = window.setTimeout(() => {
      resultFlash.value = false
      flashTimer = null
    }, 1100)
  })
}

// ---------------- 字段对齐：改一次就存成规则，下次同类报表自动复用 ----------------
const fieldLabel = (field) => standardFields.value.find((item) => item.field === field)?.label || field
const tableFile = (tableId) => dataset.value?.tables?.find((item) => item.id === tableId)?.file || ''

async function changeAlign(row, field) {
  if (!dataset.value || !field) return
  row.field = field
  row.label = fieldLabel(field)
  row.origin = '手动'
  try {
    await api.bridgeAddRule(field, row.column)
    rules.value = await api.bridgeRules()
    show(`已保存为自定义规则：${row.column} → ${fieldLabel(field)}，重新自主处理时会优先按它对齐`)
  } catch (err) {
    show(err.message || '保存规则失败', 'err')
  }
}

async function addRule() {
  const isCustom = ruleDraft.value.standard === CUSTOM_FIELD
  // 自定义时用输入框里写的名字当目标字段，其余情况用下拉框选中的标准字段
  const standard = isCustom ? ruleDraft.value.custom.trim() : ruleDraft.value.standard
  const alias = ruleDraft.value.alias.trim()
  if (!standard) {
    show('先填一下自定义字段名', 'err')
    return
  }
  if (!alias) {
    show('请填写原始列名', 'err')
    return
  }
  try {
    rules.value = await api.bridgeAddRule(standard, alias)
    ruleDraft.value.alias = ''
    if (isCustom) backToStandard()
    show('规则已保存，下次遇到同一列名将直接套用')
  } catch (err) {
    show(err.message || '保存规则失败', 'err')
  }
}

// 切到「自定义」后把光标送进输入框，少点一次
function onStandardChange(value) {
  if (value !== CUSTOM_FIELD) return
  nextTick(() => customFieldRef.value?.focus())
}

// 从自定义输入框退回标准字段下拉
function backToStandard() {
  ruleDraft.value.standard = 'emp_no'
  ruleDraft.value.custom = ''
}

async function deleteRule(rule) {
  try {
    rules.value = await api.bridgeDeleteRule(rule.alias)
    show('规则已删除')
  } catch (err) {
    show(err.message || '删除规则失败', 'err')
  }
}

// ---------------- 最近的数据集 / 现场恢复 ----------------
async function loadRecent() {
  try {
    recent.value = await api.bridgeDatasets()
  } catch (err) {
    recent.value = []
  }
}

function toggleRecent() {
  recentOpen.value = !recentOpen.value
  if (recentOpen.value) {
    recentChecked.value = []
    loadRecent()
  }
}

// 最近记录里的勾选与清理：可以全清，也可以只清选中的几条
const recentChecked = ref([])
const recentBusy = ref(false)

const allRecentChecked = computed(
  () => recent.value.length > 0 && recentChecked.value.length === recent.value.length
)

function isRecentChecked(id) {
  return recentChecked.value.includes(id)
}

function toggleRecentOne(id) {
  const index = recentChecked.value.indexOf(id)
  if (index >= 0) recentChecked.value.splice(index, 1)
  else recentChecked.value.push(id)
}

function toggleRecentAll() {
  recentChecked.value = allRecentChecked.value ? [] : recent.value.map((item) => item.id)
}

async function removeRecent(ids) {
  if (recentBusy.value) return
  const target = ids || []
  const count = target.length ? target.length : recent.value.length
  if (!count) return
  const tip = target.length
    ? `确定删除选中的 ${count} 条最近记录？记录会从列表移除，已导出的表仍保留在导出目录。`
    : `确定清空全部 ${count} 条最近记录？记录会从列表移除，已导出的表仍保留在导出目录。`
  if (!window.confirm(tip)) return
  recentBusy.value = true
  try {
    const res = await api.bridgeDelete(target)
    recent.value = res?.items || []
    recentChecked.value = []
    // 当前打开的数据集刚好被删掉时，页面不能继续显示已经不存在的数据
    if (dataset.value?.id && (target.length ? target.includes(dataset.value.id) : true)) {
      resetDataset()
    }
    const kept = Number(res?.kept_files || 0)
    show(
      kept
        ? `已移除 ${res?.deleted ?? count} 条记录，${kept} 个导出文件仍保留在导出目录`
        : `已移除 ${res?.deleted ?? count} 条最近记录`
    )
  } catch (err) {
    show(err.message || '删除失败', 'err')
  } finally {
    recentBusy.value = false
    loadRecent()
  }
}

// 弹层挂在卡片头部，点其他地方要能收起，否则会一直挡着上传区
function onDocClick() {
  recentOpen.value = false
  closeFolderMenu()
}

async function openDataset(item) {
  if (!item?.id) return
  recentOpen.value = false
  try {
    const res = await api.bridgeDataset(item.id)
    dataset.value = res
    instruction.value = res.instruction || ''
    okTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    anomalyTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    resultTab.value = 'ok'
    saveSnapshot()
    if (res.result) loadRows('ok', 1)
  } catch (err) {
    show(err.message || '打开数据集失败', 'err')
  }
}

function resetDataset() {
  dataset.value = null
  instruction.value = ''
  goalOpen.value = false
  resultTab.value = 'ok'
  pendingFiles.value = []
  datasetName.value = ''
  error.value = ''
  // 换数据集时权限回到默认的自动审批：上一单选的手动审批不该带到新的一单
  access.value = 'auto'
  clearSnapshot()
  loadRecent()
}

function saveSnapshot() {
  try {
    sessionStorage.setItem(SNAPSHOT_KEY, JSON.stringify({
      id: dataset.value?.id || '',
      instruction: instruction.value,
      at: Date.now()
    }))
  } catch (e) {
    // 隐私模式下 sessionStorage 可能不可用，忽略即可
  }
}

function clearSnapshot() {
  try {
    sessionStorage.removeItem(SNAPSHOT_KEY)
  } catch (e) {
    // 同上
  }
}

async function restoreSnapshot() {
  let snapshot
  try {
    snapshot = JSON.parse(sessionStorage.getItem(SNAPSHOT_KEY) || 'null')
  } catch (e) {
    snapshot = null
  }
  if (!snapshot?.id || Date.now() - Number(snapshot.at || 0) > 12 * 3600 * 1000) return
  try {
    const res = await api.bridgeDataset(snapshot.id)
    dataset.value = res
    instruction.value = snapshot.instruction || res.instruction || ''
    if (res.result) loadRows('ok', 1)
  } catch (e) {
    clearSnapshot()
  }
}

// ---------------- 展示辅助 ----------------
const modeBadge = computed(() => {
  if (!plan.value) return ''
  return plan.value.mode === 'llm' ? '大模型参与规划' : '规则规划'
})

const alignCount = computed(() => plan.value?.aligns?.length || 0)
const computeCount = computed(() => plan.value?.computes?.length || 0)
const outputCount = computed(() => plan.value?.outputColumns?.length || 0)
const crossSystemFields = computed(() => {
  const map = new Map()
  for (const align of plan.value?.aligns || []) {
    if (!map.has(align.label)) map.set(align.label, new Set())
    map.get(align.label).add(tableFile(align.tableId))
  }
  return Array.from(map.entries())
    .filter(([, files]) => files.size > 1)
    .map(([label, files]) => `${label}（${Array.from(files).join(' / ')}）`)
})

const elapsedText = (ms) => (!ms ? '' : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`)

// 自主循环每一轮的结论：达标 / 有改善 / 已回滚 / 无改动 / 到上限
const verdictText = (verdict) => ({
  passed: '已达标',
  improved: '有改善，继续',
  rolled_back: '已回滚',
  no_change: '没有可改的地方',
  exhausted: '到轮次上限'
}[verdict] || verdict)

// 主表 / 关联表 / 备选表：备选表是同类表里没参与计算的那张，单独标一下，免得对不上行数
const roleOf = (tableId) => plan.value?.sources?.find((item) => item.table_id === tableId)?.role || ''

function toggleTable(id) {
  const index = openTables.value.indexOf(id)
  if (index >= 0) openTables.value.splice(index, 1)
  else openTables.value.push(id)
}

function rowsOf(table) {
  const list = table?.rows || []
  return list.map((row, index) => ({ ...row, __key: `${index}-${Object.values(row)[0] || ''}` }))
}

onMounted(async () => {
  railScope.value = 'bridge'
  syncRail()
  // 离线时不发请求：这些接口必然失败，只会留下一串错误提示
  if (!offlineMode.value) {
    try {
      standardFields.value = await api.bridgeStandardFields()
    } catch (e) {
      standardFields.value = []
    }
    loadRecent()
    loadRules()
    loadExportDir()
  }
  // 离线时恢复现场也拉不到数据集，白跑一次请求；等连通后再恢复
  if (!offlineMode.value) restoreSnapshot()
  document.addEventListener('click', onDocClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  stopTicker()
  stopPolling()
  stopExportNoteTimer()
  if (flashTimer) {
    window.clearTimeout(flashTimer)
    flashTimer = null
  }
  saveSnapshot()
  // 只有状态条还归桥接模式时才清空；已经切回普通模式的话，那边会自己重写
  if (railScope.value === 'bridge') {
    railTools.value = []
    railSteps.value = 0
    railDetail.value = ''
    railMemory.value = 0
    railLessons.value = 0
    railState.value = '待交接'
    railTone.value = 'idle'
  }
})

async function loadRules() {
  try {
    rules.value = await api.bridgeRules()
  } catch (e) {
    rules.value = []
  }
  loadLessons()
}

async function loadLessons() {
  try {
    lessons.value = await api.bridgeExperiences()
  } catch (e) {
    lessons.value = []
  }
}

async function deleteLesson(item) {
  try {
    lessons.value = await api.bridgeDeleteExperience(item.id)
    show('已删除这条经验记忆')
  } catch (err) {
    show(err.message || '删除失败', 'err')
  }
}

// 人工给经验打分。说「不适用」两次且从没被确认过的经验会下线，不再参与同类数据集的复用——
async function feedbackLesson(item, useful) {
  try {
    lessons.value = await api.bridgeFeedbackExperience(item.id, useful)
    show(useful ? '已标记为有用，可信度提高' : '已标记为不适用，连续两次将停用')
  } catch (err) {
    show(err.message || '标记失败', 'err')
  }
}
</script>

<template>
  <div class="bg">
    <!-- 网格放在内层：外层只做「内容区宽度容器」，让下面的双栏能用容器查询判断， -->
    <div class="bg-grid">
    <!-- 正在看某一单时断网：上面那份说明卡在「未上传」区块里，这里补一条， -->
    <div v-if="offlineMode && dataset" class="bg-offline is-bar">
      <span class="offline-chip" :title="OFFLINE_HINT"><i />离线模式</span>
      <span>这单的重新规划与执行都要连后端，离线时先看已有结果；继续处理请到普通模式或表格智作。</span>
      <button type="button" class="bg-link" @click="goStandardMode">切到普通模式</button>
    </div>

    <!-- ==================== 0. 还没有数据集：交付入口 ==================== -->
    <section v-if="!dataset" class="bg-card is-upload">
      <header class="bg-card-head">
        <div>
          <span class="bg-kicker">跨系统表格桥接</span>
          <h2>提交整个数据集，剩下的它自己来</h2>
          <p>上传压缩包或整个导出目录即可：智能体自己定目标、自己规划、自己执行，跑完自己对验收标准，不达标自己改一处重跑，交付标准表时把每一轮的决定都写清。</p>
        </div>
        <!-- 最近记录：图标放在「核心能力」左边，悬浮提示、点击展开 -->
        <div class="bg-head-side">
          <!-- 最近记录要先向后端拉清单；离线时按钮留在那里只会点出一个空列表 -->
          <button
            v-if="!offlineMode"
            class="bg-recent-btn"
            type="button"
            title="最近记录"
            aria-label="最近记录"
            @click.stop="toggleRecent"
          >
            <Icon name="history" :size="14" />
          </button>
          <span class="bg-core">核心能力</span>

          <div v-if="recentOpen && !offlineMode" class="bg-recent-pop" @click.stop>
            <div class="bg-recent-pop-head">
              <b>最近记录</b>
              <span>{{ recent.length }} 个数据集</span>
            </div>
            <div v-if="recent.length" class="bg-recent-pop-list">
              <label class="bg-recent-row is-head">
                <input type="checkbox" :checked="allRecentChecked" @change="toggleRecentAll" />
                <span>全选</span>
              </label>
              <label
                v-for="item in recent"
                :key="item.id"
                class="bg-recent-row"
                :class="{ 'is-on': isRecentChecked(item.id) }"
              >
                <input
                  type="checkbox"
                  :checked="isRecentChecked(item.id)"
                  @change="toggleRecentOne(item.id)"
                />
                <span class="bg-recent-main" @click.stop="openDataset(item)">
                  <b :title="item.name">{{ item.name }}</b>
                  <i>
                    {{ item.created_at }} · {{ item.tables?.length || 0 }} 张表 ·
                    {{ item.instruction ? '已规划' : '未规划' }}
                  </i>
                </span>
              </label>
            </div>
            <p v-else class="bg-recent-empty">还没有记录，扫描一个数据集后会出现在这里。</p>
            <div v-if="recent.length" class="bg-recent-pop-foot">
              <span class="bg-recent-sel">已选 {{ recentChecked.length }} / {{ recent.length }}</span>
              <button
                type="button"
                class="bg-recent-act"
                :disabled="recentBusy || !recentChecked.length"
                @click="removeRecent(recentChecked.slice())"
              >
                删除所选
              </button>
              <button
                type="button"
                class="bg-recent-act is-danger"
                :disabled="recentBusy"
                @click="removeRecent([])"
              >
                清空全部
              </button>
            </div>
          </div>
        </div>
      </header>

      <div class="bg-card-body">
        <!-- 离线：智能体模式的扫描、规划、执行全部要后端和大模型， -->
        <div v-if="offlineMode" class="bg-offline">
          <div class="bg-offline-head">
            <span class="offline-chip" :title="OFFLINE_HINT"><i />离线模式</span>
            <b>智能体模式需要联网</b>
          </div>
          <p>
            这一页的扫描、规划、跨表执行和追问都依赖后端与大模型，断网时无法运行。
            想继续干活请切到「普通模式」：表格在本地解析、字段按内置词典对齐，可直接下载 CSV；
            「表格智作」页也能离线建表、合并并导出 CSV。
          </p>
          <div class="bg-offline-foot">
            <button type="button" class="bg-primary" @click="goStandardMode">切到普通模式（离线可用）</button>
            <button type="button" class="bg-link" @click="retryConnection">重新检测连接</button>
          </div>
        </div>

        <template v-else>
        <div class="bg-drop-grid">
          <el-upload
            ref="uploadRef"
            class="bg-drop"
            drag
            multiple
            :auto-upload="false"
            :show-file-list="false"
            accept=".zip,.xlsx,.xls,.csv"
            :on-change="onFileChange"
            :disabled="scanning"
          >
            <div class="bg-drop-inner">
              <span class="bg-drop-icon"><Icon name="database" :size="19" /></span>
              <span class="bg-drop-text">
                <b>拖入压缩包或表格文件</b>
                <span>支持 .zip / .xlsx / .xls / .csv，可一次提交多个</span>
              </span>
            </div>
          </el-upload>

          <button class="bg-folder" type="button" :disabled="scanning" @click="folderInput?.click()">
            <span class="bg-drop-icon"><Icon name="folder" :size="18" /></span>
            <span class="bg-drop-text">
              <b>选择整个文件夹</b>
              <span>将整个导出目录一并提交</span>
            </span>
          </button>
          <input
            ref="folderInput"
            class="bg-hidden"
            type="file"
            webkitdirectory
            directory
            multiple
            @change="onFolderPick"
          />
        </div>

        <div v-if="pendingFiles.length" class="bg-pending">
          <div class="bg-pending-head">
            <span><Icon name="file" :size="13" /> {{ pendingText }}</span>
            <button class="bg-link" type="button" @click="pendingFiles = []">全部移除</button>
          </div>
          <ul class="bg-pending-list">
            <li v-for="(file, index) in pendingFiles" :key="file.name + file.size">
              <span :title="file.name">{{ file.name }}</span>
              <em>{{ Math.max(1, Math.round(file.size / 1024)) }} KB</em>
              <button class="bg-x" type="button" title="移除" @click="removePending(index)">
                <Icon name="close" :size="11" :stroke="2.2" />
              </button>
            </li>
          </ul>
        </div>

        <div class="bg-naming">
          <span class="bg-label">数据集名称</span>
          <el-input v-model="datasetName" placeholder="例如 2026 年 4 月人事薪资数据集" />
          <button class="bg-primary" :disabled="!canScan" @click="startScan">
            {{ scanning ? '正在扫描…' : '扫描这个数据集' }}
          </button>

          <!-- 还没跑过任何数据集时，导出位置收在这一行右侧的空白处，不单独占一整行 -->
          <section class="bg-dest is-inline">
            <span class="bg-dest-label">导出位置</span>
            <el-input
              v-model="exportDirDraft"
              :disabled="busy"
              :placeholder="`默认：${exportDir.defaultDir || '前端目录下的 downloads'}`"
              @keyup.enter="saveExportDir()"
            />
            <button class="bg-dest-btn" type="button" :disabled="busy" @click="openPicker()">选择文件夹</button>
            <button class="bg-dest-btn" type="button" :disabled="busy" @click="saveExportDir()">保存</button>
            <button
              v-if="exportCustom"
              class="bg-dest-reset"
              type="button"
              :disabled="busy"
              @click="resetExportDir()"
            >
              恢复默认
            </button>
            <span v-if="busy" class="bg-dest-tip">处理中不可更改</span>
          </section>
        </div>

        <!-- 可执行权限：上传前就定下来，因为上传后它会立刻开始规划 -->
        <div class="bg-access is-upload">
          <span class="bg-label">可执行权限</span>
          <div class="bg-access-modes">
            <button
              v-for="mode in ACCESS_MODES"
              :key="mode.key"
              class="bg-access-mode"
              :class="{ 'is-on': access === mode.key }"
              type="button"
              :title="mode.hint"
              :disabled="scanning"
              @click="setAccess(mode.key)"
            >{{ mode.label }}</button>
          </div>
          <!-- 权限和「跑完要不要自动打开文件夹」都在这行定：这两件事都是开跑前就该想好的， -->
          <div class="bg-access-note">
            <span class="bg-access-hint">{{ accessHint }}</span>
            <label class="bg-auto-save" title="达标时自动打开标准表所在文件夹；未达标只提示，不自动打开">
              <input v-model="autoDownload" type="checkbox" :disabled="scanning" />
              跑完自动打开文件夹
            </label>
          </div>
        </div>

        <!-- 业务目标：默认折叠，写了就照这句跑；不填就由智能体按数据结构自主规划 -->
        <div class="bg-fold" :class="{ open: goalOpen }">
          <button class="bg-fold-head" type="button" @click="goalOpen = !goalOpen">
            <Icon name="spark" :size="13" />
            <b>业务目标</b>
            <span>{{ instruction.trim() ? instruction : '可选 · 不填由智能体自主规划' }}</span>
            <Icon name="down" :size="12" class="bg-fold-arrow" />
          </button>
          <div v-show="goalOpen" class="bg-fold-body">
            <el-input
              ref="instructionRef"
              v-model="instruction"
              type="textarea"
              :rows="3"
              maxlength="600"
              show-word-limit
              :disabled="scanning"
              placeholder="例如：把 HR 入职表和 ERP 薪资表做桥接，计算每个员工应发工资，入职信息缺失的进异常清单"
            />
            <div class="bg-samples">
              <span class="bg-label">示例</span>
              <button
                v-for="text in exampleHints"
                :key="text"
                class="bg-sample"
                type="button"
                :title="text"
                @click="useExample(text)"
              >
                {{ text.slice(0, 26) }}…
              </button>
            </div>
          </div>
        </div>

        <p v-if="error" class="bg-error">{{ error }}</p>

        <!-- 上传页也不铺长文：8 个环节收成一行，想看细节再点开 -->
        <button class="bg-more" type="button" @click="flowOpen = !flowOpen">
          <Icon name="down" :size="12" class="bg-sec-arrow" :class="{ 'is-open': flowOpen }" />
          <b>{{ flowOpen ? '收起自主流程' : '它自己会做哪 8 件事' }}</b>
          <span>扫描 → 解析 → 对齐 → 定目标 → 规划 → 执行 → 自检 → 交付</span>
        </button>

        <div v-show="flowOpen" class="bg-tips">
          <ol>
            <li>扫描检索：遍历数据集目录，按文件名与表头判定每张表的业务归属</li>
            <li>表格解析：读取各表的表头、行数与样例值，缓存表结构供后续复用</li>
            <li>字段对齐：将各系统中含义相同、命名不同的字段对齐到统一标准字段；优先复用经验与自定义规则</li>
            <li>自主定目标：不写指令也行，按数据结构推断这单要做什么，同类数据集会沿用上次达标的目标</li>
            <li>自主规划：自己排关联键、计算口径、筛选条件、异常检查与输出列，不需要人工确认</li>
            <li>自主执行：跨表关联、聚合计算、条件筛选，命中检查规则的行单独隔离；先把工号/姓名里的全角空格等脏值自动修干净</li>
            <li>自我验收：跑完对照验收标准自己检查，正常行占比不达标就自己改一处重跑，改完没变好自动回滚，最多 3 轮</li>
            <li>标准化交付：按目标系统的列顺序输出标准表与异常清单，并把这次配置沉淀成经验</li>
          </ol>
        </div>
        </template>
      </div>
    </section>

    <!-- ==================== 1. 数据集已扫描：常驻运行条 + 折叠区块 ==================== -->
    <template v-else>
      <!-- 常驻运行条：谁、跑到哪、结果几行、下一步点哪，都在这一条里。 -->
      <header class="bg-runbar">
        <div class="bg-runbar-id">
          <span class="bg-runbar-title" :title="dataset.name">{{ dataset.name }}</span>
          <span class="bg-status" :class="`is-${statusTone}`"><i />{{ statusText }}</span>
        </div>
        <div class="bg-runbar-stats">
          <span><b>{{ dataset.tables?.length || 0 }}</b>表</span>
          <span><b>{{ totalRows }}</b>行</span>
          <span v-if="result"><b>{{ result.ok_rows }}</b>正常</span>
          <span v-if="result" :class="{ 'is-warn': result.anomaly_rows > 0 }"><b>{{ result.anomaly_rows }}</b>异常</span>
          <span v-if="result"><b>{{ result.rounds || 1 }}</b>轮</span>
          <span v-if="dataset.skipped?.length" class="is-muted" :title="`${dataset.skipped.length} 张表与原表重复，未参与计算`">
            <b>{{ dataset.skipped.length }}</b>重复
          </span>
        </div>
        <div class="bg-runbar-actions">
          <!-- 匹配规则与执行都要连后端；离线只留「换数据集」，别摆点了没反应的按钮 -->
          <button v-if="!offlineMode" class="bg-ghost" type="button" @click="rulesVisible = true">
            <Icon name="sliders" :size="13" /> 匹配规则
          </button>
          <button class="bg-ghost" type="button" @click="resetDataset">换数据集</button>
          <button v-if="offlineMode" class="bg-ghost" type="button" @click="goStandardMode">普通模式可用</button>
          <button v-if="awaiting && !offlineMode" class="bg-primary" :disabled="!canAuto" @click="approveNow">
            <Icon name="shield" :size="14" />
            {{ approving ? '执行中…' : '确认执行' }}
          </button>
          <button v-else-if="!offlineMode" class="bg-primary" :disabled="!canAuto" @click="autoNow(goalText)">
            <Icon name="play" :size="14" />
            {{ busy ? '自主处理中…' : result ? '重新自主处理' : '开始自主处理' }}
          </button>
        </div>
      </header>

      <section class="bg-card is-data">
        <button class="bg-sec-row" type="button" @click="dataOpen = !dataOpen">
          <span class="bg-sec-no">01</span>
          <b>数据集</b>
          <span class="bg-sec-text" :title="dataBrief">
            {{ dataset.tables?.length || 0 }} 张表 · 共 {{ totalRows }} 行
            <em v-if="dataset.skipped?.length">· {{ dataset.skipped.length }} 张重复表已跳过</em>
          </span>
          <span class="bg-sec-id">{{ dataset.id }}</span>
          <Icon name="down" :size="12" class="bg-sec-arrow" :class="{ 'is-open': dataOpen }" />
        </button>

        <div v-show="dataOpen" class="bg-card-body">
          <ul class="bg-tables">
            <li v-for="table in dataset.tables" :key="table.id" class="bg-table">
              <div class="bg-table-main">
                <span class="bg-table-id">{{ table.id }}</span>
                <div class="bg-table-meta">
                  <b>{{ table.file }}</b>
                  <span>{{ table.rows }} 行 · {{ table.columns?.length || 0 }} 列</span>
                </div>
                <el-tag size="small" effect="plain" :type="table.kind === 'unknown' ? 'info' : 'success'">
                  {{ table.kind_label }}
                </el-tag>
                <span v-if="roleOf(table.id) === '备选表'" class="bg-alt">备选表</span>
                <span v-else-if="roleOf(table.id) === '主表'" class="bg-role">主表</span>
                <button class="bg-link" type="button" @click="toggleTable(table.id)">
                  {{ openTables.includes(table.id) ? '收起字段' : '看字段' }}
                </button>
              </div>
              <div v-if="openTables.includes(table.id)" class="bg-table-cols">
                <span v-for="column in table.columns" :key="column">
                  {{ column }}<i>{{ table.sample?.[column] ?? '' }}</i>
                </span>
              </div>
            </li>
            <!-- 被判重跳过的表：明确列出来并说明理由，免得用户以为漏扫了一张 -->
            <li
              v-for="(item, index) in dataset.skipped || []"
              :key="`skipped-${index}`"
              class="bg-table is-skipped"
            >
              <div class="bg-table-main">
                <span class="bg-table-id is-skipped">×</span>
                <div class="bg-table-meta">
                  <b>{{ item.file }}</b>
                  <span>{{ item.rows }} 行 · {{ item.columns?.length || 0 }} 列 · 与 {{ item.duplicate_of }} 重复，未参与计算</span>
                </div>
                <el-tag size="small" effect="plain" type="info">{{ item.kind_label }}</el-tag>
                <span class="bg-alt">重复已跳过</span>
              </div>
              <p class="bg-table-reason">{{ item.reason }}</p>
            </li>
          </ul>

          <p v-for="note in dataset.notes || []" :key="note" class="bg-note">{{ note }}</p>
        </div>
      </section>

      <!-- ==================== 2. 目标与验收（不需要人写指令） ==================== -->
      <section class="bg-card is-goal">
        <div class="bg-sec-row is-static">
          <span class="bg-sec-no">02</span>
          <b>目标与验收</b>
          <span class="bg-sec-text" :title="goalBrief">
            {{ plan?.goal ? '目标已确定' : '智能体正在按数据结构推断' }}
            <em v-if="acceptance.length">· {{ acceptance.length }} 条验收标准</em>
          </span>
          <span v-if="result" class="bg-badge" :class="result.passed ? 'is-ok' : 'is-warn'">
            {{ result.passed ? '已达标' : '未达标' }}
          </span>
        </div>
        <div class="bg-card-body is-tight">
          <!-- 可执行权限：决定了方案出来之后要不要等人点头；默认自动审批 -->
          <div class="bg-access">
            <span class="bg-label">可执行权限</span>
            <div class="bg-access-modes">
              <button
                v-for="mode in ACCESS_MODES"
                :key="mode.key"
                class="bg-access-mode"
                :class="{ 'is-on': access === mode.key }"
                type="button"
                :title="mode.hint"
                :disabled="!canSetAccess"
                @click="setAccess(mode.key)"
              >{{ mode.label }}</button>
            </div>
            <span class="bg-access-hint">{{ accessHint }}</span>
          </div>

          <!-- 手动审批停在这一步：方案已经在了，点确认才开始执行导出 -->
          <div v-if="awaiting" class="bg-approve">
            <div>
              <b>方案已生成，等你确认执行</b>
              <span>{{ plan?.summary || '按当前方案执行跨表关联、计算与异常隔离' }}</span>
            </div>
            <div class="bg-approve-actions">
              <button class="bg-ghost" type="button" @click="setAccess('auto')">改为自动审批</button>
              <button class="bg-primary" type="button" :disabled="!canAuto" @click="approveNow">
                {{ approving ? '执行中…' : '确认执行' }}
              </button>
            </div>
          </div>

          <div class="bg-goal">
            <span class="bg-label">本次目标{{ plan?.goal ? '' : '（智能体推断）' }}</span>
            <p>{{ goalText || '智能体正在按数据结构推断…' }}</p>
          </div>

          <div v-if="acceptance.length" class="bg-accept">
            <Icon name="shield" :size="13" />
            <ul class="bg-list is-inline" :class="{ 'is-clamped': !acceptAll }">
              <li v-for="text in acceptance" :key="text">{{ text }}</li>
            </ul>
            <button
              v-if="acceptance.length > 2"
              class="bg-link"
              type="button"
              @click="acceptAll = !acceptAll"
            >
              {{ acceptAll ? '收起' : `全部 ${acceptance.length} 条` }}
            </button>
          </div>

          <p v-if="error" class="bg-error">{{ error }}</p>

          <div v-if="busy" class="bg-progress">
            <div class="bg-progress-bar"><i :style="{ width: progressPercent + '%' }" /></div>
            <div class="bg-progress-line">
              <span>{{ tickText }}<em>已等待 {{ elapsed }}s</em></span>
              <button class="bg-cancel" type="button" :disabled="cancelling" @click="cancelRun">
                {{ cancelling ? '取消中…' : '取消' }}
              </button>
            </div>
            <!-- 最近几条真实事件：它刚刚读了几张表、关联多少行、这轮决定改什么 -->
            <ul v-if="runEvents.length" class="bg-progress-log">
              <li v-for="event in runEvents.slice(-4)" :key="event.seq">
                <time>{{ event.at }}</time><b>{{ event.label }}</b>
              </li>
            </ul>
          </div>
        </div>
      </section>

      <!-- ==================== 3. 方案与自主过程 ==================== -->
      <section v-if="plan" class="bg-card is-plan">
        <div class="bg-sec-row is-static">
          <span class="bg-sec-no">03</span>
          <b>执行方案</b>
          <span class="bg-sec-text" :title="planBrief">
            {{ awaiting ? '方案已就绪，等你确认后执行' : '它自己定的，不用确认就已经执行' }}
          </span>
          <span class="bg-badge" :class="plan.mode === 'llm' ? 'is-ai' : 'is-rule'" :title="modeBadge">{{ modeBadge }}</span>
        </div>

        <div class="bg-card-body is-tight">
          <p
            class="bg-summary"
            :class="{ 'is-clamped': !summaryOpen }"
            :title="plan.summary"
            @click="summaryOpen = !summaryOpen"
          >
            {{ plan.summary }}
          </p>
          <button
            v-if="summaryLong"
            class="bg-link bg-summary-more"
            type="button"
            @click="summaryOpen = !summaryOpen"
          >
            {{ summaryOpen ? '收起' : '展开全文' }}
          </button>

          <div class="bg-stats">
            <span><b>{{ dataset.tables?.length || 0 }}</b> 张表参与</span>
            <span><b>{{ alignCount }}</b> 处字段对齐</span>
            <span><b>{{ computeCount }}</b> 个计算列</span>
            <span><b>{{ outputCount }}</b> 个输出列</span>
          </div>

          <!-- 自主过程（每轮的决定）与执行轨迹（每步做了什么）并排两列， -->
          <div class="bg-pair">
            <div v-if="attempts.length" class="bg-block is-flat">
              <div class="bg-block-head">
                <Icon name="shield" :size="14" /><b>自主过程</b>
                <span>{{ attempts.length }} 轮 · {{ result?.passed ? '已达标' : '未达标' }}</span>
              </div>
              <ul class="bg-attempts">
                <li v-for="item in attempts" :key="item.round" :class="`is-${item.verdict}`">
                  <span class="bg-attempt-no">第 {{ item.round }} 轮</span>
                  <div>
                    <p>
                      <b>正常 {{ item.ok_rows ?? item.okRows }} 行 / 异常 {{ item.anomaly_rows ?? item.anomalyRows }} 行</b>
                      <em v-if="item.verdict">{{ verdictText(item.verdict) }}</em>
                    </p>
                    <span v-if="item.decision" class="bg-step-detail">{{ item.decision }}</span>
                  </div>
                </li>
              </ul>
              <p class="bg-note">
                达标就停；不达标它自己改一处再跑，改完没变好会自动回滚到上一版，最多 {{ 3 }} 轮。
              </p>
            </div>

            <div class="bg-block is-flat">
              <div class="bg-block-head"><Icon name="terminal" :size="14" /><b>执行轨迹</b><span>{{ plan.steps?.length || 0 }} 步</span></div>
              <ol class="bg-steps">
                <li v-for="(step, index) in plan.steps" :key="step.tool + index" :class="`is-${step.state}`">
                  <span class="bg-step-no">{{ index + 1 }}</span>
                  <div>
                    <p><b>{{ step.title }}</b><code>{{ step.tool }}</code><em v-if="step.ms">{{ elapsedText(step.ms) }}</em></p>
                    <span class="bg-step-detail">{{ step.detail }}</span>
                  </div>
                </li>
              </ol>
            </div>
          </div>

          <!-- 运行日志：每一步的真实参数都能展开（读了几张表、本轮行数、改前异常、导出目录…）， -->
          <div v-if="runEvents.length" class="bg-block is-flat is-log">
            <div class="bg-block-head">
              <Icon name="terminal" :size="14" /><b>运行日志</b>
              <span>{{ runEvents.length }} 条 · 有细节的都能展开</span>
            </div>
            <ul class="bg-log">
              <li v-for="event in runEvents.slice(-12)" :key="event.seq">
                <button
                  class="bg-log-row"
                  type="button"
                  :class="{ 'is-open': openTrace.includes(event.seq), 'is-static': !traceOf(event) }"
                  :disabled="!traceOf(event)"
                  @click="toggleTrace(event.seq)"
                >
                  <time>{{ event.at }}</time>
                  <span>{{ event.label }}</span>
                  <Icon
                    v-if="traceOf(event)"
                    name="down"
                    :size="11"
                    class="bg-sec-arrow"
                    :class="{ 'is-open': openTrace.includes(event.seq) }"
                  />
                </button>
                <div v-if="openTrace.includes(event.seq)" class="bg-log-body">
                  <span v-for="(value, key) in traceOf(event)" :key="key" class="bg-log-kv">
                    <b>{{ key }}</b>{{ value }}
                  </span>
                  <p v-if="event.detail" class="bg-log-detail">{{ event.detail }}</p>
                </div>
              </li>
            </ul>
          </div>

          <!-- 方案细节默认收起：字段对齐表、计算/筛选/异常口径、输出列、说明都在里面 -->
          <button class="bg-more" type="button" @click="detailOpen = !detailOpen">
            <Icon name="down" :size="12" class="bg-sec-arrow" :class="{ 'is-open': detailOpen }" />
            <b>{{ detailOpen ? '收起方案细节' : '方案细节' }}</b>
            <span>对齐 {{ alignCount }} · 计算 {{ computeCount }} · 输出 {{ outputCount }} · 筛选与异常口径</span>
          </button>

          <div v-show="detailOpen">
          <div class="bg-block">
            <div class="bg-block-head"><Icon name="link" :size="14" /><b>跨系统的同义字段</b><span>{{ alignCount }} 个对齐</span></div>
            <el-table :data="plan.aligns" border size="small" max-height="320" class="bg-table-el">
              <el-table-column label="标准字段" min-width="150">
                <template #default="{ row }">
                  <el-select
                    :model-value="row.field"
                    size="small"
                    filterable
                    :disabled="planning || running"
                    @change="(value) => changeAlign(row, value)"
                  >
                    <el-option
                      v-for="item in standardFields"
                      :key="item.field"
                      :label="`${item.label}（${item.field}）`"
                      :value="item.field"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="来源表" min-width="150">
                <template #default="{ row }">{{ tableFile(row.table_id) }}</template>
              </el-table-column>
              <el-table-column label="原始列名" min-width="120" prop="column" />
              <el-table-column label="来源" width="100">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain" :type="row.origin === '规则' ? 'info' : 'success'">{{ row.origin }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="置信度" width="90">
                <template #default="{ row }">{{ Math.round((row.confidence || 0) * 100) }}%</template>
              </el-table-column>
            </el-table>
            <p class="bg-note">改动任意一行都会保存为自定义规则，下次遇到同样的列名自动复用。</p>

            <div v-if="crossSystemFields.length" class="bg-cross">
              <b>识别出的跨系统字段</b>
              <span v-for="item in crossSystemFields" :key="item">{{ item }}</span>
            </div>
          </div>

          <div class="bg-cols">
            <div class="bg-block">
              <div class="bg-block-head"><Icon name="sliders" :size="14" /><b>计算</b></div>
              <ul class="bg-list">
                <li v-for="item in plan.computes" :key="item.name">
                  <b>{{ item.name }}</b>
                  <code>{{ [...item.plus].join(' + ') }}{{ item.minus?.length ? ' − ' + item.minus.join(' − ') : '' }}</code>
                </li>
                <li v-if="!plan.computes?.length" class="bg-empty">没有需要计算的列</li>
              </ul>
            </div>
            <div class="bg-block">
              <div class="bg-block-head"><Icon name="filter" :size="14" /><b>筛选</b></div>
              <ul class="bg-list">
                <li v-for="text in plan.filters" :key="text">{{ text }}</li>
              </ul>
            </div>
            <div class="bg-block">
              <div class="bg-block-head"><Icon name="alert" :size="14" /><b>异常判定</b></div>
              <ul class="bg-list">
                <li v-for="text in plan.anomalyRules" :key="text">{{ text }}</li>
              </ul>
            </div>
            <div class="bg-block">
              <div class="bg-block-head"><Icon name="columns" :size="14" /><b>输出列</b></div>
              <div class="bg-chips">
                <span v-for="column in plan.outputColumns" :key="column">{{ column }}</span>
              </div>
            </div>
          </div>

          <p v-for="note in plan.notes || []" :key="note" class="bg-note">{{ note }}</p>
          </div>

          <p class="bg-hint">方案是它自己定的，也已自己跑完，不需要你点确认；原始文件只读，产出写到输出目录。</p>
        </div>
      </section>

      <!-- ==================== 4. 结果 ==================== -->
      <section v-if="result" ref="resultCardRef" class="bg-card is-result" :class="{ 'is-flash': resultFlash }">
        <div class="bg-sec-row is-static">
          <span class="bg-sec-no">04</span>
          <b>交付结果</b>
          <span class="bg-sec-text" :title="resultBrief">
            标准表 {{ result.ok_rows }} 行 · 异常 {{ result.anomaly_rows }} 行
            <em>
              · {{ result.table_count }} 张表参与 · {{ result.rounds || 1 }} 轮 · {{ elapsedText(result.elapsed_ms) }}
              <template v-if="result.repairs"> · 修复 {{ result.repairs }} 个脏值</template>
            </em>
          </span>
          <span class="bg-badge" :class="result.passed ? 'is-ok' : 'is-warn'">
            {{ result.passed ? '已达标' : '未达标' }}
          </span>
        </div>

        <div class="bg-card-body is-tight">
          <div class="bg-files">
            <button
              v-for="artifact in result.artifacts"
              :key="artifact"
              class="bg-file"
              type="button"
              :title="`打开 ${artifact} 所在文件夹`"
              @click="reveal(artifact)"
            >
              <Icon name="folder" :size="12" /> {{ artifact }}
            </button>
            <span class="bg-files-time">完成于 {{ result.finished_at }}</span>
          </div>

          <el-tabs v-model="resultTab" class="bg-tabs" @tab-change="changeTab">
            <el-tab-pane label="标准表" name="ok">
              <div class="bg-pane-body">
                <div class="bg-rows-head">
                  <span><b class="bg-pane-mark is-ok">标准表</b>共 {{ okTable.total || result.ok_rows }} 行，可直接导入目标系统</span>
                  <button class="bg-link" type="button" :disabled="rowsBusy" @click="loadRows('ok', okTable.page)">刷新</button>
                </div>
                <el-table :data="rowsOf(okTable)" border size="small" max-height="420" class="bg-table-el">
                  <el-table-column
                    v-for="column in (okTable.columns.length ? okTable.columns : result.columns)"
                    :key="column"
                    :label="column"
                    :prop="column"
                    min-width="110"
                    show-overflow-tooltip
                  />
                </el-table>
                <div class="bg-pager">
                  <button class="bg-ghost" :disabled="okTable.page <= 1 || rowsBusy" @click="loadRows('ok', okTable.page - 1)">上一页</button>
                  <span>第 {{ okTable.page }} 页 / 共 {{ Math.max(1, Math.ceil((okTable.total || 0) / okTable.size)) }} 页</span>
                  <button
                    class="bg-ghost"
                    :disabled="rowsBusy || okTable.page * okTable.size >= (okTable.total || 0)"
                    @click="loadRows('ok', okTable.page + 1)"
                  >下一页</button>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="`异常清单（${result.anomaly_rows}）`" name="anomaly">
              <div class="bg-pane-body is-anomaly">
                <div class="bg-rows-head">
                  <span><b class="bg-pane-mark is-warn">异常清单</b>共 {{ anomalyTable.total || result.anomaly_rows }} 行，命中原因都写在里面</span>
                  <button class="bg-link" type="button" :disabled="rowsBusy" @click="loadRows('anomaly', anomalyTable.page)">刷新</button>
                </div>
                <el-table :data="rowsOf(anomalyTable)" border size="small" max-height="420" class="bg-table-el is-anomaly">
                  <el-table-column
                    v-for="column in (anomalyTable.columns.length ? anomalyTable.columns : ['工号', '姓名', '异常原因', '来源表', '原值摘要'])"
                    :key="column"
                    :label="column"
                    :prop="column"
                    :min-width="column === '异常原因' || column === '原值摘要' ? 200 : 110"
                    show-overflow-tooltip
                  />
                </el-table>
                <div class="bg-pager">
                  <button class="bg-ghost" :disabled="anomalyTable.page <= 1 || rowsBusy" @click="loadRows('anomaly', anomalyTable.page - 1)">上一页</button>
                  <span>第 {{ anomalyTable.page }} 页 / 共 {{ Math.max(1, Math.ceil((anomalyTable.total || 0) / anomalyTable.size)) }} 页</span>
                  <button
                    class="bg-ghost"
                    :disabled="rowsBusy || anomalyTable.page * anomalyTable.size >= (anomalyTable.total || 0)"
                    @click="loadRows('anomaly', anomalyTable.page + 1)"
                  >下一页</button>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>

          <!-- 会话式追问：问情况就只回答（不重跑）；说改哪儿就改完立刻重跑并重新验收。 -->
          <div class="bg-chat">
            <div class="bg-block-head">
              <Icon name="chat" :size="14" /><b>和它聊聊</b>
              <span>问它为什么这么算，或直接说改哪儿 —— 带改动的会立即重跑</span>
            </div>

            <div v-if="talks.length" class="bg-turn-list">
              <div v-for="(turn, index) in talks" :key="index" class="bg-turn" :class="`is-${turn.role}`">
                <div class="bg-turn-head">
                  <b>{{ turn.role === 'user' ? '你' : '智能体' }}</b>
                  <time>{{ turn.at }}</time>
                  <span v-if="turn.action === 'rerun'" class="bg-turn-tag">
                    已重跑 {{ turn.rounds }} 轮 · {{ turn.passed ? '达标' : '未达标' }}
                  </span>
                </div>
                <p>{{ turn.text }}</p>
              </div>
            </div>

            <div class="bg-refine-row">
              <el-input
                v-model="chatText"
                type="textarea"
                :rows="2"
                resize="none"
                maxlength="200"
                show-word-limit
                :disabled="chatBusy"
                placeholder="例如：为什么异常这么多？ ／ 部门列不要了 ／ 只保留在职员工"
                @keydown.ctrl.enter="sendChat"
              />
              <button
                class="bg-primary"
                type="button"
                :disabled="!chatText.trim() || chatBusy || busy"
                @click="sendChat"
              >
                <Icon name="send" :size="14" /> {{ chatBusy ? '处理中…' : '发送' }}
              </button>
            </div>
            <div class="bg-refine-hints">
              <button
                v-for="hint in chatHints"
                :key="hint"
                class="bg-chip"
                type="button"
                :disabled="chatBusy || busy"
                @click="chatText = hint"
              >{{ hint }}</button>
            </div>
          </div>
        </div>
      </section>
    </template>
    </div>

    <!-- ==================== 字段规则 + 经验记忆 ==================== -->
    <el-dialog v-model="rulesVisible" title="字段规则与经验记忆" width="620px" class="bg-dialog">
      <div class="bg-rule-block">
        <span class="bg-rule-head">经验记忆<span class="bg-rule-tip">跑成功的配置会自动记下来，下一单同类数据集先复用，再交给你核对</span></span>
        <ul v-if="lessons.length" class="bg-rule-list">
          <li v-for="item in lessons" :key="item.id">
            <b>{{ item.dataset_name || '未命名数据集' }}</b>
            <span>{{ item.scene || '未填业务目标' }}</span>
            <em>
              可信 {{ item.trust }}% · 对齐 {{ item.align_count }} 处 · 复用 {{ item.wins }} 次
              <template v-if="item.anomaly_rate >= 25"> · 异常 {{ item.anomaly_rate }}%</template>
              · {{ item.saved_at }}
              <template v-if="item.offline"> · 已停用</template>
            </em>
            <span class="bg-rule-fb">
              <button type="button" title="这套配置是对的" @click="feedbackLesson(item, true)">有用</button>
              <button type="button" title="不再用这套配置" @click="feedbackLesson(item, false)">不适用</button>
            </span>
            <button class="bg-x" type="button" title="删除" @click="deleteLesson(item)">
              <Icon name="trash" :size="12" />
            </button>
          </li>
        </ul>
        <p v-else class="bg-empty">还没有经验记忆，成功跑完一单就会自动生成。</p>
      </div>

      <div class="bg-rule-block">
        <span class="bg-rule-head">自定义字段匹配规则<span class="bg-rule-tip">规则优先级最高，命中后覆盖词典和经验</span></span>
        <div class="bg-rule-add">
          <!-- 第一项是「自定义」：选中后这一格换成输入框，可以写标准字段表里没有的目标名 -->
          <el-select
            v-if="ruleDraft.standard !== CUSTOM_FIELD"
            v-model="ruleDraft.standard"
            size="small"
            filterable
            class="bg-w-sm"
            @change="onStandardChange"
          >
            <el-option label="自定义…" :value="CUSTOM_FIELD" />
            <el-option
              v-for="item in standardFields"
              :key="item.field"
              :label="item.label"
              :value="item.field"
            />
          </el-select>
          <el-input
            v-else
            ref="customFieldRef"
            v-model="ruleDraft.custom"
            size="small"
            class="bg-w-custom"
            placeholder="自定义字段名"
            @keyup.enter="addRule"
          >
            <template #suffix>
              <button
                class="bg-rule-back"
                type="button"
                title="回到标准字段"
                @click="backToStandard"
              >×</button>
            </template>
          </el-input>
          <span class="bg-rule-arrow">←</span>
          <el-input v-model="ruleDraft.alias" size="small" placeholder="原始列名，例如 职员名称" class="bg-w-md" />
          <el-button type="primary" size="small" @click="addRule">保存规则</el-button>
        </div>
        <ul v-if="rules.length" class="bg-rule-list">
          <li v-for="rule in rules" :key="rule.alias">
            <b>{{ rule.alias }}</b>
            <span>→ {{ fieldLabel(rule.standard) }}</span>
            <em>{{ rule.at }}</em>
            <button class="bg-x" type="button" title="删除" @click="deleteRule(rule)">
              <Icon name="trash" :size="12" />
            </button>
          </li>
        </ul>
        <p v-else class="bg-empty">还没有自定义规则，改一次字段对齐就会自动记下来。</p>
      </div>
    </el-dialog>

    <!-- 选择文件夹：左边常用位置，上方面包屑，右边文件夹列表，点进去再确认 -->
    <el-dialog v-model="pickerOpen" :title="pickerTitle" width="760px" class="bg-dialog bg-pick-dialog">
      <div class="bg-explore">
        <aside class="bg-explore-side">
          <p class="bg-explore-head">常用位置</p>
          <button
            class="bg-explore-place"
            :class="{ on: picker.path === pickerPlaces.defaultDir }"
            type="button"
            @click="loadPicker(pickerPlaces.defaultDir)"
          >
            <Icon name="download" :size="14" />
            <span>默认目录</span>
          </button>
          <button
            v-if="exportDir.custom"
            class="bg-explore-place"
            :class="{ on: picker.path === exportDir.custom }"
            type="button"
            @click="loadPicker(exportDir.custom)"
          >
            <Icon name="folder" :size="14" />
            <span>已保存的目录</span>
          </button>
          <p class="bg-explore-head">此电脑</p>
          <button
            v-for="dir in pickerPlaces.dirs"
            :key="dir.path"
            class="bg-explore-place"
            :class="{ on: picker.path === dir.path }"
            type="button"
            @click="loadPicker(dir.path)"
          >
            <Icon name="database" :size="14" />
            <span>{{ dir.name }}</span>
          </button>
        </aside>

        <section class="bg-explore-main">
          <div class="bg-explore-bar">
            <button
              class="bg-explore-nav"
              type="button"
              title="上一级"
              :disabled="!picker.path"
              @click="pickerUp"
            >
              <Icon name="left" :size="14" />
            </button>
            <div class="bg-explore-crumbs">
              <button class="bg-crumb" type="button" @click="loadPicker('')">此电脑</button>
              <template v-for="(crumb, index) in pickerCrumbs" :key="crumb.path">
                <span class="bg-crumb-sep">›</span>
                <button
                  class="bg-crumb"
                  :class="{ on: index === pickerCrumbs.length - 1 }"
                  type="button"
                  @click="loadPicker(crumb.path)"
                >
                  {{ crumb.name }}
                </button>
              </template>
            </div>
            <button
              class="bg-explore-new"
              type="button"
              title="在当前文件夹下新建一个文件夹"
              :disabled="!picker.path || newFolderOpen"
              @click="toggleNewFolder"
            >
              <Icon name="plus" :size="13" /> 新建文件夹
            </button>
          </div>

          <!-- 新建文件夹：就地输入名字，回车建好并进入，Esc 取消 -->
          <div v-if="newFolderOpen" class="bg-explore-new-row">
            <Icon name="folder" :size="14" />
            <input
              ref="newFolderRef"
              v-model="newFolderName"
              class="bg-explore-new-input"
              type="text"
              maxlength="60"
              placeholder="新文件夹名称，回车确认"
              @keyup.enter="createFolder()"
              @keyup.esc="cancelNewFolder()"
            />
            <button class="bg-primary" type="button" :disabled="!newFolderName.trim() || newFolderBusy" @click="createFolder()">
              {{ newFolderBusy ? '正在建…' : '新建' }}
            </button>
            <button class="bg-ghost" type="button" @click="cancelNewFolder()">取消</button>
          </div>

          <ul class="bg-explore-list">
            <li
              v-for="entry in picker.entries"
              :key="entry.path"
              @contextmenu.prevent="openFolderMenu($event, entry)"
            >
              <!-- 右键选「重命名」后这一行就地变成输入框，回车确认、Esc 取消 -->
              <div v-if="renamingPath === entry.path" class="bg-explore-renaming">
                <Icon name="folder" :size="15" />
                <input
                  :ref="setRenameRef"
                  v-model="renameName"
                  class="bg-explore-new-input"
                  type="text"
                  maxlength="60"
                  @keyup.enter="commitRename(entry)"
                  @keyup.esc="cancelRename()"
                />
                <button
                  class="bg-primary"
                  type="button"
                  :disabled="!renameName.trim() || renameBusy"
                  @click="commitRename(entry)"
                >
                  {{ renameBusy ? '改名中…' : '改名' }}
                </button>
                <button class="bg-ghost" type="button" @click="cancelRename()">取消</button>
              </div>
              <button v-else type="button" title="单击进入，右键可重命名" @click="loadPicker(entry.path)">
                <Icon name="folder" :size="15" />
                <span class="bg-explore-name">{{ entry.name }}</span>
                <em v-if="entry.root">根目录</em>
              </button>
            </li>
            <li v-if="picker.loading" class="bg-explore-empty">正在读目录…</li>
            <li v-else-if="!picker.entries.length" class="bg-explore-empty">
              这个文件夹下没有子文件夹，可以直接存到这里
            </li>
          </ul>

          <div class="bg-explore-foot">
            <span class="bg-explore-target" :title="picker.path">
              {{ picker.path || '还没进入任何文件夹' }}
            </span>
            <button
              class="bg-link"
              type="button"
              :disabled="!pickerPlaces.defaultDir"
              @click="loadPicker(pickerPlaces.defaultDir)"
            >
              回到默认目录
            </button>
          </div>
          <p v-if="picker.error" class="bg-error">{{ picker.error }}</p>
        </section>
      </div>
      <template #footer>
        <button class="bg-ghost" type="button" @click="pickerOpen = false">取消</button>
        <button class="bg-primary" type="button" :disabled="!picker.path" @click="pickFolder(picker.path)">
          确定
        </button>
      </template>
    </el-dialog>

    <!-- 文件夹右键菜单：贴着鼠标出现，点空白处自动收起 -->
    <div
      v-if="folderMenu.open && folderMenu.entry"
      class="bg-menu"
      :style="{ left: `${folderMenu.x}px`, top: `${folderMenu.y}px` }"
      @click.stop
    >
      <button type="button" @click="loadPicker(folderMenu.entry.path); closeFolderMenu()">
        <Icon name="folder" :size="13" /> 打开
      </button>
      <button type="button" @click="startRename(folderMenu.entry)">
        <Icon name="pencil" :size="13" /> 重命名
      </button>
    </div>

    <!-- 导出位置提示：固定在右下角，不占正文；点「前往文件夹」才打开并收起 -->
    <transition name="bg-note">
      <div v-if="exportNotice" class="bg-export-note">
        <Icon name="folder" :size="14" />
        <div class="bg-export-text">
          <span>导出文件已保存到</span>
          <b class="bg-export-path" :title="exportNotice.dir">{{ exportNotice.dir }}</b>
        </div>
        <button class="bg-export-go" type="button" @click="reveal(exportNotice.file)">前往文件夹</button>
        <button class="bg-export-link" type="button" title="换一个目录，只移动已导出的文件，不重新处理"
                @click="openRelocate()">更改位置</button>
        <button class="bg-export-x" type="button" title="收起" @click="exportNotice = null">×</button>
      </div>
    </transition>
  </div>
</template>

<style scoped>
/* 工作台形态：主工作区（数据集 / 目标 / 交付结果）+ 右侧运行检查器（方案、自主过程、执行轨迹）。
 * 以前是单列 1080px，宽屏右侧整片空白、内容一路往下堆；现在宽屏下左右分工，
 * 左边看结果、右边看它为什么这么做，和主流智能体产品的「产出 + 运行观察」布局一致。 */
.bg {
  --bg-ink: #141922;
  --bg-ink-2: #4b5563;
  --bg-ink-3: #8b929e;
  --bg-line: #e4e8ef;
  --bg-line-soft: #eef1f6;
  --bg-blue: #2b5fd9;
  --bg-blue-soft: #eef3ff;
  --bg-mono: ui-monospace, SFMono-Regular, "SF Mono", Consolas, "Liberation Mono", monospace;

  display: block;
  /* 只做宽度容器：内容区多宽就按多宽判断双栏，不牵扯左侧导航栏的占位 */
  container-type: inline-size;
  max-width: 1560px;
  margin: 0 auto;
}

.bg-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 14px;
}

/* 区块归属：窄屏按顺序纵向堆叠；宽屏再按列分工（见文末容器断点） */
.bg-runbar,
.bg-card {
  grid-column: 1;
  min-width: 0;
}

/* 还没上传时只有一张上传卡：宽屏下也占满整行，不要缩在左半边 */
.bg-card.is-upload {
  grid-column: 1 / -1;
}

.bg-card {
  overflow: hidden;
  border: 1px solid var(--bg-line);
  border-radius: 10px;
  background: #fff;
}

/* 点开交付文件后，结果卡会闪一下蓝色描边：
 * 用户本来就停在 04 时，滚动是没有位移的，用这一下告诉他「要看的就是这块」。 */
.bg-card.is-result.is-flash {
  animation: bg-result-flash 1.1s ease-out;
}

@keyframes bg-result-flash {
  from {
    box-shadow: 0 0 0 2px rgb(45 108 255 / 45%), 0 8px 20px rgb(45 108 255 / 18%);
  }
  to {
    box-shadow: 0 0 0 2px rgb(45 108 255 / 0%), 0 8px 20px rgb(45 108 255 / 0%);
  }
}

.bg-card-head {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  border-bottom: 1px solid var(--bg-line-soft);
  background: linear-gradient(180deg, #fafbfd 0%, #fff 100%);
}

.bg-card-head h2 {
  margin: 3px 0 4px;
  color: var(--bg-ink);
  font-size: 16px;
  font-weight: 650;
  line-height: 1.35;
}

.bg-card-head p {
  margin: 0;
  color: var(--bg-ink-3);
  font-size: 12px;
  line-height: 1.6;
}

.bg-kicker {
  color: var(--bg-blue);
  font-family: var(--bg-mono);
  font-size: 10.5px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.bg-core {
  flex: none;
  padding: 3px 9px;
  border: 1px solid #c9d8f8;
  border-radius: 999px;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

/* 「核心能力」左侧的最近记录入口：图标按钮 + 下拉记录 */
.bg-head-side {
  position: relative;
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
}

.bg-recent-btn {
  display: grid;
  place-items: center;
  width: 27px;
  height: 27px;
  padding: 0;
  border: 1px solid var(--bg-line);
  border-radius: 7px;
  background: #fff;
  color: var(--bg-ink-3);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.bg-recent-btn:hover {
  border-color: #c9d8f8;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
}

.bg-recent-pop {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  width: 340px;
  /* 弹层自身不再无限长高：头部和底栏固定，只有列表滚动 */
  max-height: 320px;
  background: #fff;
  border: 1px solid var(--bg-line);
  border-radius: 10px;
  box-shadow: 0 12px 28px rgb(20 30 50 / 12%);
  overflow: hidden;
}

.bg-recent-pop-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex: none;
  padding: 10px 12px 8px;
  border-bottom: 1px solid var(--bg-line);
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-recent-pop-head b {
  color: var(--bg-ink);
  font-size: 12px;
  font-weight: 600;
}

/* 列表区独立滚动，条目再多也不会把弹层撑出屏幕 */
.bg-recent-pop-list {
  flex: 1 1 auto;
  min-height: 0;
  max-height: 232px;
  padding: 6px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.bg-recent-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px;
  border-radius: 6px;
  cursor: pointer;
}

.bg-recent-row:hover {
  background: #f6f8fb;
}

.bg-recent-row.is-on {
  background: var(--bg-blue-soft);
}

.bg-recent-row.is-head {
  padding: 4px 6px;
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-recent-row input[type='checkbox'] {
  flex: none;
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--bg-blue);
  cursor: pointer;
}

.bg-recent-main {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  cursor: pointer;
}

.bg-recent-main b {
  overflow: hidden;
  color: var(--bg-ink);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-recent-main i {
  overflow: hidden;
  color: var(--bg-ink-3);
  font-size: 11px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-recent-pop-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  flex: none;
  padding: 8px 10px;
  border-top: 1px solid var(--bg-line);
  background: #fbfcfd;
}

.bg-recent-sel {
  flex: 1 1 auto;
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-recent-act {
  padding: 4px 9px;
  border: 1px solid var(--bg-line);
  border-radius: 6px;
  background: #fff;
  color: var(--bg-ink-2);
  font-size: 11px;
  cursor: pointer;
}

.bg-recent-act:hover:not(:disabled) {
  border-color: #c9d8f8;
  color: var(--bg-blue);
}

.bg-recent-act.is-danger:hover:not(:disabled) {
  border-color: #f0c2c2;
  color: #c0392b;
}

.bg-recent-act:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.bg-recent-empty {
  margin: 0;
  padding: 14px 12px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
  line-height: 1.7;
}

.bg-card-body {
  padding: 18px 20px 20px;
}

/* 上传入口：与普通模式的投放卡同一套外观（斜纹浅底 + 白底圆角图标），两处互换 */
.bg-drop-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.bg-drop :deep(.el-upload),
.bg-drop :deep(.el-upload-dragger) {
  width: 100%;
  height: 100%;
}

.bg-drop :deep(.el-upload-dragger) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 104px;
  padding: 22px 16px;
  border: 1px dashed #cfd5df;
  border-radius: 10px;
  background:
    repeating-linear-gradient(135deg, rgb(43 95 217 / 2.2%) 0 8px, transparent 8px 16px),
    #fbfcfe;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.bg-drop :deep(.el-upload-dragger:hover) {
  border-color: var(--bg-blue);
  background: var(--bg-blue-soft);
}

.bg-drop-inner,
.bg-folder {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  justify-content: center;
  width: 100%;
  text-align: center;
}

.bg-drop-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  min-width: 0;
}

.bg-drop-text b {
  color: var(--bg-ink);
  font-size: 13px;
  font-weight: 600;
}

.bg-drop-text span {
  color: var(--bg-ink-3);
  font-size: 11.5px;
  line-height: 1.6;
}

.bg-drop-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 34px;
  height: 34px;
  margin-bottom: 4px;
  border: 1px solid #e4e7ed;
  border-radius: 9px;
  background: #fff;
  color: #909399;
}

.bg-folder {
  min-height: 104px;
  padding: 22px 16px;
  border: 1px dashed #cfd5df;
  border-radius: 10px;
  background:
    repeating-linear-gradient(135deg, rgb(43 95 217 / 2.2%) 0 8px, transparent 8px 16px),
    #fbfcfe;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.bg-folder:hover:not(:disabled) {
  border-color: var(--bg-blue);
  background: var(--bg-blue-soft);
}

.bg-folder:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.bg-hidden {
  display: none;
}

.bg-pending {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fbfcfe;
}

.bg-pending-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--bg-ink-2);
  font-size: 12px;
}

.bg-pending-head > span {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}

.bg-pending-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.bg-pending-list li {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 100%;
  padding: 3px 6px 3px 8px;
  border: 1px solid var(--bg-line);
  border-radius: 6px;
  background: #fff;
  font-size: 11.5px;
}

.bg-pending-list li > span {
  overflow: hidden;
  max-width: 220px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-pending-list em {
  color: var(--bg-ink-3);
  font-size: 10.5px;
  font-style: normal;
}

.bg-x {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: none;
  color: var(--bg-ink-3);
}

.bg-x:hover {
  background: #f0f2f6;
  color: var(--bg-ink);
}

.bg-naming {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 14px;
}

.bg-label {
  flex: none;
  color: #303133;
  font-size: 14px;
  font-weight: 400;
}

.bg-naming :deep(.el-input) {
  flex: 1 1 240px;
  max-width: 340px;
}

.bg-primary,
.bg-ghost {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  height: 32px;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 400;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease, background-color 0.15s ease;
}

.bg-primary {
  border: 1px solid var(--bg-blue);
  background: var(--bg-blue);
  color: #fff;
}

.bg-primary:hover:not(:disabled) {
  border-color: #224cae;
  background: #224cae;
}

.bg-primary:disabled,
.bg-ghost:disabled {
  cursor: not-allowed;
  opacity: 0.45;
  box-shadow: none;
}

.bg-ghost {
  border: 1px solid #d0d3d6;
  background: #fff;
  color: #303133;
}

.bg-ghost:hover:not(:disabled) {
  border-color: #b8bcc2;
  background: #f5f6f7;
  color: #303133;
}

.bg-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--bg-blue);
  font-size: 11.5px;
  cursor: pointer;
}

.bg-link:hover {
  text-decoration: underline;
}

.bg-error {
  margin: 10px 0 0;
  color: #c0392b;
  font-size: 12px;
}

.bg-note {
  margin: 8px 0 0;
  color: var(--bg-ink-3);
  font-size: 11.5px;
  line-height: 1.6;
}

.bg-tips {
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid var(--bg-line-soft);
  border-radius: 8px;
  background: #fafbfd;
}

.bg-tips b {
  color: var(--bg-ink-2);
  font-size: 12px;
}

.bg-tips ol {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
  line-height: 1.75;
}

/* 数据集里的表 */
.bg-status {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 3px 9px;
  border: 1px solid var(--bg-line);
  border-radius: 999px;
  color: var(--bg-ink-2);
  font-size: 11px;
}

.bg-status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b6bdc9;
}

.bg-status.is-ok {
  border-color: #bfe3d1;
  background: #eefaf3;
  color: #1f7a4d;
}

.bg-status.is-ok i {
  background: #1f7a4d;
}

.bg-status.is-wait {
  border-color: #f0d9b5;
  background: #fff8ec;
  color: var(--bg-ink-2);
}

.bg-status.is-wait i {
  background: #d08a1a;
}

.bg-tables {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.bg-table {
  padding: 10px 12px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fff;
}

/* 判重跳过的那张表：虚线框 + 灰字，看一眼就知道它没参与计算 */
.bg-table.is-skipped {
  border-style: dashed;
  background: #fafbfc;
}

.bg-table.is-skipped .bg-table-meta b {
  color: var(--bg-ink-3);
  text-decoration: line-through;
  text-decoration-color: #cfd4dc;
}

.bg-table.is-skipped .bg-table-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-table-reason {
  margin: 8px 0 0;
  color: var(--bg-ink-3);
  font-size: 11px;
  line-height: 1.55;
}

.bg-table-main {
  display: flex;
  gap: 10px;
  align-items: center;
}

.bg-table-id {
  display: grid;
  flex: none;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
  font-family: var(--bg-mono);
  font-size: 10.5px;
  font-weight: 600;
}

.bg-table-id.is-skipped {
  background: #f2f3f5;
  color: var(--bg-ink-3);
  font-family: inherit;
  font-size: 12px;
}

.bg-table-meta {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.bg-table-meta b {
  overflow: hidden;
  color: var(--bg-ink);
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-table-meta span {
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-table-cols {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
  padding-top: 9px;
  border-top: 1px dashed var(--bg-line);
}

.bg-alt,
.bg-role {
  flex: none;
  padding: 1px 6px;
  border-radius: 5px;
  font-size: 10px;
  white-space: nowrap;
}

.bg-alt {
  border: 1px solid #e2e5ea;
  background: #f7f8fa;
  color: var(--bg-ink-3);
}

.bg-role {
  border: 1px solid #c9d8f8;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
}

.bg-table-cols span {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 2px 7px;
  border: 1px solid var(--bg-line-soft);
  border-radius: 5px;
  background: #fafbfd;
  color: var(--bg-ink-2);
  font-size: 11px;
}

.bg-table-cols i {
  color: var(--bg-ink-3);
  font-style: normal;
}

/* 指令 */
.bg-samples {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
}

.bg-sample {
  padding: 3px 9px;
  border: 1px solid var(--bg-line);
  border-radius: 999px;
  background: #fbfcfe;
  color: var(--bg-ink-2);
  font-size: 11px;
}

.bg-sample:hover {
  border-color: #c5d3ee;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
}

.bg-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

/* 离线说明卡：琥珀色，和正文里的蓝色卡片区分开，不像是报错 */
.bg-offline {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid #f0e0c2;
  border-radius: 10px;
  background:
    radial-gradient(120% 100% at 0% 0%, rgb(217 145 31 / 8%) 0%, transparent 55%),
    #fffdf7;
}

.bg-offline-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.bg-offline-head b {
  color: var(--bg-ink);
  font-size: 13.5px;
}

.bg-offline p {
  margin: 0;
  color: var(--bg-ink-2);
  font-size: 12.5px;
  line-height: 1.75;
}

.bg-offline-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding-top: 4px;
}

/* 看某一单时断网的那条：横着排，别占一屏高度 */
.bg-offline.is-bar {
  flex-direction: row;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 10px 14px;
  font-size: 12.5px;
  color: var(--bg-ink-2);
}

.bg-offline.is-bar .bg-link {
  margin-left: auto;
}

/* 业务目标折叠块：默认只露一行标题，展开后才出现输入框和示例 */
.bg-fold {
  margin-top: 12px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fff;
}

.bg-fold-head {
  display: flex;
  gap: 7px;
  align-items: center;
  width: 100%;
  padding: 10px 13px;
  border: 0;
  border-radius: 8px;
  background: none;
  text-align: left;
  cursor: pointer;
}

.bg-fold-head:hover {
  background: #f7f9fc;
}

.bg-fold-head b {
  flex: none;
  color: var(--bg-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.bg-fold-head > span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--bg-ink-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-fold-arrow {
  flex: none;
  color: var(--bg-ink-3);
  transition: transform 0.16s ease;
}

.bg-fold.open .bg-fold-arrow {
  transform: rotate(180deg);
}

.bg-fold-body {
  padding: 0 13px 13px;
}

.bg-fold-body .el-textarea__inner {
  font-size: 12.5px;
}

.bg-hint {
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

.bg-progress {
  margin-top: 12px;
}

.bg-progress-bar {
  overflow: hidden;
  height: 4px;
  border-radius: 999px;
  background: var(--bg-line-soft);
}

.bg-progress-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #7aa0ef, var(--bg-blue));
  transition: width 0.8s ease;
}

.bg-progress-line > span {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 6px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

.bg-progress em {
  color: var(--bg-ink-3);
  font-style: normal;
}

/* 取消：和“已等待”同一行，做成很轻的文字按钮，不抢进度条的注意力 */
.bg-progress-line {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.bg-cancel {
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  color: var(--bg-ink-3);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
}

.bg-cancel:hover:not(:disabled) {
  color: #c0392b;
  text-decoration: underline;
}

.bg-cancel:disabled {
  cursor: default;
  opacity: 0.6;
}

/* 真实事件：只留最近几条，看得见它此刻在做什么，又不把页面撑长 */
.bg-progress-log {
  display: grid;
  gap: 3px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.bg-progress-log li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

.bg-progress-log time {
  flex: none;
  color: var(--bg-ink-3);
  font-variant-numeric: tabular-nums;
}

.bg-progress-log b {
  color: var(--bg-ink-2);
  font-weight: 500;
}

/* 方案 */
/* 「大模型参与规划」这类徽标在窄栏（右侧 380px）里必须整条一行，不许折成两行；
 * 真长到一行放不下就出省略号，悬浮用 title 看全文。 */
.bg-badge {
  flex: none;
  max-width: 100%;
  padding: 2px 8px;
  overflow: hidden;
  border: 1px solid var(--bg-line);
  border-radius: 999px;
  font-size: 10.5px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-badge.is-ai {
  border-color: #c9d8f8;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
}

.bg-badge.is-rule {
  border-color: #e2e5ea;
  background: #f7f8fa;
  color: var(--bg-ink-2);
}

.bg-badge.is-ok {
  border-color: #bfe3d1;
  background: #eefaf3;
  color: #1f7a4d;
}

.bg-badge.is-warn {
  border-color: #f2dfb8;
  background: #fdf6e8;
  color: #8a5a13;
}

/* 自主目标：一句话目标 + 可选的人工补充 */
.bg-goal {
  padding: 11px 12px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fbfcfe;
}

.bg-goal p {
  margin: 4px 0 0;
  color: var(--bg-ink);
  font-size: 13px;
  line-height: 1.7;
}

/* 导出位置：内容区最上方的一行，标签 + 路径 + 两个按钮，处理中置灰锁定 */
.bg-dest {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 10px 14px;
  border: 1px solid var(--bg-line);
  border-radius: 10px;
  background: #fff;
}

.bg-dest-label {
  display: inline-flex;
  flex: none;
  align-items: center;
  color: #303133;
  font-size: 14px;
}

.bg-dest .el-input {
  flex: 1 1 220px;
  min-width: 0;
}

/* 一行里只保留一个主按钮，其余都用浅色描边按钮，避免一排彩色块 */
.bg-dest-btn {
  flex: none;
  height: 32px;
  padding: 0 14px;
  border: 1px solid #d0d3d6;
  border-radius: 6px;
  background: #fff;
  color: #303133;
  font-size: 14px;
  font-weight: 400;
  line-height: 1;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.bg-dest-btn:hover:not(:disabled) {
  border-color: #b8bcc2;
  background: #f5f6f7;
}

.bg-dest-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.bg-dest-reset {
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  color: #606266;
  font-size: 14px;
  font-weight: 400;
  cursor: pointer;
}

.bg-dest-reset:hover:not(:disabled) {
  color: #303133;
  text-decoration: underline;
}

.bg-dest-reset:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

/* 上传页的导出位置：缩成一行，贴在「数据集名称」这行的右侧空白处，不单独占一整行 */
.bg-dest.is-inline {
  flex: 1 1 320px;
  max-width: 520px;
  margin-left: auto;
  padding: 0;
  border: 0;
  background: none;
  gap: 6px;
}

.bg-dest.is-inline .el-input {
  flex: 1 1 130px;
}

.bg-dest-tip {
  flex: none;
  color: var(--bg-ink-3);
  font-size: 11px;
}

/* 自主过程：每一轮跑了多少、决定了什么 */
.bg-attempts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
}

.bg-attempts li {
  display: flex;
  gap: 10px;
  padding: 9px 11px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fbfcfe;
}

.bg-attempts li.is-rolled_back {
  border-color: #f2dfb8;
  background: #fdf6e8;
}

.bg-attempts li.is-passed {
  border-color: #bfe3d1;
  background: #f4fbf7;
}

.bg-attempt-no {
  flex: none;
  min-width: 48px;
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 11px;
}

.bg-attempts p {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin: 0 0 3px;
}

.bg-attempts p b {
  color: var(--bg-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.bg-attempts p em {
  color: var(--bg-ink-3);
  font-size: 11px;
  font-style: normal;
}

.bg-block {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
}

.bg-block-head {
  display: flex;
  gap: 6px;
  align-items: center;
  color: var(--bg-ink-2);
  font-size: 11.5px;
}

.bg-block-head b {
  color: var(--bg-ink);
  font-size: 12px;
}

.bg-block-head > span {
  margin-left: auto;
  color: var(--bg-ink-3);
  font-size: 10.5px;
}

.bg-steps {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.bg-steps li {
  display: flex;
  gap: 9px;
  padding: 7px 0;
}

.bg-steps li + li {
  border-top: 1px dashed var(--bg-line-soft);
}

.bg-step-no {
  display: grid;
  flex: none;
  place-items: center;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border: 1px solid #c9d8f8;
  border-radius: 50%;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
  font-family: var(--bg-mono);
  font-size: 9.5px;
}

.bg-steps div {
  min-width: 0;
}

.bg-steps p {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  align-items: center;
  margin: 0;
  color: var(--bg-ink);
  font-size: 12px;
}

.bg-steps code {
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10px;
}

.bg-steps p em {
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10px;
  font-style: normal;
}

.bg-step-detail {
  display: block;
  margin-top: 2px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
  line-height: 1.55;
}

.bg-table-el {
  margin-top: 10px;
}

.bg-cross {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
}

.bg-cross b {
  color: var(--bg-ink-2);
  font-size: 11px;
}

.bg-cross span {
  padding: 2px 7px;
  border: 1px solid #c9d8f8;
  border-radius: 5px;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
  font-size: 10.5px;
}

.bg-cols {
  display: grid;
  gap: 0 12px;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
}

.bg-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 9px 0 0;
  padding: 0;
  list-style: none;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  line-height: 1.55;
}

.bg-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  align-items: baseline;
}

.bg-list code {
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10.5px;
}

.bg-list li.bg-empty {
  color: var(--bg-ink-3);
}

.bg-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 9px;
}

.bg-chips span {
  padding: 2px 7px;
  border: 1px solid var(--bg-line);
  border-radius: 5px;
  background: #fbfcfe;
  color: var(--bg-ink-2);
  font-size: 10.5px;
}

.bg-run {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--bg-line-soft);
}

/* 结果 */
.bg-tabs {
  margin-top: 14px;
}

/* Tab 条：两条结果表之间的分界线就在这条上（下划线 + 选中项色条），
 * 让人一眼看出「现在看的是哪张表」，而不是两张表在同一个框里接着往下排。 */
.bg-tabs :deep(.el-tabs__header) {
  margin-bottom: 10px;
}

.bg-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--bg-line);
}

.bg-tabs :deep(.el-tabs__item) {
  height: 34px;
  padding: 0 2px;
  margin-right: 18px;
  color: var(--bg-ink-3);
  font-size: 12.5px;
  font-weight: 500;
}

.bg-tabs :deep(.el-tabs__item.is-active) {
  color: var(--bg-ink);
  font-weight: 600;
}

.bg-tabs :deep(.el-tabs__active-bar) {
  height: 2px;
  border-radius: 2px;
  background: var(--bg-blue);
}

.bg-tabs :deep(.el-tabs__item:hover) {
  color: var(--bg-ink);
}

.bg-rows-head {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

/* 两个结果表刚分完页紧接着就是另一张表，视觉上一直接着看不出边界。
 * 现在每张表都带一个自己的标签头（标准表 / 异常清单），
 * 异常清单再用琥珀色区分，和正常结果一眼能看出来。 */
.bg-pane-body {
  padding-top: 2px;
}

.bg-pane-body.is-anomaly {
  margin: -2px -4px 0;
  padding: 8px 8px 6px;
  border-top: 2px solid #f0d9b5;
  border-radius: 8px;
  background: #fffdf8;
}

.bg-pane-mark {
  display: inline-block;
  margin-right: 7px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.2px;
}

.bg-pane-mark.is-ok {
  border: 1px solid #cfe6d8;
  background: #f1faf4;
  color: #2f8f5b;
}

.bg-pane-mark.is-warn {
  border: 1px solid #f0d9b5;
  background: #fff6e6;
  color: #a8690f;
}

.bg-pager {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--bg-line);
}

.bg-pager span {
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-pager .bg-ghost {
  height: 28px;
  padding: 0 11px;
  font-size: 11.5px;
}

/* 规则弹窗 */
.bg-dialog :deep(.el-dialog__body) {
  padding-top: 6px;
}

.bg-dialog-desc {
  margin: 0 0 12px;
  color: var(--bg-ink-3);
  font-size: 12px;
  line-height: 1.6;
}

/* 规则和经验分两块，中间一条分隔线；经验在上，因为是系统自己攒的 */
.bg-rule-block + .bg-rule-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--bg-line);
}

.bg-rule-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 8px;
  color: var(--bg-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.bg-rule-tip {
  color: var(--bg-ink-3);
  font-size: 11px;
  font-weight: 400;
}

.bg-rule-add {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--bg-line-soft);
}

.bg-w-sm {
  width: 130px;
}

/* 自定义字段名可能比标准字段的标签长一点，给输入框留宽一些 */
.bg-w-custom {
  width: 150px;
}

/* 自定义输入框里的小 ×：退回标准字段下拉，做成按钮会把这行挤得太满 */
.bg-rule-back {
  padding: 0 2px;
  border: 0;
  background: none;
  color: var(--bg-ink-3);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.bg-rule-back:hover {
  color: var(--bg-blue);
}

.bg-w-md {
  flex: 1;
}

.bg-rule-arrow {
  color: var(--bg-ink-3);
  font-size: 12px;
}

.bg-rule-list {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.bg-rule-list li {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 0;
  font-size: 12px;
}

.bg-rule-list li + li {
  border-top: 1px dashed var(--bg-line-soft);
}

.bg-rule-list b {
  color: var(--bg-ink);
}

.bg-rule-list span {
  color: var(--bg-ink-2);
}

.bg-rule-list em {
  margin-left: auto;
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10px;
  font-style: normal;
}

.bg-empty {
  margin-top: 10px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

/* 经验行的人工反馈：平时低调，悬停才显色 */
.bg-rule-fb {
  display: inline-flex;
  flex-shrink: 0;
  gap: 4px;
}

.bg-rule-fb button {
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--bg-line);
  border-radius: 4px;
  background: #fff;
  color: var(--bg-ink-3);
  font-size: 10.5px;
  line-height: 18px;
  cursor: pointer;
}

.bg-rule-fb button:hover {
  border-color: var(--bg-accent);
  color: var(--bg-accent);
}

/* 交付后的对话区：输入框 + 多轮气泡。提问只回答，带改动才重跑 */
.bg-chat {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--bg-line);
}

.bg-turn-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
  max-height: 260px;
  padding-right: 2px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}

.bg-turn {
  padding: 8px 10px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fafbfd;
}

/* 自己的话靠右、浅蓝底；智能体的回答靠左、白底，和主流对话界面一致 */
.bg-turn.is-user {
  margin-left: 36px;
  border-color: #d9e4ff;
  background: var(--bg-blue-soft);
}

.bg-turn.is-agent {
  margin-right: 36px;
  background: #fff;
}

.bg-turn-head {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-bottom: 4px;
  color: var(--bg-ink-3);
  font-size: 10.5px;
}

.bg-turn-head b {
  color: var(--bg-ink-2);
  font-size: 11px;
  font-weight: 600;
}

.bg-turn-head time {
  font-family: var(--bg-mono);
}

.bg-turn-tag {
  padding: 1px 6px;
  border: 1px solid #cfe6d8;
  border-radius: 9px;
  background: #f1faf4;
  color: #2f8f5b;
}

.bg-turn p {
  margin: 0;
  color: var(--bg-ink);
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 可执行权限：手动审批 / 自动审批 / 完全访问 —— 三段式切换，一眼看出当前是哪档 */
.bg-access {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 10px;
  align-items: center;
  margin-bottom: 12px;
}

/* 上传页这一行不要贴着下面的输入框，单独留点间距 */
.bg-access.is-upload {
  margin: 4px 0 14px;
}

.bg-access-modes {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--bg-line);
  border-radius: 7px;
  background: #f7f9fc;
}

.bg-access-mode {
  height: 26px;
  padding: 0 11px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--bg-ink-2);
  font-size: 12px;
  cursor: pointer;
}

.bg-access-mode.is-on {
  background: #fff;
  box-shadow: 0 1px 3px rgb(20 30 50 / 12%);
  color: var(--bg-blue);
  font-weight: 600;
}

.bg-access-mode:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.bg-access-hint {
  flex: 1 1 220px;
  min-width: 0;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

/* 提示语和「跑完自动打开文件夹」绑在一行：窄屏时提示先换行，开关跟着它一起走，
 * 不会被挤到整行的最右边、看起来像是另一个设置 */
.bg-access-note {
  display: flex;
  flex: 1 1 260px;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.bg-access-note .bg-access-hint {
  flex: 1 1 auto;
}

/* 手动审批的等待条：方案已出但不执行，摆明了「还差你一下」 */
.bg-approve {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid #f0d9b5;
  border-radius: 8px;
  background: #fffaf1;
}

.bg-approve b {
  display: block;
  color: #a8690f;
  font-size: 12.5px;
  font-weight: 600;
}

.bg-approve span {
  display: block;
  margin-top: 2px;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  line-height: 1.5;
}

.bg-approve-actions {
  display: flex;
  flex: none;
  gap: 8px;
}

/* 运行日志：每行一条事件，带细节的可以展开成 key/value */
.bg-log {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.bg-log-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 4px 6px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
}

.bg-log-row:hover:not(.is-static) {
  background: var(--bg-blue-soft);
}

.bg-log-row.is-static {
  cursor: default;
}

.bg-log-row time {
  flex: none;
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10.5px;
}

.bg-log-row span {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-log-body {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin: 0 0 4px 34px;
  padding: 6px 8px;
  border-left: 2px solid var(--bg-blue);
  border-radius: 0 5px 5px 0;
  background: #f7f9fc;
}

.bg-log-kv {
  display: inline-flex;
  gap: 4px;
  align-items: baseline;
  color: var(--bg-ink-2);
  font-family: var(--bg-mono);
  font-size: 10.5px;
  overflow-wrap: anywhere;
}

.bg-log-kv b {
  color: var(--bg-ink-3);
  font-weight: 500;
  font-family: inherit;
}

.bg-log-detail {
  flex: 1 1 100%;
  margin: 2px 0 0;
  color: var(--bg-ink-3);
  font-size: 10.5px;
  line-height: 1.5;
}

.bg-refine-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  margin-top: 9px;
}

.bg-refine-row .el-textarea {
  flex: 1 1 auto;
  min-width: 0;
}

.bg-refine-row .bg-primary {
  flex: none;
  height: 34px;
}

.bg-refine-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.bg-chip {
  height: 24px;
  padding: 0 9px;
  border: 1px solid var(--bg-line);
  border-radius: 12px;
  background: #fff;
  color: var(--bg-ink-2);
  font-size: 11px;
  cursor: pointer;
}

.bg-chip:hover:not(:disabled) {
  border-color: var(--bg-blue);
  color: var(--bg-blue);
}

.bg-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 目录选择器：左边常用位置 + 上方面包屑 + 右边文件夹列表，交互按资源管理器来 */
.bg-explore {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  min-height: 320px;
  border: 1px solid var(--bg-line);
  border-radius: 10px;
  overflow: hidden;
}

.bg-explore-side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 8px;
  border-right: 1px solid var(--bg-line);
  background: #fafbfd;
}

.bg-explore-head {
  margin: 8px 6px 4px;
  color: var(--bg-ink-3);
  font-size: 10.5px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.bg-explore-place {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 7px;
  background: none;
  color: var(--bg-ink-2);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.bg-explore-place:hover {
  background: #eef3fd;
}

.bg-explore-place.on {
  background: #e6efff;
  color: var(--bg-blue);
  font-weight: 600;
}

.bg-explore-place span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-explore-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #fff;
}

.bg-explore-bar {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid var(--bg-line);
}

.bg-explore-nav {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex: 0 0 auto;
  border: 1px solid var(--bg-line);
  border-radius: 6px;
  background: #fff;
  color: var(--bg-ink-2);
  cursor: pointer;
}

.bg-explore-nav:disabled {
  color: var(--bg-ink-3);
  cursor: default;
}

.bg-explore-crumbs {
  display: flex;
  gap: 2px;
  align-items: center;
  min-width: 0;
  padding: 3px 8px;
  overflow: hidden;
  border: 1px solid var(--bg-line);
  border-radius: 7px;
  background: #f8f9fb;
  flex: 1 1 auto;
}

.bg-crumb {
  max-width: 180px;
  overflow: hidden;
  padding: 2px 5px;
  border: 0;
  border-radius: 5px;
  background: none;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.bg-crumb:hover {
  background: #e8effa;
  color: var(--bg-blue);
}

.bg-crumb.on {
  color: var(--bg-ink);
  font-weight: 600;
}

.bg-crumb-sep {
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-explore-new {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  flex: 0 0 auto;
  padding: 6px 10px;
  border: 1px solid var(--bg-line);
  border-radius: 7px;
  background: #fff;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  white-space: nowrap;
  cursor: pointer;
}

.bg-explore-new:hover:not(:disabled) {
  border-color: #bfd3f7;
  background: #f1f6ff;
  color: var(--bg-blue);
}

.bg-explore-new:disabled {
  color: var(--bg-ink-3);
  cursor: default;
}

.bg-explore-new-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid var(--bg-line);
  background: #f6f9ff;
  color: var(--bg-blue);
}

.bg-explore-new-input {
  flex: 1 1 auto;
  min-width: 0;
  padding: 5px 9px;
  border: 1px solid #bfd3f7;
  border-radius: 6px;
  background: #fff;
  color: var(--bg-ink);
  font-size: 12px;
  outline: none;
}

.bg-explore-new-input:focus {
  border-color: var(--bg-blue);
  box-shadow: 0 0 0 2px rgba(43, 95, 217, 0.12);
}

.bg-explore-new-row .bg-primary,
.bg-explore-new-row .bg-ghost {
  padding: 5px 11px;
  font-size: 11.5px;
}

/* 就地改名那一行：和新建文件夹同一种输入框，避免两个视觉体系 */
.bg-explore-renaming {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 3px 10px;
  border-radius: 7px;
  background: #f6f9ff;
  color: var(--bg-blue);
}

.bg-explore-renaming .bg-primary,
.bg-explore-renaming .bg-ghost {
  padding: 4px 10px;
  font-size: 11px;
}

/* 右键菜单：贴在鼠标位置，不随滚动跑，所以用 fixed */
.bg-menu {
  position: fixed;
  z-index: 3000;
  min-width: 148px;
  padding: 5px;
  border: 1px solid var(--bg-line);
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(18, 30, 54, 0.16);
}

.bg-menu button {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 7px 9px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--bg-ink-2);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.bg-menu button:hover {
  background: #eef3fd;
  color: var(--bg-blue);
}

.bg-explore-list {
  flex: 1 1 auto;
  max-height: 300px;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  list-style: none;
}

.bg-explore-list li button {
  display: flex;
  gap: 9px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 7px;
  background: none;
  color: var(--bg-ink-2);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}

.bg-explore-list li button:hover {
  background: #f1f5fd;
  color: var(--bg-blue);
}

.bg-explore-name {
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-explore-list li button em {
  color: var(--bg-ink-3);
  font-size: 10.5px;
  font-style: normal;
}

.bg-explore-empty {
  padding: 14px 10px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

.bg-explore-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 12px;
  border-top: 1px solid var(--bg-line);
  background: #fbfcfe;
}

.bg-explore-target {
  flex: 1 1 auto;
  overflow: hidden;
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .bg-explore {
    grid-template-columns: 1fr;
  }

  .bg-explore-side {
    flex-direction: row;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid var(--bg-line);
  }

  .bg-explore-head {
    display: none;
  }

  .bg-explore-place {
    width: auto;
    white-space: nowrap;
  }

  .bg-explore-new {
    padding: 6px 8px;
    font-size: 11px;
  }

  /* 窄屏：路径输入框独占一行，按钮跟在下面 */
  .bg-dest {
    padding: 9px 10px;
  }

  .bg-dest .el-input {
    flex-basis: 100%;
  }
}

/* 跑完自动打开标准表：跟着「可执行权限」那一行走的小开关，开跑前就能定 */
.bg-auto-save {
  display: inline-flex;
  flex: none;
  white-space: nowrap;
  gap: 6px;
  align-items: center;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--bg-line);
  border-radius: 7px;
  background: #fff;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  cursor: pointer;
  user-select: none;
}

.bg-auto-save input {
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--bg-blue);
  cursor: pointer;
}

/* 导出位置提示：右下角浮层，最多 420px，不推挤正文 */
.bg-export-note {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2100;
  display: flex;
  gap: 9px;
  align-items: center;
  max-width: 472px;
  padding: 10px 12px;
  border: 1px solid var(--bg-line);
  border-radius: 9px;
  background: #fff;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  box-shadow: 0 10px 26px rgba(20, 25, 34, 0.1);
}

.bg-export-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.bg-export-path {
  max-width: 100%;
  overflow: hidden;
  color: var(--bg-ink);
  font-family: var(--bg-mono);
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-export-go {
  flex: none;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--bg-blue);
  border-radius: 6px;
  background: var(--bg-blue);
  color: #fff;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

.bg-export-go:hover {
  background: #2450bd;
}

.bg-export-link {
  flex: none;
  padding: 0 2px;
  border: 0;
  background: none;
  color: var(--bg-ink-2);
  font-size: 11.5px;
  font-weight: 400;
  cursor: pointer;
}

.bg-export-link:hover {
  color: var(--bg-blue);
  text-decoration: underline;
}

.bg-export-x {
  flex: none;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--bg-ink-3);
  font-size: 14px;
  line-height: 20px;
  cursor: pointer;
}

.bg-note-enter-active,
.bg-note-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.bg-note-enter-from,
.bg-note-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* ---------- 常驻运行条 ----------
 * 一单的「谁、跑到哪、结果几行、下一步点哪」全在这条上，钉在顶栏下方。
 * 语义上是任务头，视觉上浮在内容之上：半透明底 + 细投影，滚过表格时仍可读。
 */
.bg-runbar {
  position: sticky;
  top: 73px;
  z-index: 8;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  align-items: center;
  justify-content: space-between;
  padding: 9px 12px;
  border: 1px solid var(--bg-line);
  border-radius: 10px;
  background: rgb(255 255 255 / 93%);
  box-shadow: 0 4px 14px rgb(20 30 50 / 7%);
  backdrop-filter: blur(10px);
}

.bg-runbar-id {
  display: flex;
  flex: 1 1 auto;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.bg-runbar-title {
  overflow: hidden;
  color: var(--bg-ink);
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 关键数字用等宽字体、竖细线分隔，像仪表盘读数而不是四张大卡片 */
.bg-runbar-stats {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
}

.bg-runbar-stats span {
  display: flex;
  gap: 4px;
  align-items: baseline;
  padding: 0 9px;
  border-left: 1px solid var(--bg-line-soft);
  color: var(--bg-ink-3);
  font-size: 10.5px;
  white-space: nowrap;
}

.bg-runbar-stats span b {
  color: var(--bg-ink);
  font-family: var(--bg-mono);
  font-size: 12.5px;
  font-weight: 600;
}

.bg-runbar-stats span.is-warn b {
  color: #b4761b;
}

.bg-runbar-stats span.is-muted b {
  color: var(--bg-ink-3);
}

.bg-runbar-actions {
  display: flex;
  flex: none;
  gap: 6px;
  align-items: center;
}

/* ---------- 区块标题行 ----------
 * 原来每块都是一个独立卡片头（kicker + 大标题 + 说明 + 按钮），四块叠起来就是四屏。
 * 现在压成一行：序号、名称、一句摘要、右侧状态，点整行展开/收起。
 */
.bg-sec-row {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 11px 14px;
  border: 0;
  background: none;
  color: var(--bg-ink-2);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.bg-sec-row:hover {
  background: #fafbfd;
}

.bg-sec-row.is-static {
  cursor: default;
}

.bg-sec-row.is-static:hover {
  background: none;
}

.bg-sec-no {
  flex: none;
  padding: 1px 5px;
  border: 1px solid var(--bg-line);
  border-radius: 4px;
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10px;
  line-height: 1.5;
}

.bg-sec-row b {
  flex: none;
  color: var(--bg-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.bg-sec-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--bg-ink-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-sec-text em {
  color: #a9b0bb;
  font-style: normal;
}

.bg-sec-id {
  flex: none;
  color: var(--bg-ink-3);
  font-family: var(--bg-mono);
  font-size: 10px;
}

.bg-sec-arrow {
  flex: none;
  color: var(--bg-ink-3);
  transition: transform 0.18s ease;
}

.bg-sec-arrow.is-open {
  transform: rotate(180deg);
}

/* 折叠后的内容区更紧凑一点，分层感靠标题行就够了 */
.bg-card-body.is-tight {
  padding: 12px 14px 14px;
}

/* 卡片被 sticky 运行条盖住的问题：滚动定位时给它留出高度 */
.bg-card {
  scroll-margin-top: 130px;
}

/* ---------- 验收标准做成勾选胶囊 ----------
 * 8 条标准原本是一列文字，占掉小半屏；改成一行小胶囊，默认只露 2 条。
 */
.bg-accept {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin-top: 9px;
  color: var(--bg-ink-3);
}

.bg-list.is-inline {
  flex: 1 1 auto;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 5px 6px;
  min-width: 0;
  margin: 0;
}

.bg-list.is-inline li {
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--bg-line);
  border-radius: 999px;
  background: #fbfcfe;
  color: var(--bg-ink-2);
  font-size: 11px;
  line-height: 1.5;
}

.bg-list.is-inline li::before {
  content: '✓';
  color: #2f8f5b;
  font-family: var(--bg-mono);
  font-size: 10px;
}

/* 收起状态只留前两条，点「全部 N 条」再看全 */
.bg-list.is-inline.is-clamped li:nth-child(n + 3) {
  display: none;
}

/* ---------- 方案摘要与数字行 ---------- */
.bg-summary {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--bg-ink);
  font-size: 13.5px;
  font-weight: 550;
  line-height: 1.62;
  cursor: pointer;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.bg-summary:not(.is-clamped) {
  display: block;
  overflow: visible;
  -webkit-line-clamp: unset;
}

.bg-summary-more {
  margin-top: 2px;
  font-size: 10.5px;
}

.bg-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  margin-top: 10px;
}

.bg-stats span {
  padding: 3px 8px;
  border: 1px solid var(--bg-line);
  border-radius: 5px;
  background: #fbfcfe;
  color: var(--bg-ink-3);
  font-size: 11px;
}

.bg-stats span b {
  margin-right: 3px;
  color: var(--bg-ink);
  font-family: var(--bg-mono);
  font-size: 12px;
  font-weight: 600;
}

/* ---------- 过程与轨迹并排 ---------- */
.bg-pair {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  margin-top: 12px;
}

.bg-block.is-flat {
  margin-top: 0;
}

/* ---------- 「更多细节」折叠按钮 ---------- */
.bg-more {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  margin-top: 12px;
  padding: 8px 10px;
  border: 1px dashed var(--bg-line);
  border-radius: 8px;
  background: #fafbfd;
  color: var(--bg-ink-2);
  font-family: inherit;
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.bg-more:hover {
  border-color: #c9d8f8;
  background: var(--bg-blue-soft);
  color: var(--bg-blue);
}

.bg-more b {
  flex: none;
  font-weight: 600;
}

.bg-more > span {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--bg-ink-3);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 产物文件行 ---------- */
.bg-files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-bottom: 10px;
}

.bg-file {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 3px 8px;
  border: 1px solid var(--bg-line);
  border-radius: 6px;
  background: #fff;
  color: var(--bg-ink-2);
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
}

.bg-file:hover {
  border-color: #c9d8f8;
  color: var(--bg-blue);
}

.bg-files-time {
  margin-left: auto;
  color: var(--bg-ink-3);
  font-size: 10.5px;
}

/* ---------- 宽屏：主工作区 + 右侧运行检查器 ----------
 * 断点按内容区宽度算（容器查询），不看视口：左侧还有个导航栏要占位，
 * 1060px 以下才退回单列，所以 1440 的笔记本也能出两栏。
 * 右侧面板跟着滚动停住、内部自己滚，左栏结果表再长也不会被它挤走。
 */
@container (min-width: 1060px) {
  .bg-grid {
    grid-template-columns: minmax(0, 1fr) 380px;
    column-gap: 16px;
  }

  .bg-runbar {
    grid-column: 1 / -1;
  }

  .bg-card.is-plan {
    position: sticky;
    /* 让开顶栏（73px）+ 钉住的运行条（约 46px），否则滚到最后会被压住一截 */
    top: 122px;
    display: flex;
    flex-direction: column;
    grid-column: 2;
    /* 跨住左侧 01/02/04 三行，粘住整个主区高度 */
    grid-row: 2 / span 3;
    max-height: calc(100vh - 138px);
  }

  .bg-card.is-plan .bg-card-body {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-width: thin;
  }

  .bg-card.is-plan .bg-card-body::-webkit-scrollbar {
    width: 6px;
  }

  .bg-card.is-plan .bg-card-body::-webkit-scrollbar-thumb {
    border-radius: 3px;
    background: #dfe3e9;
  }

  /* 右栏窄，过程与轨迹并排会被压得过窄，改成纵向 */
  .bg-card.is-plan .bg-pair {
    grid-template-columns: minmax(0, 1fr);
  }

  /* 右栏只有 380px，计算/筛选/异常/输出这四块放不下两列，统一改单列 */
  .bg-card.is-plan .bg-cols {
    grid-template-columns: minmax(0, 1fr);
  }

  .bg-card.is-plan .bg-block {
    margin-top: 10px;
    padding: 10px 11px;
  }
}

@media (max-width: 860px) {
  .bg-drop-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  /* 窄屏：运行条不钉住（本来就占高度），数字行换到第二行，动作按钮只留主操作 */
  .bg-runbar {
    position: static;
    flex-wrap: wrap;
    gap: 8px;
  }

  .bg-runbar-stats {
    order: 3;
    width: 100%;
    padding-top: 6px;
    border-top: 1px solid var(--bg-line-soft);
  }

  .bg-runbar-stats span:first-child {
    border-left: 0;
    padding-left: 0;
  }

  .bg-runbar-actions {
    margin-left: auto;
  }

  .bg-runbar-actions .bg-ghost {
    display: none;
  }

  /* 过程与轨迹在窄屏下并排放不下，改回纵向 */
  .bg-pair {
    grid-template-columns: minmax(0, 1fr);
  }

  .bg-export-note {
    right: 12px;
    bottom: 12px;
    left: 12px;
    max-width: none;
  }

  .bg-card-head {
    flex-direction: column;
  }
}
</style>
