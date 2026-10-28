<script setup>
/**
 * 智能体模式 —— 跨系统表格桥接
 *
 * 和标准模式的区别只有一点：用户不用指定表、不用给模板。
 * 上传整个数据集（zip / 文件夹），说一句业务目标，智能体自己找表、对齐字段、
 * 跨表计算、隔离异常行，最后输出能直接导入目标系统的标准表。
 *
 * 页面上的顺序就是它干活的顺序：
 * 交付数据集 → 自然语言指令 → 规划方案（用户确认）→ 执行 → 结果
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
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

const rulesVisible = ref(false)
const rules = ref([])
const standardFields = ref([])
const ruleDraft = ref({ standard: 'emp_no', alias: '' })

const recent = ref([])
const openTables = ref([])

const resultTab = ref('ok')
const okTable = ref({ columns: [], rows: [], total: 0, page: 1, size: 20 })
const anomalyTable = ref({ columns: [], rows: [], total: 0, page: 1, size: 20 })
const rowsBusy = ref(false)

/* ---------------- 等待期间的过程文案：让“在干活”可感知 ---------------- */
const PLAN_TICKS = [
  '扫描数据集目录，逐个识别表格身份…',
  '解析表头、行数和样例值…',
  '区分 HR 入职表 / ERP 薪资表 / OA 单据…',
  '对齐不同系统的同义字段…',
  '确定关联键、计算口径和筛选条件…',
  '整理方案与异常规则…'
]
const RUN_TICKS = [
  '按关联键把多张表接起来…',
  '执行跨表计算与条件筛选…',
  '逐行检查空值、重复主键和格式错误…',
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
  if (planning.value) return '智能体正在规划'
  if (running.value) return '智能体正在执行'
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
    resultTab.value = 'ok'
    okTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    anomalyTable.value = { columns: [], rows: [], total: 0, page: 1, size: 20 }
    saveSnapshot()
    loadRecent()
    show(`扫描完成：${res.tables?.length || 0} 张表`)
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
    if (!silent) show('方案已经出来了，请核对后执行')
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
    show(`已记住：${row.column} → ${fieldLabel(field)}，并按新规则重新规划`)
    await planNow(true)
  } catch (err) {
    show(err.message || '保存规则失败', 'err')
  }
}

async function addRule() {
  if (!ruleDraft.value.alias.trim()) {
    show('先填一下原始列名', 'err')
    return
  }
  try {
    rules.value = await api.bridgeAddRule(ruleDraft.value.standard, ruleDraft.value.alias.trim())
    ruleDraft.value.alias = ''
    show('规则已保存，下次遇到这个列名会直接对齐')
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

async function openDataset(item) {
  if (!item?.id) return
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
  try {
    standardFields.value = await api.bridgeStandardFields()
  } catch (e) {
    standardFields.value = []
  }
  loadRecent()
  loadRules()
  restoreSnapshot()
})

onBeforeUnmount(() => {
  stopTicker()
  saveSnapshot()
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
          <h2>把整个数据集交上来，不用挑表</h2>
          <p>一个压缩包，或者直接选一整个文件夹。智能体自己找表、理解表、对齐字段，你只需要说清业务目标。</p>
        </div>
        <span class="bg-core">核心能力</span>
      </header>

      <div class="bg-card-body">
        <div class="bg-drop-grid">
          <el-upload
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
              <b>拖入压缩包 / Excel</b>
              <span>支持 .zip / .xlsx / .xls / .csv，可一次放多个</span>
            </div>
          </el-upload>

          <button class="bg-folder" type="button" :disabled="scanning" @click="folderInput?.click()">
            <span class="bg-drop-icon"><Icon name="folder" :size="20" /></span>
            <b>选择一个文件夹</b>
            <span>把整个导出目录直接交给智能体</span>
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

        <div v-if="recent.length" class="bg-recent">
          <span class="bg-label">最近的数据集</span>
          <ul>
            <li v-for="item in recent.slice(0, 4)" :key="item.id" @click="openDataset(item)">
              <b>{{ item.name }}</b>
              <span>{{ item.created_at }} · {{ item.tables?.length || 0 }} 张表 · {{ item.instruction ? '已规划' : '未规划' }}</span>
            </li>
          </ul>
        </div>

        <div class="bg-tips">
          <b>它按这五步干活</b>
          <ol>
            <li>扫描数据集，给每张表安一个业务身份</li>
            <li>解析表头与样例值，把不同系统的同义字段对齐到标准字段</li>
            <li>按业务目标做跨表关联、计算和筛选</li>
            <li>识别空值、重复主键、对不上的行，单独出异常清单</li>
            <li>按目标系统的列顺序输出标准表，可直接导入</li>
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
            <h2>用一句话说清这单要做什么</h2>
            <p>不用写文件名，也不用写字段名。它自己会去找哪张表、哪两列是一回事。</p>
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
            <span class="bg-label">试试</span>
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
              {{ plan ? '方案已生成，改完目标可以重新规划' : '智能体不会直接跑，先出方案给你核对' }}
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
              {{ dataset.status === 'done' ? '已经执行过一次，可以按当前方案重跑' : '方案经用户确认后才会执行，不会自动改动数据' }}
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
        规则保存在服务器（data/bridge/rules.json），跨数据集复用。新增报表里出现同样的列名时会直接对齐，不用每次重新判断。
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

.bg-recent {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--bg-line-soft);
}

.bg-recent ul {
  display: grid;
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  list-style: none;
}

.bg-recent li {
  padding: 9px 11px;
  border: 1px solid var(--bg-line);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.bg-recent li:hover {
  border-color: #c5d3ee;
  background: #f8faff;
}

.bg-recent li b {
  display: block;
  overflow: hidden;
  color: var(--bg-ink);
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-recent li span {
  color: var(--bg-ink-3);
  font-size: 11px;
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
