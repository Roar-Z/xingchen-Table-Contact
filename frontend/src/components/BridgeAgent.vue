<script setup>
/**
 * 智能体模式 —— 跨系统表格桥接
 *
 * 和标准模式的区别只有一点：用户不用指定表、不用给模板。
 * 上传整个数据集（zip / 文件夹），说一句业务目标，智能体自己找表、对齐字段、
 * 跨表计算、隔离异常行，最后输出能直接导入目标系统的标准表。
 *
 * 页面上的顺序就是它干活的顺序：
 * 提交数据集 → 说明业务目标 → 核对方案（用户确认）→ 执行 → 交付结果
 * 同一份进度会同步到左侧导航栏的「汇表智能体」卡片：状态、本次调用的原子能力、步数、规则条数。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ElButton,
  ElDialog,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElUpload
} from 'element-plus'
import Icon from './Icon.vue'
import {
  railDetail,
  railMemory,
  railMemoryRequest,
  railScope,
  railState,
  railSteps,
  railTools,
  railTone
} from '../agentRail'
import { api } from '../api'
import { useToast } from '../toast'

const { show } = useToast()

const SNAPSHOT_KEY = 'huibiao.bridge.v1'

const dataset = ref(null)
const pendingFiles = ref([])
const pickSource = ref('files')
const datasetName = ref('')
const instruction = ref('')
const error = ref('')

const scanning = ref(false)
const planning = ref(false)
const running = ref(false)

const folderInput = ref(null)
const instructionRef = ref(null)
const uploadRef = ref(null)

const rulesVisible = ref(false)
const rules = ref([])
const standardFields = ref([])
const ruleDraft = ref({ standard: 'emp_no', alias: '' })

const recent = ref([])
// 「最近记录」下拉：点图标展开，点空白处收起
const recentOpen = ref(false)
const openTables = ref([])

const resultTab = ref('ok')
const okTable = ref({ columns: [], rows: [], total: 0, page: 1, size: 20 })
const anomalyTable = ref({ columns: [], rows: [], total: 0, page: 1, size: 20 })
const rowsBusy = ref(false)

/* ---------------- 等待期间的过程文案：让“正在处理”可感知 ---------------- */
const PLAN_TICKS = [
  '遍历数据集目录，判定各表的业务归属…',
  '读取表头、行数与样例值…',
  '对齐各系统含义相同、命名不同的字段…',
  '确定关联键、计算口径与筛选条件…',
  '校验计算列、异常检查与输出列…',
  '汇总执行方案，等待用户核对…'
]
const RUN_TICKS = [
  '按关联键关联多张数据表…',
  '执行跨表聚合与业务计算…',
  '按日期区间与状态条件筛除记录…',
  '隔离异常行，输出标准表与异常清单…'
]

const tickIndex = ref(0)
const elapsed = ref(0)
let ticker = null

const tickText = computed(() => {
  const list = planning.value ? PLAN_TICKS : RUN_TICKS
  return list[Math.min(tickIndex.value, list.length - 1)]
})

const progressText = computed(() => {
  if (planning.value) return '正在生成执行方案'
  if (running.value) return '正在执行方案'
  return ''
})

const progressPercent = computed(() => {
  const cap = planning.value ? 92 : 96
  return Math.min(cap, 8 + elapsed.value * (planning.value ? 7 : 6))
})

function startTicker(kind) {
  stopTicker()
  tickIndex.value = 0
  elapsed.value = 0
  ticker = window.setInterval(() => {
    elapsed.value += 1
    tickIndex.value += 1
    if (kind === 'plan' && planning.value === false) stopTicker()
  }, 900)
}

function stopTicker() {
  if (ticker) {
    window.clearInterval(ticker)
    ticker = null
  }
}

/* ---------------- 示例：只填进输入框，不自动提交 ---------------- */
const EXAMPLES = [
  '把 HR 入职表和 ERP 薪资表做桥接，计算每个员工应发工资，过滤掉入职信息缺失的异常人员，输出符合 OA 导入标准的表格',
  '把 OA 请假单按工号并到员工表上，汇总每人请假天数和小时，只保留今年 4 月的记录',
  '把 OA 加班表和 HR 花名册对起来，统计每人加班小时，工号对不上的单独出一张异常清单'
]

function useExample(text) {
  if (planning.value || running.value) return
  instruction.value = text
  nextTick(() => instructionRef.value?.focus?.())
}

/* ---------------- 上传 ---------------- */
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
    const res = await api.bridgeCreate(pendingFiles.value, datasetName.value, pickSource.value)
    dataset.value = res
    pendingFiles.value = []
    uploadRef.value?.clearFiles?.()
    resultTab.value = 'ok'
    okTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    anomalyTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    saveSnapshot()
    loadRecent()
    show(`数据集扫描完成：识别 ${res.tables?.length || 0} 张表`)
  } catch (err) {
    error.value = err.message || '上传失败'
    show(error.value, 'err')
  } finally {
    scanning.value = false
  }
}

/* ---------------- 规划 ---------------- */
const plan = computed(() => dataset.value?.plan || null)
const result = computed(() => dataset.value?.result || null)

const statusText = computed(() => {
  const map = {
    scanned: '已扫描，待规划',
    planned: '方案已就绪，待用户确认',
    running: '执行中',
    done: '已完成',
    failed: '执行失败'
  }
  return map[dataset.value?.status] || '待规划'
})

const statusTone = computed(() => {
  if (dataset.value?.status === 'done') return 'ok'
  if (dataset.value?.status === 'planned') return 'wait'
  if (dataset.value?.status === 'failed') return 'err'
  return 'idle'
})

/* ---------------- 左侧「操作步骤」状态条 ----------------
 * 桥接模式的操作清单不是写死的六条：规划器排了几步，这里就按执行顺序列几项，
 * 每项后面跟一句它具体做什么。用户在左侧即可看到这次要做的环节与进度。
 */
const BRIDGE_TOOLS = {
  scan_dataset: { label: '扫描检索', hint: '找出数据集里的全部表格' },
  parse_sheets: { label: '表格解析', hint: '读取表头、行数和示例内容' },
  detect_kind: { label: '业务识别', hint: '判断表格属于人事、薪酬还是考勤' },
  align_fields: { label: '字段对齐', hint: '把不同表格中含义相同的列对应起来' },
  join_tables: { label: '跨表关联', hint: '按工号或姓名把多张表拼在一起' },
  filter_rows: { label: '条件筛选', hint: '按日期、在职状态等条件保留记录' },
  compute: { label: '业务计算', hint: '汇总合计并按公式算出新列' },
  isolate_anomaly: { label: '异常隔离', hint: '把缺项、格式错误、对不上的记录单独列出' },
  standardize_export: { label: '标准化导出', hint: '按目标系统的列顺序生成可导入文件' }
}

// 规划阶段真正会跑掉的原子能力：扫描、解析、业务识别、字段对齐。
// 规划过程中只亮这四项，且逐个点亮；跨表关联、计算、异常隔离、导出要等用户确认执行，
// 不能在规划阶段就全部点亮。
const PLAN_PHASE_TOOLS = ['scan_dataset', 'parse_sheets', 'detect_kind', 'align_fields']
const PLAN_STAGE_TOOLS = new Set(PLAN_PHASE_TOOLS)

const railStateText = computed(() => {
  if (scanning.value) return '扫描中'
  if (planning.value) return '规划中'
  if (running.value) return '执行中'
  if (error.value) return '需处理'
  if (result.value) return '已交付'
  if (plan.value) return '待确认'
  if (dataset.value) return '待下达目标'
  return '待上传'
})

const railToneOf = computed(() => {
  const text = railStateText.value
  if (text === '已交付') return 'ok'
  if (['扫描中', '规划中', '执行中'].includes(text)) return 'busy'
  if (['待确认', '待下达目标', '需处理'].includes(text)) return 'wait'
  return 'idle'
})

const railToolsOf = computed(() => {
  // 规划进行中（含重新规划）：只按规划阶段的四项逐个点亮，每 2 秒推进一格，
  // 走完就停在最后一项继续闪烁，不再往后亮，避免看起来“全部跑完”。
  if (planning.value) {
    const count = Math.min(PLAN_PHASE_TOOLS.length, 1 + Math.floor(elapsed.value / 2))
    const finished = count >= PLAN_PHASE_TOOLS.length
    const items = PLAN_PHASE_TOOLS.slice(0, count).map((key, index) => ({
      key,
      ...BRIDGE_TOOLS[key],
      // 本项转圈；走到最后一项时四项都算完成，交给下面的「方案编排」继续转
      state: finished || index < count - 1 ? 'done' : 'busy'
    }))
    // 四项都点亮之后才开始等模型编排，这时才出现「方案编排」，
    // 不能提前排在清单里，否则看起来像跳步
    if (finished) {
      items.push({
        key: 'planner',
        label: '方案编排',
        hint: '确定关联方式、计算规则与输出列',
        state: 'busy'
      })
    }
    return items
  }
  const steps = plan.value?.steps || []
  if (!steps.length) return []
  const executed = dataset.value?.status === 'done' || !!result.value
  // 执行阶段只在真正的执行项上推进：每 1.2 秒点一格，当前项转圈，后面的保持未开始
  const execTotal = steps.filter((step) => !PLAN_STAGE_TOOLS.has(step.tool)).length
  const execLit = running.value ? Math.min(execTotal, 1 + Math.floor(elapsed.value / 1.2)) : 0
  let execIndex = 0
  return steps.map((step, index) => {
    const meta = BRIDGE_TOOLS[step.tool] || { label: step.title || step.tool, hint: step.detail || '' }
    const planningStage = PLAN_STAGE_TOOLS.has(step.tool)
    let state = 'idle'
    if (planningStage) {
      // 这几项在规划阶段已经真跑过，直接算完成
      state = 'done'
    } else if (running.value) {
      state = execIndex < execLit - 1 ? 'done' : execIndex === execLit - 1 ? 'busy' : 'idle'
    } else if (executed) {
      state = 'done'
    }
    if (!planningStage) execIndex += 1
    return {
      key: `${step.tool}-${index}`,
      label: meta.label,
      hint: step.detail || meta.hint,
      state
    }
  })
})

// 状态下方的一句摘要：规划中说明正在编排什么，出方案后写清本次规模
const railDetailOf = computed(() => {
  if (planning.value) return '正在确定关联方式、计算规则、筛选条件与输出列'
  const current = plan.value
  if (current) {
    const parts = []
    const aligns = current.aligns?.length || 0
    const computes = current.computes?.length || 0
    const outputs = current.outputColumns?.length || 0
    if (aligns) parts.push(`对齐 ${aligns} 个字段`)
    if (computes) parts.push(`计算 ${computes} 列`)
    if (outputs) parts.push(`输出 ${outputs} 列`)
    return parts.join(' · ')
  }
  if (dataset.value) return `${dataset.value.tables?.length || 0} 张表已就绪，待下达业务目标`
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
}

watch(
  () => [railStateText.value, railToneOf.value, plan.value?.steps?.length, railToolsOf.value, railDetailOf.value, rules.value.length],
  syncRail,
  { immediate: true, deep: true }
)

// 导航栏点「管理」→ 打开自定义字段匹配规则
watch(railMemoryRequest, (n) => {
  if (n && railScope.value === 'bridge') rulesVisible.value = true
})

const canPlan = computed(() => !!dataset.value && instruction.value.trim().length > 0 && !planning.value && !running.value)

async function planNow(silent = false) {
  if (!dataset.value || !canPlan.value) return
  planning.value = true
  error.value = ''
  startTicker('plan')
  try {
    const res = await api.bridgePlan(dataset.value.id, instruction.value.trim())
    dataset.value = res
    saveSnapshot()
    if (!silent) show('执行方案已生成，请核对字段映射与计算口径后再执行')
  } catch (err) {
    error.value = err.message || '规划失败'
    show(error.value, 'err')
  } finally {
    planning.value = false
    stopTicker()
  }
}

/* ---------------- 执行 ---------------- */
const canRun = computed(() => !!plan.value && !running.value && !planning.value)

async function runNow() {
  if (!canRun.value) return
  running.value = true
  error.value = ''
  startTicker('run')
  try {
    const res = await api.bridgeRun(dataset.value.id)
    dataset.value = res
    resultTab.value = 'ok'
    await loadRows('ok', 1)
    saveSnapshot()
    show(`执行完成：标准表 ${res.result?.ok_rows ?? 0} 行，异常 ${res.result?.anomaly_rows ?? 0} 行`)
  } catch (err) {
    error.value = err.message || '执行失败'
    show(error.value, 'err')
  } finally {
    running.value = false
    stopTicker()
    loadRecent()
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

function download(artifact) {
  if (!dataset.value) return
  window.open(api.bridgeDownloadUrl(dataset.value.id, artifact), '_blank')
}

/* ---------------- 字段对齐：改一次就存成规则，下次同类报表自动复用 ---------------- */
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
    show(`已保存为自定义规则：${row.column} → ${fieldLabel(field)}，并按新规则重新规划`)
    await planNow(true)
  } catch (err) {
    show(err.message || '保存规则失败', 'err')
  }
}

async function addRule() {
  if (!ruleDraft.value.alias.trim()) {
    show('请填写原始列名', 'err')
    return
  }
  try {
    rules.value = await api.bridgeAddRule(ruleDraft.value.standard, ruleDraft.value.alias.trim())
    ruleDraft.value.alias = ''
    show('规则已保存，下次遇到同一列名将直接套用')
  } catch (err) {
    show(err.message || '保存规则失败', 'err')
  }
}

async function deleteRule(rule) {
  try {
    rules.value = await api.bridgeDeleteRule(rule.alias)
    show('规则已删除')
  } catch (err) {
    show(err.message || '删除规则失败', 'err')
  }
}

/* ---------------- 最近的数据集 / 现场恢复 ---------------- */
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

/* 最近记录里的勾选与清理：可以全清，也可以只清选中的几条 */
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
    ? `确定删除选中的 ${count} 条最近记录？对应源表与产出文件会一起清理。`
    : `确定清空全部 ${count} 条最近记录？对应源表与产出文件会一起清理。`
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
    show(`已删除 ${res?.deleted ?? count} 条最近记录`)
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
  resultTab.value = 'ok'
  pendingFiles.value = []
  datasetName.value = ''
  error.value = ''
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

/* ---------------- 展示辅助 ---------------- */
const modeBadge = computed(() => {
  if (!plan.value) return ''
  return plan.value.mode === 'llm' ? '大模型参与规划' : '规则规划'
})

const alignCount = computed(() => plan.value?.aligns?.length || 0)
const customAlignCount = computed(() => plan.value?.aligns?.filter((a) => a.origin !== '规则').length || 0)
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
  try {
    standardFields.value = await api.bridgeStandardFields()
  } catch (e) {
    standardFields.value = []
  }
  loadRecent()
  loadRules()
  restoreSnapshot()
  document.addEventListener('click', onDocClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  stopTicker()
  saveSnapshot()
  // 只有状态条还归桥接模式时才清空；已经切回标准模式的话，那边会自己重写
  if (railScope.value === 'bridge') {
    railTools.value = []
    railSteps.value = 0
    railDetail.value = ''
    railMemory.value = 0
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
}
</script>

<template>
  <div class="bg">
    <!-- ==================== 0. 还没有数据集：交付入口 ==================== -->
    <section v-if="!dataset" class="bg-card">
      <header class="bg-card-head">
        <div>
          <span class="bg-kicker">跨系统表格桥接</span>
          <h2>提交整个数据集，无需指定文件名</h2>
          <p>上传一个压缩包，或直接选择整个导出目录。智能体自行定位数据表、判定业务归属并对齐字段，用户只需说明业务目标。</p>
        </div>
        <!-- 最近记录：图标放在「核心能力」左边，悬浮提示、点击展开 -->
        <div class="bg-head-side">
          <button
            class="bg-recent-btn"
            type="button"
            title="最近记录"
            aria-label="最近记录"
            @click.stop="toggleRecent"
          >
            <Icon name="history" :size="14" />
          </button>
          <span class="bg-core">核心能力</span>

          <div v-if="recentOpen" class="bg-recent-pop" @click.stop>
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
              <span class="bg-drop-icon"><Icon name="database" :size="20" /></span>
              <b>拖入压缩包或表格文件</b>
              <span>支持 .zip / .xlsx / .xls / .csv，可一次提交多个</span>
            </div>
          </el-upload>

          <button class="bg-folder" type="button" :disabled="scanning" @click="folderInput?.click()">
            <span class="bg-drop-icon"><Icon name="folder" :size="20" /></span>
            <b>选择整个文件夹</b>
            <span>将整个导出目录一并提交</span>
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
          <el-input v-model="datasetName" size="small" placeholder="例如 2026 年 4 月人事薪资数据集" />
          <button class="bg-primary" :disabled="!canScan" @click="startScan">
            <Icon v-if="!scanning" name="search" :size="13" />
            {{ scanning ? '正在扫描表格…' : '扫描这个数据集' }}
          </button>
        </div>

        <p v-if="error" class="bg-error">{{ error }}</p>

        <div class="bg-tips">
          <b>执行流程</b>
          <ol>
            <li>扫描检索：遍历数据集目录，按文件名与表头判定每张表的业务归属</li>
            <li>表格解析：读取各表的表头、行数与样例值，缓存表结构供后续复用</li>
            <li>字段对齐：将各系统中含义相同、命名不同的字段对齐到统一标准字段，并支持自定义规则</li>
            <li>自主规划：规划器按业务目标编排子任务，给出关联键、计算口径、筛选条件、异常检查与输出列</li>
            <li>人工核对：方案与字段映射交由用户核对，修正后的映射可直接保存为可复用规则</li>
            <li>跨表执行：按关联键关联多表，执行聚合与加减计算、条件筛选，命中检查规则的行单独隔离</li>
            <li>标准化交付：按目标系统的列顺序与日期格式输出标准表，同时生成异常清单</li>
          </ol>
        </div>
      </div>
    </section>

    <!-- ==================== 1. 数据集已扫描 ==================== -->
    <template v-else>
      <section class="bg-card">
        <header class="bg-card-head">
          <div>
            <span class="bg-kicker">数据集 · {{ dataset.id }}</span>
            <h2>{{ dataset.name }}</h2>
            <p>{{ dataset.tables?.length || 0 }} 张表，共 {{ dataset.tables?.reduce((sum, t) => sum + (t.rows || 0), 0) || 0 }} 行数据，来源 {{ dataset.source_type === 'folder' ? '文件夹' : dataset.source_type === 'zip' ? '压缩包' : '文件' }}</p>
          </div>
          <div class="bg-head-tools">
            <span class="bg-status" :class="`is-${statusTone}`"><i />{{ statusText }}</span>
            <button class="bg-ghost" type="button" @click="rulesVisible = true">
              <Icon name="sliders" :size="13" /> 匹配规则
            </button>
            <button class="bg-ghost" type="button" @click="resetDataset">换一个数据集</button>
          </div>
        </header>

        <div class="bg-card-body">
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
          </ul>

          <p v-for="note in dataset.notes || []" :key="note" class="bg-note">{{ note }}</p>
        </div>
      </section>

      <!-- ==================== 2. 自然语言指令 ==================== -->
      <section class="bg-card">
        <header class="bg-card-head">
          <div>
            <span class="bg-kicker">业务目标</span>
            <h2>以自然语言描述本次桥接目标</h2>
            <p>无需指定文件名与列名。智能体自行定位数据表，并判定不同系统间含义相同的字段。</p>
          </div>
        </header>
        <div class="bg-card-body">
          <el-input
            ref="instructionRef"
            v-model="instruction"
            type="textarea"
            :rows="3"
            maxlength="600"
            show-word-limit
            placeholder="例如：把 HR 入职表和 ERP 薪资表做桥接，计算每个员工应发工资，过滤掉入职信息缺失的异常人员，输出符合 OA 导入标准的表格"
          />
          <div class="bg-samples">
            <span class="bg-label">示例指令</span>
            <button
              v-for="text in EXAMPLES"
              :key="text"
              class="bg-sample"
              type="button"
              :title="text"
              @click="useExample(text)"
            >
              {{ text.slice(0, 26) }}…
            </button>
          </div>

          <div class="bg-actions">
            <span class="bg-hint">
              {{ plan ? '方案已生成；修改业务目标后可重新规划' : '智能体先输出方案，经核对确认后才执行' }}
            </span>
            <button class="bg-primary" :disabled="!canPlan" @click="planNow(false)">
              <Icon name="spark" :size="14" />
              {{ planning ? '正在规划…' : plan ? '重新规划' : '让智能体规划' }}
            </button>
          </div>

          <p v-if="error" class="bg-error">{{ error }}</p>

          <div v-if="planning" class="bg-progress">
            <div class="bg-progress-bar"><i :style="{ width: progressPercent + '%' }" /></div>
            <span>{{ tickText }}<em>已等待 {{ elapsed }}s</em></span>
          </div>
        </div>
      </section>

      <!-- ==================== 3. 方案 ==================== -->
      <section v-if="plan" class="bg-card">
        <header class="bg-card-head">
          <div>
            <span class="bg-kicker">规划方案</span>
            <h2>{{ plan.summary }}</h2>
            <p>下面是它的判断依据和具体动作，确认无误再执行。</p>
          </div>
          <div class="bg-head-tools">
            <span class="bg-badge" :class="plan.mode === 'llm' ? 'is-ai' : 'is-rule'">{{ modeBadge }}</span>
            <span v-if="dataset.status === 'done'" class="bg-badge is-ok">已执行</span>
          </div>
        </header>

        <div class="bg-card-body">
          <div class="bg-tiles">
            <div class="bg-tile">
              <span>数据表</span>
              <b>{{ dataset.tables?.length || 0 }}<em>张</em></b>
              <small>{{ plan.sources?.filter((s) => s.role === '主表').length ? '一张主表 + 关联表' : '单表标准化' }}</small>
            </div>
            <div class="bg-tile">
              <span>字段对齐</span>
              <b>{{ alignCount }}<em>个</em></b>
              <small>{{ customAlignCount ? `其中 ${customAlignCount} 个来自自定义/AI` : '全部由规则与词典对齐' }}</small>
            </div>
            <div class="bg-tile">
              <span>计算列</span>
              <b>{{ computeCount }}<em>个</em></b>
              <small>{{ computeCount ? '跨表计算后生成' : '本次不需要额外计算' }}</small>
            </div>
            <div class="bg-tile">
              <span>输出列</span>
              <b>{{ outputCount }}<em>列</em></b>
              <small>按目标系统习惯排列</small>
            </div>
          </div>

          <div class="bg-block">
            <div class="bg-block-head"><Icon name="terminal" :size="14" /><b>执行轨迹</b><span>{{ plan.steps?.length || 0 }} 步</span></div>
            <ol class="bg-steps">
              <li v-for="(step, index) in plan.steps" :key="step.tool + index" :class="`is-${step.state}`">
                <span class="bg-step-no">{{ index + 1 }}</span>
                <div>
                  <p><b>{{ step.title }}</b><code>{{ step.tool }}</code><em v-if="step.ms">{{ elapsedText(step.ms) }}</em>
                    <span v-if="step.state === 'pending'" class="bg-step-pending">待用户确认</span>
                  </p>
                  <span class="bg-step-detail">{{ step.detail }}</span>
                </div>
              </li>
            </ol>
          </div>

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

          <div class="bg-run">
            <span class="bg-hint">
              {{ dataset.status === 'done' ? '已执行过一次，可按当前方案重新执行' : '方案经用户确认后才会执行，不会自动改动原始数据' }}
            </span>
            <button class="bg-primary bg-primary-lg" :disabled="!canRun" @click="runNow">
              <Icon name="play" :size="14" />
              {{ running ? '正在执行…' : dataset.status === 'done' ? '按方案重跑' : '用户确认并执行' }}
            </button>
          </div>

          <div v-if="running" class="bg-progress">
            <div class="bg-progress-bar"><i :style="{ width: progressPercent + '%' }" /></div>
            <span>{{ tickText }}<em>已等待 {{ elapsed }}s</em></span>
          </div>
        </div>
      </section>

      <!-- ==================== 4. 结果 ==================== -->
      <section v-if="result" class="bg-card">
        <header class="bg-card-head">
          <div>
            <span class="bg-kicker">交付结果</span>
            <h2>标准表 {{ result.ok_rows }} 行，异常 {{ result.anomaly_rows }} 行</h2>
            <p>{{ result.table_count }} 张表参与桥接，耗时 {{ elapsedText(result.elapsed_ms) }}，完成于 {{ result.finished_at }}</p>
          </div>
          <div class="bg-head-tools">
            <button
              v-for="artifact in result.artifacts"
              :key="artifact"
              class="bg-ghost"
              type="button"
              @click="download(artifact)"
            >
              <Icon name="download" :size="13" /> {{ artifact }}
            </button>
          </div>
        </header>

        <div class="bg-card-body">
          <div class="bg-tiles">
            <div class="bg-tile">
              <span>桥接结果</span>
              <b>{{ result.total_rows }}<em>行</em></b>
              <small>按关联键合并后，正常 + 异常</small>
            </div>
            <div class="bg-tile">
              <span>正常输出</span>
              <b>{{ result.ok_rows }}<em>行</em></b>
              <small>可直接导入目标系统</small>
            </div>
            <div class="bg-tile" :class="{ 'is-warn': result.anomaly_rows > 0 }">
              <span>异常隔离</span>
              <b>{{ result.anomaly_rows }}<em>行</em></b>
              <small>单独出清单，不影响标准表</small>
            </div>
            <div class="bg-tile">
              <span>输出列</span>
              <b>{{ result.columns?.length || 0 }}<em>列</em></b>
              <small>{{ result.mode === 'llm' ? '大模型参与规划' : '规则规划' }}</small>
            </div>
          </div>

          <el-tabs v-model="resultTab" class="bg-tabs" @tab-change="changeTab">
            <el-tab-pane label="标准表" name="ok">
              <div class="bg-rows-head">
                <span>共 {{ okTable.total || result.ok_rows }} 行</span>
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
            </el-tab-pane>

            <el-tab-pane :label="`异常清单（${result.anomaly_rows}）`" name="anomaly">
              <div class="bg-rows-head">
                <span>共 {{ anomalyTable.total || result.anomaly_rows }} 行，命中原因都写在里面</span>
                <button class="bg-link" type="button" :disabled="rowsBusy" @click="loadRows('anomaly', anomalyTable.page)">刷新</button>
              </div>
              <el-table :data="rowsOf(anomalyTable)" border size="small" max-height="420" class="bg-table-el">
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
            </el-tab-pane>
          </el-tabs>
        </div>
      </section>
    </template>

    <!-- ==================== 自定义匹配规则 ==================== -->
    <el-dialog v-model="rulesVisible" title="自定义字段匹配规则" width="620px" class="bg-dialog">
      <p class="bg-dialog-desc">
        规则保存在服务器数据库（data/huibiao.db），跨数据集复用。新增报表里出现同样的列名时会直接对齐，不用每次重新判断。
      </p>
      <div class="bg-rule-add">
        <el-select v-model="ruleDraft.standard" size="small" filterable class="bg-w-sm">
          <el-option
            v-for="item in standardFields"
            :key="item.field"
            :label="item.label"
            :value="item.field"
          />
        </el-select>
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
    </el-dialog>
  </div>
</template>

<style scoped>
.bg {
  --bg-ink: #141922;
  --bg-ink-2: #4b5563;
  --bg-ink-3: #8b929e;
  --bg-line: #e4e8ef;
  --bg-line-soft: #eef1f6;
  --bg-blue: #2b5fd9;
  --bg-blue-soft: #eef3ff;
  --bg-mono: ui-monospace, SFMono-Regular, "SF Mono", Consolas, "Liberation Mono", monospace;

  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1080px;
}

.bg-card {
  overflow: hidden;
  border: 1px solid var(--bg-line);
  border-radius: 10px;
  background: #fff;
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

.bg-head-tools {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}

.bg-card-body {
  padding: 18px 20px 20px;
}

/* 上传 */
.bg-drop-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
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
  padding: 18px;
  border: 1px dashed #ccd4e2;
  border-radius: 9px;
  background: #fbfcfe;
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
  gap: 3px;
  align-items: center;
  text-align: center;
}

.bg-drop-inner b,
.bg-folder b {
  color: var(--bg-ink);
  font-size: 13px;
  font-weight: 600;
}

.bg-drop-inner > span:last-child,
.bg-folder > span:last-child {
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

.bg-drop-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  margin-bottom: 2px;
  border: 1px solid #d7e0f2;
  border-radius: 9px;
  background: #fff;
  color: var(--bg-blue);
}

.bg-folder {
  align-items: center;
  justify-content: center;
  padding: 18px;
  border: 1px dashed #ccd4e2;
  border-radius: 9px;
  background: #fbfcfe;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.bg-folder:hover:not(:disabled) {
  border-color: var(--bg-blue);
  background: var(--bg-blue-soft);
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
  gap: 10px;
  align-items: center;
  margin-top: 14px;
}

.bg-label {
  flex: none;
  color: var(--bg-ink-2);
  font-size: 12px;
  font-weight: 600;
}

.bg-naming :deep(.el-input) {
  max-width: 320px;
}

.bg-primary,
.bg-ghost {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 15px;
  border-radius: 7px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.16s ease;
}

.bg-primary {
  border: 1px solid var(--bg-blue);
  background: var(--bg-blue);
  color: #fff;
  box-shadow: 0 3px 8px rgb(43 95 217 / 20%);
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

.bg-primary-lg {
  height: 40px;
  padding: 0 20px;
  font-size: 13px;
}

.bg-ghost {
  border: 1px solid #d8dde7;
  background: #fff;
  color: var(--bg-ink-2);
}

.bg-ghost:hover:not(:disabled) {
  border-color: #b9c3d4;
  color: var(--bg-ink);
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

.bg-progress > span {
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

/* 方案 */
.bg-badge {
  padding: 2px 8px;
  border: 1px solid var(--bg-line);
  border-radius: 999px;
  font-size: 10.5px;
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

.bg-tiles {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.bg-tile {
  padding: 11px 12px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fbfcfe;
}

.bg-tile > span {
  color: var(--bg-ink-3);
  font-size: 10.5px;
}

.bg-tile b {
  display: block;
  margin: 2px 0 1px;
  color: var(--bg-ink);
  font-family: var(--bg-mono);
  font-size: 19px;
  font-weight: 600;
  line-height: 1.2;
}

.bg-tile b em {
  margin-left: 3px;
  color: var(--bg-ink-3);
  font-family: inherit;
  font-size: 10.5px;
  font-style: normal;
}

.bg-tile small {
  color: var(--bg-ink-3);
  font-size: 10.5px;
  line-height: 1.45;
}

.bg-tile.is-warn {
  border-color: #f0d9b5;
  background: #fff8ec;
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

.bg-steps li.is-pending .bg-step-no {
  border-color: #e2e5ea;
  background: #f7f8fa;
  color: var(--bg-ink-3);
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

.bg-step-pending {
  padding: 1px 6px;
  border: 1px solid #f0d9b5;
  border-radius: 999px;
  background: #fff8ec;
  color: #a96400;
  font-size: 10px;
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

.bg-rows-head {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--bg-ink-3);
  font-size: 11.5px;
}

.bg-pager {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 10px;
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

@media (max-width: 860px) {
  .bg-drop-grid,
  .bg-tiles {
    grid-template-columns: minmax(0, 1fr);
  }

  .bg-card-head {
    flex-direction: column;
  }

  .bg-head-tools {
    justify-content: flex-start;
  }
}
</style>
