<script setup>
// 表格智作 —— 页面主体
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import ResultTable from '../components/ResultTable.vue'
import { api, apiState, isNetworkError } from '../api'
import { OFFLINE_HINT, composeLocalSheet, downloadCsv, loadLocalSheets, makeLocalSheet, readTable, saveLocalSheets } from '../offline'
import { clearNotice, showNotice } from '../appNotice'
import { useToast } from '../toast'
import { clearTopbar, useTopbar } from '../topbar'

// 和 App.vue 里 keep-alive 的 include 一致：切到别的页面再回来时，
// 清单、已展开的表格和输入内容都还在，不会重新挂载闪一次骨架
defineOptions({ name: 'SheetWorkbench' })

const route = useRoute()
const { show } = useToast()

// keep-alive 收回后顶栏会被别的页面顶掉，激活时要重新挂回来
function mountTopbar() {
  useTopbar({
    title: '表格智作',
    desc: offlineMode.value
      ? '当前离线：Excel / CSV 在本地解析，按规则建表、合并，结果下载为 CSV；联网后自动切回在线模式。'
      : '上传 Excel 直接改，或者用一句话建表、合并；每张表都是一份独立的文件，改完就能下载。',
    // 建表入口放在右上角，和「我的仓库」并排；页面里不再重复摆一个同名按钮。
    // 离线时「新建空白表」要连后端建文件，先不给按钮，改用页面里的本地建表
    actions: offlineMode.value
      ? []
      : [{ key: 'blank', label: '新建空白表', icon: 'plus', hint: '填列名新建一张空表' }],
    onAction: (key) => {
      if (key === 'blank') openDialog('blank')
    }
  })
}

// 断网/恢复会改变顶栏说明和按钮，切一下状态就重新挂一次
watch(() => apiState.online, () => mountTopbar())

onBeforeUnmount(() => clearTopbar())

// 页面被 keep-alive 缓存后，切走时只会触发 deactivated、不会卸载；
// 这里必须手动收起顶栏，否则「任务记录 / 运行观测」会顶着“表格智作”的标题和说明
onDeactivated(() => clearTopbar())

const tables = ref([])
const loading = ref(true)
const listError = ref('')
const keyword = ref('')
const selected = ref([])
const opened = ref([])
const details = reactive({})
// 每张表单独一个计数，改完后让下面的表格重新拉第一页
const refreshing = reactive({})
// 加完行后通知表格翻到最后一页
const lastSignal = reactive({})
const drafts = reactive({})
const revising = reactive({})
const revErrors = reactive({})
const cardBusy = reactive({})

const uploadBusy = ref(false)
const fileInput = ref(null)
let searchTimer = null

// ==================== 一句话 ====================
const composerText = ref('')
const composerInput = ref(null)
const composerBusy = ref(false)
const composerError = ref('')
// 展开表格时默认收起：卡片里已有「改这张表」，两个输入框不该同时挤在页尾
const composerOpen = ref(false)
const showComposer = computed(() => !opened.value.length || composerOpen.value)
// 示例只负责把说法填进输入框，发不发由用户点「生成」决定
const composerSamples = [
  '新建一张表，列：检查日期、车间、负责人、检查结果、整改期限',
  '新建一张月度费用表，列：日期、部门、金额、备注',
  '把选中的两张表按工号合并'
]

// ==================== 离线模式 ====================
const offlineMode = computed(() => apiState.online === false)
const localTables = ref([])
const localSelected = ref([])
const localOpen = ref('')
const localBusy = ref(false)
const localDragging = ref(false)
const localFileInput = ref(null)
const offlineText = ref('')
const offlineError = ref('')

const offlineSamples = [
  '新建一张表，列：检查日期、车间、负责人、检查结果、整改期限',
  '新建一张月度费用表，列：日期、部门、金额、备注',
  '把选中的两张表按车间合并',
  '把选中的两张表按日期合并'
]

// 勾选两张以上时告诉用户「这几张表共有哪些列」，按哪一列合并都能自己说，
const localJoinHint = computed(() => {
  const picked = localTables.value.filter((item) => localSelected.value.includes(item.id))
  if (picked.length < 2) return ''
  const shared = picked[0].columns.filter((column) => picked.every((item) => item.columns.includes(column)))
  if (!shared.length) return '这几张表没有同名的列，只能按列名并排拼起来'
  const shown = shared.slice(0, 8).join('、') + (shared.length > 8 ? ` 等 ${shared.length} 列` : '')
  return `共有列：${shown}（说「按其中的列合并」即可，如「按车间合并」）`
})

const openLocalTable = computed(() => localTables.value.find((item) => item.id === localOpen.value) || null)

function loadLocal() {
  localTables.value = loadLocalSheets()
  if (!localTables.value.some((item) => item.id === localOpen.value)) {
    localOpen.value = localTables.value[0] ? localTables.value[0].id : ''
  }
}

function persistLocal() {
  saveLocalSheets(localTables.value)
}

function localMeta(table) {
  return `${table.rows.length} 行 · ${table.columns.length} 列 · ${table.updated_at}`
}

// 预览只给前 30 行：离线表可能有几千行，整表渲染既慢又没意义
function localPreviewRows(table) {
  return (table.rows || []).slice(0, 30)
}

function toggleLocalSelect(id) {
  localSelected.value = localSelected.value.includes(id)
    ? localSelected.value.filter((item) => item !== id)
    : [...localSelected.value, id]
}

function pickLocalFiles() {
  localFileInput.value?.click()
}

async function addLocalFiles(files) {
  const list = Array.from(files || [])
  if (!list.length || localBusy.value) return
  localBusy.value = true
  try {
    for (const file of list) {
      try {
        const table = await readTable(file)
        const base = file.name.replace(/\.(xlsx|xls|csv|tsv|txt)$/i, '')
        // 同名再传当作更新：删掉旧的，用新解析的结果（和在线接口的覆盖语义一致）
        localTables.value = localTables.value.filter((item) => item.name !== base)
        localTables.value = [
          { ...makeLocalSheet(base, table.columns, table.rows), origin: 'upload' },
          ...localTables.value
        ]
      } catch (err) {
        show(err.message, 'err')
      }
    }
    if (localTables.value.length) {
      localOpen.value = localTables.value[0].id
      persistLocal()
    }
  } finally {
    localBusy.value = false
  }
}

function onLocalFiles(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  addLocalFiles(files)
}

function onLocalDrop(event) {
  localDragging.value = false
  addLocalFiles(event.dataTransfer?.files)
}

// 拖到子元素上时 dragleave 也会冒泡，直接置 false 会让高亮闪一下；只有真的离开整块才复位
function onLocalDragLeave(event) {
  const next = event.relatedTarget
  if (!next || !event.currentTarget?.contains(next)) localDragging.value = false
}

function runOfflineComposer() {
  const text = offlineText.value.trim()
  if (!text || localBusy.value) return
  offlineError.value = ''
  try {
    const picked = localTables.value.filter((item) => localSelected.value.includes(item.id))
    const result = composeLocalSheet(text, picked)
    if (result.kind === 'merge') {
      localTables.value = localTables.value.filter((item) => !localSelected.value.includes(item.id))
      localSelected.value = []
    }
    localTables.value = [result.sheet, ...localTables.value]
    localOpen.value = result.sheet.id
    offlineText.value = ''
    persistLocal()
    show(result.kind === 'merge'
      ? `已按${result.key ? `「${result.key}」` : '列名并排'}合并成「${result.sheet.name}」，共 ${result.sheet.rows.length} 行`
      : `已在本地建好「${result.sheet.name}」，列名共 ${result.sheet.columns.length} 个`)
  } catch (err) {
    offlineError.value = err.message
  }
}

function fillOffline(text) {
  offlineText.value = text
}

function removeLocal(table) {
  if (!window.confirm(`确定删除本地的「${table.name}」？只删浏览器里这份，不影响在线数据。`)) return
  localTables.value = localTables.value.filter((item) => item.id !== table.id)
  localSelected.value = localSelected.value.filter((id) => id !== table.id)
  if (localOpen.value === table.id) localOpen.value = localTables.value[0] ? localTables.value[0].id : ''
  persistLocal()
}

function downloadLocal(table) {
  downloadCsv(table.name, table.columns, table.rows)
  show(`已导出「${table.name}」为 CSV`)
}

watch(offlineMode, (off) => {
  if (off) {
    loadLocal()
    // 离线时不请求后端，骨架屏和错误提示都不该留在屏幕上
    loading.value = false
    clearNotice()
  } else {
    loadTables(true)
  }
})

// 每张表下方的改表输入框，填字后要能自动聚焦
const draftInputs = {}
function setDraftInput(id, el) {
  if (el) draftInputs[id] = el
  else delete draftInputs[id]
}

function fillComposer(text) {
  composerText.value = text
  composerError.value = ''
  nextTick(() => {
    if (composerInput.value) {
      composerInput.value.focus()
      composerInput.value.setSelectionRange(text.length, text.length)
    }
  })
}

// 左栏勾选两张以上后点「合并选中」：展开一句话输入框，把说法填好并把光标放到末尾，
function startMergeSelected() {
  if (selected.value.length < 2) return
  composerOpen.value = true
  fillComposer(`把选中的 ${selected.value.length} 张表合并`)
  nextTick(() => {
    if (composerInput.value) composerInput.value.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
}

function fillDraft(id, text) {
  drafts[id] = text
  revErrors[id] = ''
  nextTick(() => {
    const input = draftInputs[id]
    if (input) {
      input.focus()
      input.setSelectionRange(text.length, text.length)
    }
  })
}

// ==================== 弹窗（模板 / 空白表 / 加列 / 重命名 / 删除） ====================
const dialog = reactive({
  open: false,
  kind: '',
  sheetId: '',
  title: '',
  hint: '',
  name: '',
  columns: '',
  busy: false,
  error: ''
})
const dialogConfirmText = computed(() => {
  if (dialog.kind === 'delete') return '删除'
  return dialog.busy ? '处理中…' : '确定'
})

const originText = (origin) =>
  ({ upload: '上传', template: '模板', nl: '新建', merge: '融合', task: '任务归档' })[origin] || '表格'

function fmtTime(value) {
  const text = String(value || '')
  return text.length >= 16 ? text.slice(5, 16) : text
}

// ==================== 清单 ====================

async function loadTables(silent = false) {
  if (!silent) loading.value = tables.value.length === 0
  listError.value = ''
  try {
    tables.value = await api.sheets({ q: keyword.value.trim() })
    // 这一次取到了，就把上一次留下的提示收掉
    clearNotice()
    for (const id of opened.value) {
      if (!details[id]) await loadDetail(id)
    }
  } catch (err) {
    listError.value = isNetworkError(err) ? '暂时连不上服务，请稍后刷新重试' : err.message
    // 报错统一交给顶栏下方那条全局提示，正文里不再单独摆一条红带
    showNotice(listError.value, () => loadTables())
  } finally {
    loading.value = false
  }
}

async function loadDetail(id) {
  try {
    details[id] = await api.sheet(id)
  } catch (err) {
    show(err.message, 'err')
  }
}

function toggleOpen(id) {
  if (opened.value.includes(id)) {
    closeCard(id)
  } else {
    opened.value.push(id)
    if (!details[id]) loadDetail(id)
  }
}

function closeCard(id) {
  opened.value = opened.value.filter((item) => item !== id)
}

function openAll() {
  opened.value = tables.value.map((item) => item.id)
  opened.value.forEach((id) => {
    if (!details[id]) loadDetail(id)
  })
}

function closeAll() {
  opened.value = []
}

function toggleSelect(id) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((item) => item !== id)
    : [...selected.value, id]
}

// 顶部那个复选框就是全选：点一次勾上全部，再点一次全部取消（主流列表的常规做法）
const allSelected = computed(
  () => tables.value.length > 0 && selected.value.length === tables.value.length
)

function toggleSelectAll() {
  selected.value = allSelected.value ? [] : tables.value.map((item) => item.id)
}

// 「删除选中」：把左栏勾选的表连同文件一起删掉。
// 只删勾选的那些，不碰没勾的；已展开的卡片同步收起，清单立刻刷新
const selectedBusy = ref(false)

async function removeSelected() {
  const ids = [...selected.value]
  if (!ids.length || selectedBusy.value) return
  const names = tables.value.filter((item) => ids.includes(item.id)).map((item) => item.name)
  const tip = ids.length === 1
    ? `确定删除「${names[0]}」？文件会一起删掉，删除后不能恢复。`
    : `确定删除选中的 ${ids.length} 张表？\n${names.slice(0, 5).join('、')}${names.length > 5 ? ' 等' : ''}\n文件会一起删掉，删除后不能恢复。`
  if (!window.confirm(tip)) return
  selectedBusy.value = true
  try {
    let count = ids.length
    try {
      const res = await api.deleteSheets(ids)
      count = res?.deleted ?? ids.length
    } catch (e) {
      // 后端还没换成带批量删除的版本时，退回逐张删，功能照样可用
      count = 0
      for (const id of ids) {
        await api.deleteSheet(id)
        count += 1
      }
    }
    ids.forEach((id) => closeCard(id))
    selected.value = []
    await loadTables(true)
    show(`已删除 ${count} 张表`)
  } catch (err) {
    show(err.message || '删除失败', 'err')
  } finally {
    selectedBusy.value = false
  }
}

// ==================== 上传 ====================

// 拖进来时高亮边框，和新建任务页的上传区保持一致的手感
const dragging = ref(false)

function pickFiles() {
  if (uploadBusy.value) return
  fileInput.value && fileInput.value.click()
}

function onFiles(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  uploadFiles(files)
}

function onDrop(event) {
  dragging.value = false
  const files = Array.from((event.dataTransfer && event.dataTransfer.files) || [])
  uploadFiles(files)
}

// 拖到子元素上也会触发 dragleave，直接置 false 会让高亮闪烁；
// 只有真正离开这块区域才收起，避免高亮卡住不消失
function onDragLeave(event) {
  const to = event.relatedTarget
  if (to && event.currentTarget.contains(to)) return
  dragging.value = false
}

async function uploadFiles(files) {
  const picked = files.filter((file) => /\.(xlsx|xls)$/i.test(file.name))
  if (!picked.length) {
    if (files.length) show('只支持 .xlsx / .xls 表格', 'err')
    return
  }
  if (uploadBusy.value) return
  uploadBusy.value = true
  listError.value = ''
  try {
    const created = await api.uploadSheets(picked)
    tables.value = await api.sheets({ q: keyword.value.trim() })
    for (const sheet of created) {
      details[sheet.id] = await api.sheet(sheet.id)
      if (!opened.value.includes(sheet.id)) opened.value.push(sheet.id)
    }
    show(`已导入 ${created.length} 张表`)
  } catch (err) {
    listError.value = err.message
  } finally {
    uploadBusy.value = false
  }
}

// ==================== 一句话：建表 / 融合 / 改表 ====================

async function runComposer(textOverride) {
  const text = String(textOverride == null ? composerText.value : textOverride).trim()
  if (!text || composerBusy.value) return
  composerBusy.value = true
  composerError.value = ''
  try {
    // 左边的清单可能正被搜索词过滤着，句子里点名的表要从全量里找
    const all = await api.sheets()
    const mentioned = all
      .filter((item) => text.includes(item.name))
      .sort((a, b) => b.name.length - a.name.length)
    const wantsMerge = /合并|融合|关联|拼起来|汇总到/.test(text)

    // 句子里点名了某张表，就对着这张表改，不用先在左边选中
    if (!wantsMerge && mentioned.length === 1 && !/新建|建一张|创建|做一张/.test(text)) {
      await runRevise(mentioned[0].id, text)
      composerText.value = ''
      composerOpen.value = false
      return
    }

    let ids = []
    if (wantsMerge) {
      ids = mentioned.length >= 2 ? mentioned.map((item) => item.id) : [...selected.value]
      if (ids.length < 2) {
        throw new Error('要融合的话，先在左边勾选两张以上的表，或者直接说出两张表的名字')
      }
    } else if (mentioned.length >= 2) {
      ids = mentioned.map((item) => item.id)
    } else if (selected.value.length >= 2 && !/新建|建一张|创建|做一张/.test(text)) {
      ids = [...selected.value]
    }

    const sheet = await api.sheetCompose(text, ids)
    details[sheet.id] = sheet
    if (!opened.value.includes(sheet.id)) opened.value.push(sheet.id)
    // 融合完就取消勾选，免得下次又说「选中的两张表」时困惑
    if (wantsMerge) selected.value = []
    // 新表不在当前搜索范围里时清掉筛选，不然列表里看不到它
    if (keyword.value) keyword.value = ''
    await loadTables(true)
    composerText.value = ''
    // 生成完这张表已经展开了，统一入口收回去，页尾只留卡片里的输入框
    composerOpen.value = false
    show(`已生成「${sheet.name}」，共 ${sheet.row_count} 行`)
  } catch (err) {
    composerError.value = err.message
  } finally {
    composerBusy.value = false
  }
}

async function runRevise(id, textOverride) {
  const text = String(textOverride == null ? drafts[id] || '' : textOverride).trim()
  if (!text || revising[id]) return
  revising[id] = true
  revErrors[id] = ''
  try {
    const detail = await api.reviseSheet(id, text)
    details[id] = detail
    // 从上方「一句话」点名改表时，这张表可能还收着，直接展开给人看结果
    if (!opened.value.includes(id)) opened.value.push(id)
    refreshing[id] = (refreshing[id] || 0) + 1
    drafts[id] = ''
    if (keyword.value) keyword.value = ''
    await loadTables(true)
    const changed = detail.revision ? detail.revision.changed_rows : 0
    show(`已按这句话改完，影响 ${changed} 行`)
  } catch (err) {
    revErrors[id] = err.message
  } finally {
    revising[id] = false
  }
}

// 按这张表的列给两三条现成的说法，点了就执行，省得用户猜能怎么说。
function editSamples(detail) {
  if (!detail) return []
  const columns = detail.columns || []
  if (!columns.length) return []
  const row = Math.min(2, Math.max(1, detail.row_count || 1))
  const numeric = columns.find((name) => /天数|时长|数量|金额|小时|人数|次数|分数|单价|工资|合计/.test(name))
  const items = []
  if (numeric) {
    items.push(`第 ${row} 行的「${numeric}」改成 20`)
    items.push(`按「${numeric}」降序排序`)
  } else {
    const last = columns[columns.length - 1]
    if (last) items.push(`第 ${row} 行的「${last}」改成 已核对`)
    items.push(`按「${columns[0]}」升序排序`)
  }
  const extra = columns.includes('复核人') ? '审核状态' : '复核人'
  items.push(`新增一列「${extra}」，填待复核`)
  return items.slice(0, 3)
}

// ==================== 单表操作 ====================

async function addRow(id) {
  if (cardBusy[id]) return
  cardBusy[id] = true
  try {
    details[id] = await api.addSheetRow(id)
    lastSignal[id] = (lastSignal[id] || 0) + 1
    await loadTables(true)
    show('已加一行，双击格子就能填')
  } catch (err) {
    show(err.message, 'err')
  } finally {
    cardBusy[id] = false
  }
}

async function onDeleteRow(id, row) {
  try {
    details[id] = await api.deleteSheetRow(id, row)
    refreshing[id] = (refreshing[id] || 0) + 1
    await loadTables(true)
    show(`已删除第 ${row} 行，可以撤销`)
  } catch (err) {
    show(err.message, 'err')
  }
}

async function onCellEdited(id) {
  // 改格子后端已经落盘，这里只刷新表头里的行数、修改记录和日志
  try {
    details[id] = await api.sheet(id)
    await loadTables(true)
  } catch (err) {
    show(err.message, 'err')
  }
}

async function undo(id) {
  if (cardBusy[id]) return
  cardBusy[id] = true
  try {
    details[id] = await api.undoSheet(id)
    refreshing[id] = (refreshing[id] || 0) + 1
    await loadTables(true)
    show('已撤销上一步修改')
  } catch (err) {
    show(err.message, 'err')
  } finally {
    cardBusy[id] = false
  }
}

// ==================== 弹窗操作 ====================

function openDialog(kind, payload = {}) {
  dialog.open = true
  dialog.kind = kind
  dialog.sheetId = payload.sheetId || ''
  dialog.name = payload.name || ''
  dialog.columns = ''
  dialog.busy = false
  dialog.error = ''
  dialog.title =
    { blank: '新建空白表', column: '新增一列', rename: '重命名表格', delete: '删除表格' }[kind] || ''
  dialog.hint =
    {
      blank: '列名用顿号或逗号隔开，至少两列。',
      column: '新列会加在最后一列，空值可以之后再填。',
      rename: '只改表名，表格内容不变。',
      delete: '文件会一起删掉，删除后不能恢复。'
    }[kind] || ''
}

async function submitDialog() {
  if (dialog.busy) return
  dialog.busy = true
  dialog.error = ''
  try {
    if (dialog.kind === 'blank') {
      const columns = dialog.columns
        .split(/[、,，;；\s]+/)
        .map((item) => item.trim())
        .filter(Boolean)
      if (columns.length < 2) throw new Error('至少写两列，比如：姓名、部门、岗位')
      const sheet = await api.sheetBlank(dialog.name.trim(), columns)
      details[sheet.id] = sheet
      if (!opened.value.includes(sheet.id)) opened.value.push(sheet.id)
      show(`已建表「${sheet.name}」`)
    } else if (dialog.kind === 'column') {
      const name = dialog.name.trim()
      if (!name) throw new Error('列名不能为空')
      details[dialog.sheetId] = await api.addSheetColumn(dialog.sheetId, name)
      refreshing[dialog.sheetId] = (refreshing[dialog.sheetId] || 0) + 1
      show(`已新增列「${name}」`)
    } else if (dialog.kind === 'rename') {
      const name = dialog.name.trim()
      if (!name) throw new Error('表名不能为空')
      details[dialog.sheetId] = await api.renameSheet(dialog.sheetId, name)
      show('已重命名')
    } else if (dialog.kind === 'delete') {
      await api.deleteSheet(dialog.sheetId)
      delete details[dialog.sheetId]
      closeCard(dialog.sheetId)
      selected.value = selected.value.filter((id) => id !== dialog.sheetId)
      show('已删除表格')
    }
    dialog.open = false
    // 删除后也要重新拉清单：左侧栏、计数和空状态都跟着表格数量走，
    // 不刷新会留着已被删掉的那一行，看起来“删了还在”
    await loadTables(true)
  } catch (err) {
    dialog.error = err.message
  } finally {
    dialog.busy = false
  }
}

// ==================== 生命周期 ====================

onMounted(async () => {
  mountTopbar()
  // 断网时不去请求清单：接口必然失败，只会留下一条错误提示和空骨架
  if (offlineMode.value) {
    loading.value = false
    loadLocal()
    return
  }
  await loadTables()
  const target = route.query.id
  if (target) {
    const id = String(target)
    if (!opened.value.includes(id)) opened.value.push(id)
    if (!details[id]) await loadDetail(id)
  } else if (tables.value.length) {
    opened.value = [tables.value[0].id]
    await loadDetail(tables.value[0].id)
  }
})

// 从别的页面切回来：顶栏复位 + 静默刷新（不触发骨架、不收起已展开的表格）。
// 清单和已展开表格的详情都补一次，避免别处增删表后这里还显示旧的行数或已删除的表。
onActivated(async () => {
  mountTopbar()
  if (loading.value || offlineMode.value) return
  await loadTables(true)
  for (const id of opened.value.slice()) {
    if (!tables.value.some((item) => item.id === id)) {
      closeCard(id)
      continue
    }
    await loadDetail(id)
    refreshing[id] = (refreshing[id] || 0) + 1
  }
})

function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadTables(true), 220)
}

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <div class="sw">
    <input
      ref="fileInput"
      type="file"
      class="hidden-input"
      accept=".xlsx,.xls"
      multiple
      @change="onFiles"
    />

    <!-- 离线：后端连不上时改用本地表格。结构和在线页一致（左清单 + 右内容）， -->
    <div v-if="offlineMode" class="sw-layout" :class="{ 'is-solo': !localTables.length }">
      <input
        ref="localFileInput"
        type="file"
        class="hidden-input"
        accept=".xlsx,.xls,.csv,.tsv,.txt"
        multiple
        @change="onLocalFiles"
      />

      <aside v-if="localTables.length" class="sw-side">
        <div class="sw-side-head">
          <b>本地表格</b>
          <span class="sw-count">{{ localTables.length }}</span>
          <span class="offline-chip" :title="OFFLINE_HINT"><i />离线模式</span>
        </div>

        <div
          class="sw-drop sw-drop-sm"
          :class="{ 'is-over': localDragging, 'is-busy': localBusy }"
          role="button"
          tabindex="0"
          @click="pickLocalFiles"
          @keydown.enter.prevent="pickLocalFiles"
          @dragenter.prevent="localDragging = true"
          @dragover.prevent="localDragging = true"
          @dragleave.prevent="onLocalDragLeave"
          @dragend="localDragging = false"
          @drop.prevent="onLocalDrop"
        >
          <span class="sw-drop-icon"><Icon name="upload" :size="16" /></span>
          <b>{{ localBusy ? '正在读表…' : '导入表格' }}</b>
          <span class="sw-drop-sub">拖入或点击选择 · .xlsx / .xls / .csv</span>
        </div>

        <ul class="sw-list">
          <li
            v-for="item in localTables"
            :key="item.id"
            class="sw-list-item"
            :class="{ on: localOpen === item.id }"
          >
            <label class="sw-check" title="勾选后可以合并" @click.stop>
              <input
                type="checkbox"
                :checked="localSelected.includes(item.id)"
                @change="toggleLocalSelect(item.id)"
              />
            </label>
            <button class="sw-list-main" @click="localOpen = item.id">
              <span class="sw-list-name">{{ item.name }}</span>
              <span class="sw-list-meta">{{ localMeta(item) }}</span>
            </button>
            <span class="tag tag-neutral sw-origin">本地</span>
          </li>
        </ul>

        <div class="sw-side-foot">
          <template v-if="localSelected.length">
            <span class="sw-foot-count">已选 {{ localSelected.length }} 张</span>
            <span class="sw-muted">在下方输入「合并选中的表」</span>
          </template>
          <template v-else>勾选两张表可以按工号合并</template>
        </div>
      </aside>

      <section class="sw-main">
        <!-- 空状态整块就是拖拽区：没有表格时左栏的上传区还没出现， -->
        <div
          v-if="!localTables.length"
          class="panel sw-empty"
          :class="{ 'is-over': localDragging }"
          @dragenter.prevent="localDragging = true"
          @dragover.prevent="localDragging = true"
          @dragleave.prevent="onLocalDragLeave"
          @dragend="localDragging = false"
          @drop.prevent="onLocalDrop"
        >
          <span class="sw-empty-icon"><Icon name="upload" :size="24" /></span>
          <b>{{ localDragging ? '松手就能开始解析' : '把表格拖到这里' }}</b>
          <p>
            当前离线，拖入或选择 Excel / CSV 后在浏览器里解析，不会上传；
            建表、合并结果可随时导出 CSV。
          </p>
          <button class="btn" @click="pickLocalFiles">
            <Icon name="upload" :size="13" /> 选择文件
          </button>
          <span class="sw-empty-hint">支持 .xlsx / .xls / .csv / .tsv / .txt，可一次拖入多个</span>
        </div>

        <article v-else-if="openLocalTable" class="sw-card">
          <header class="sw-card-head">
            <div class="sw-card-title">
              <Icon name="table" :size="15" />
              <b>{{ openLocalTable.name }}</b>
              <span class="tag tag-neutral">{{ openLocalTable.origin === 'upload' ? '本地导入' : '本地生成' }}</span>
              <span class="sw-card-meta">{{ localMeta(openLocalTable) }}</span>
            </div>
            <div class="sw-card-actions">
              <button class="btn btn-sm" @click="downloadLocal(openLocalTable)">
                <Icon name="download" :size="13" />下载 CSV
              </button>
              <button class="icon-btn" title="删除这张本地表" @click="removeLocal(openLocalTable)">
                <Icon name="trash" :size="14" />
              </button>
            </div>
          </header>

          <p class="sw-card-sub">
            <span>离线只预览前 30 行；下载 CSV 会导出这张表的全部 {{ openLocalTable.rows.length }} 行。</span>
          </p>

          <div class="sw-local-table">
            <table>
              <thead>
                <tr>
                  <th>{{ openLocalTable.columns.length }} 列</th>
                  <th v-for="column in openLocalTable.columns" :key="column">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in localPreviewRows(openLocalTable)" :key="index">
                  <td class="sw-local-no">{{ index + 1 }}</td>
                  <td v-for="(cell, i) in row" :key="i">{{ cell }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <div v-else class="panel sw-empty">
          <span class="sw-empty-icon"><Icon name="table" :size="24" /></span>
          <b>从左边点开一张表</b>
          <p>看数据、导出 CSV，或者用下面那句话新建 / 合并本地表。</p>
        </div>

        <!-- 本地一句话：只认「新建…列：…」和「合并选中的表（可指定按哪一列）」两种规则 -->
        <div class="sw-compose">
          <div class="sw-compose-head">
            <b>一句话（本地）</b>
            <span>离线不调用大模型，只认「新建…列：…」和「把选中的表按某一列合并」两种说法，按哪一列都行</span>
          </div>
          <div class="sw-compose-row">
            <input
              v-model="offlineText"
              class="sw-compose-input"
              type="text"
              placeholder="例如：新建一张表，列：检查日期、车间、负责人；或把选中的表按车间合并"
              @keydown.enter="runOfflineComposer"
            />
            <button class="btn btn-primary" :disabled="!offlineText.trim()" @click="runOfflineComposer">
              生成
            </button>
          </div>
          <p v-if="localJoinHint" class="sw-compose-hint">{{ localJoinHint }}</p>
          <div class="sw-samples">
            <span>示例</span>
            <button
              v-for="text in offlineSamples"
              :key="text"
              class="sw-chip"
              title="点一下填入输入框，不会直接发送"
              @click="fillOffline(text)"
            >
              {{ text }}
            </button>
          </div>
          <p v-if="offlineError" class="sw-err">{{ offlineError }}</p>
        </div>
      </section>
    </div>

    <div v-if="!offlineMode" class="sw-layout" :class="{ 'is-solo': !tables.length }">
      <!-- 首次加载时不要先摆一个空清单壳子，否则屏幕上是两块空面板并排闪一下 -->
      <aside v-if="tables.length" class="sw-side">
        <div class="sw-side-head">
          <b>我的表格</b>
          <span class="sw-count">{{ tables.length }}</span>
          <button class="sw-icon" title="刷新" @click="loadTables(true)">
            <Icon name="refresh" :size="14" />
          </button>
        </div>

        <!-- 左栏只在确实有表格时出现，搜索框随清单一起展示 -->
        <div class="sw-side-search">
          <Icon name="search" :size="14" />
          <input
            v-model="keyword"
            type="search"
            placeholder="按表名、列名搜索"
            @input="onKeywordInput"
            @keydown.esc="keyword = ''"
          />
          <button v-if="keyword" class="sw-clear" title="清空" @click="keyword = ''; loadTables(true)">
            <Icon name="close" :size="12" />
          </button>
        </div>

        <div
          v-if="tables.length"
          class="sw-drop sw-drop-sm"
          :class="{ 'is-over': dragging, 'is-busy': uploadBusy }"
          role="button"
          tabindex="0"
          @click="pickFiles"
          @keydown.enter.prevent="pickFiles"
          @dragenter.prevent="dragging = true"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="onDragLeave"
          @dragend="dragging = false"
          @drop.prevent="onDrop"
        >
          <span class="sw-drop-icon"><Icon name="upload" :size="16" /></span>
          <b>{{ uploadBusy ? '正在导入…' : '上传表格' }}</b>
          <span class="sw-drop-sub">拖入或点击选择，可多选</span>
        </div>

        <!-- 全选就一个复选框，和列表里每行的勾选框对齐；再点一次就全部取消 -->
        <div class="sw-list-all">
          <input
            type="checkbox"
            aria-label="全选"
            :checked="allSelected"
            :indeterminate.prop="selected.length > 0 && !allSelected"
            @change="toggleSelectAll"
          />
        </div>

        <ul class="sw-list">
          <li
            v-for="item in tables"
            :key="item.id"
            class="sw-list-item"
            :class="{ on: opened.includes(item.id) }"
          >
            <label class="sw-check" title="勾选后可以合并" @click.stop>
              <input
                type="checkbox"
                :checked="selected.includes(item.id)"
                @change="toggleSelect(item.id)"
              />
            </label>
            <button class="sw-list-main" @click="toggleOpen(item.id)">
              <span class="sw-list-name">{{ item.name }}</span>
              <span class="sw-list-meta">
                {{ item.row_count }} 行 · {{ item.column_count }} 列 · {{ fmtTime(item.updated_at) }}
              </span>
            </button>
            <span class="tag tag-neutral sw-origin">{{ originText(item.origin) }}</span>
          </li>
        </ul>

        <div v-if="tables.length" class="sw-side-foot">
          <template v-if="selected.length">
            <span class="sw-foot-count">已选 {{ selected.length }} 张</span>
            <button
              v-if="selected.length >= 2"
              type="button"
              class="btn-text sw-foot-merge"
              @click="startMergeSelected"
            >
              合并
            </button>
            <button
              type="button"
              class="btn-text btn-text-danger"
              :disabled="selectedBusy"
              @click.stop="removeSelected"
            >
              {{ selectedBusy ? '删除中…' : '删除' }}
            </button>
          </template>
          <template v-else>勾选后可以合并或删除</template>
        </div>
      </aside>

      <section class="sw-main">
        <!-- 建表入口已收到顶栏；这里只留展开/收起，没有表格时不占一行 -->
        <div v-if="tables.length" class="sw-tools">
          <div class="sw-tools-left">
            <button class="btn-text" @click="openAll">全部展开</button>
            <button v-if="opened.length" class="btn-text" @click="closeAll">全部收起</button>
          </div>
        </div>

        <!-- 报错不在这里占位，统一显示在顶栏下方那条全局提示里 -->

        <!-- 首次进入用骨架屏占位：和空状态同宽同高，数据回来后不会整页跳一下 -->
        <div v-if="loading && !tables.length" class="panel sw-skeleton" aria-busy="true">
          <div class="sw-sk-rows">
            <span class="sw-sk-bar" style="width: 42%"></span>
            <span class="sw-sk-bar" style="width: 68%"></span>
            <span class="sw-sk-bar" style="width: 100%"></span>
            <span class="sw-sk-bar" style="width: 100%"></span>
            <span class="sw-sk-bar" style="width: 56%"></span>
          </div>
          <p class="sw-sk-text">正在读取表格…</p>
        </div>

        <div v-else-if="!tables.length" class="panel sw-empty">
          <!-- 已经有搜索词时才需要提示“没搜到”；没上传过表时直接给上传区，不再重复标题 -->
          <template v-if="keyword">
            <span class="sw-empty-icon"><Icon name="search" :size="24" /></span>
            <b>没有匹配「{{ keyword }}」的表格</b>
            <p>换个关键词，或者清空搜索查看全部表格。</p>
            <button class="btn" @click="keyword = ''; loadTables()">清空搜索</button>
          </template>
          <template v-else>
            <p class="sw-empty-lead">上传一个或多个 Excel，数据保存在本地，随时可以下载。</p>
            <div
              class="sw-drop sw-drop-lg"
              :class="{ 'is-over': dragging, 'is-busy': uploadBusy }"
              role="button"
              tabindex="0"
              @click="pickFiles"
              @keydown.enter.prevent="pickFiles"
              @dragenter.prevent="dragging = true"
              @dragover.prevent="dragging = true"
              @dragleave.prevent="onDragLeave"
              @dragend="dragging = false"
              @drop.prevent="onDrop"
            >
              <span class="sw-drop-icon"><Icon name="upload" :size="28" /></span>
              <b>{{ uploadBusy ? '正在读表…' : '上传 Excel' }}</b>
              <span class="sw-drop-sub">拖入或点击选择，可多选 · .xlsx / .xls</span>
            </div>
          </template>
        </div>

        <div v-else-if="!opened.length" class="panel sw-empty">
          <span class="sw-empty-icon"><Icon name="table" :size="24" /></span>
          <b>从左边点开一张表</b>
          <p>点表名展开后，就能像 Excel 一样看数据、双击改格子、用一句话改表。</p>
        </div>

        <template v-for="id in opened" :key="id">
        <article v-if="details[id]" class="sw-card">
          <header class="sw-card-head">
            <div class="sw-card-title">
              <Icon name="table" :size="15" />
              <b>{{ details[id].name }}</b>
              <span class="tag tag-neutral">{{ originText(details[id].origin) }}</span>
              <span class="sw-card-meta">
                {{ details[id].row_count }} 行 · {{ details[id].column_count }} 列 · 更新于
                {{ details[id].updated_at }}
              </span>
            </div>
            <div class="sw-card-actions">
              <button class="btn btn-sm" @click="addRow(id)">加行</button>
              <button class="btn btn-sm" @click="openDialog('column', { sheetId: id })">加列</button>
              <button
                class="btn btn-sm"
                :disabled="!details[id].undo_available || cardBusy[id]"
                @click="undo(id)"
              >
                <Icon name="left" :size="12" />撤销
              </button>
              <a class="btn btn-sm" :href="api.sheetDownloadUrl(id)" download>
                <Icon name="download" :size="13" />下载
              </a>
              <button
                class="btn btn-sm"
                @click="openDialog('rename', { sheetId: id, name: details[id].name })"
              >
                重命名
              </button>
              <button
                class="icon-btn"
                title="删除表格"
                @click="openDialog('delete', { sheetId: id, name: details[id].name })"
              >
                <Icon name="trash" :size="14" />
              </button>
              <button class="btn-text" @click="closeCard(id)">收起</button>
            </div>
          </header>

          <div v-if="details[id].note || details[id].last_summary" class="sw-card-sub">
            <span v-if="details[id].note">{{ details[id].note }}</span>
            <span v-if="details[id].last_summary">最近一次修改：{{ details[id].last_summary }}</span>
          </div>

          <ResultTable
            :sheet-id="id"
            :columns="details[id].columns"
            :refresh-key="refreshing[id] || 0"
            :show-last="lastSignal[id] || 0"
            editable
            @edited="onCellEdited(id)"
            @deleted-row="onDeleteRow(id, $event)"
          />

          <div class="sw-card-edit">
            <input
              :ref="(el) => setDraftInput(id, el)"
              v-model="drafts[id]"
              type="text"
              placeholder="对这张表说一句，例如：第 2 行的缺勤天数改成 1 / 按加班时长降序排序 / 姓名为张明的行删掉"
              @keydown.enter="runRevise(id)"
            />
            <button
              class="btn btn-primary btn-sm"
              :disabled="revising[id] || !(drafts[id] || '').trim()"
              @click="runRevise(id)"
            >
              {{ revising[id] ? '改写中…' : '改这张表' }}
            </button>
          </div>
          <div class="sw-samples sw-card-samples">
            <span>试试</span>
            <button
              v-for="text in editSamples(details[id])"
              :key="text"
              class="sw-chip"
              :disabled="revising[id]"
              title="点一下填入输入框，不会直接发送"
              @click="fillDraft(id, text)"
            >
              {{ text }}
            </button>
          </div>
          <p v-if="revErrors[id]" class="sw-err">{{ revErrors[id] }}</p>

          <div class="sw-card-foot">
            <div v-if="details[id].revisions && details[id].revisions.length" class="sw-revs">
              <span class="sw-foot-label">修改记录</span>
              <span
                v-for="(rev, index) in details[id].revisions.slice(0, 4)"
                :key="index"
                class="sw-rev"
                :title="rev.instruction"
              >
                {{ rev.summary }}
              </span>
              <span v-if="details[id].revisions.length > 4" class="sw-muted">
                还有 {{ details[id].revisions.length - 4 }} 条
              </span>
            </div>

            <details class="sw-logs">
              <summary>操作日志 {{ details[id].logs ? details[id].logs.length : 0 }}</summary>
              <ul>
                <li v-for="(log, index) in details[id].logs || []" :key="index" :class="log.level">
                  <span class="sw-log-time">{{ String(log.time || '').slice(11, 16) }}</span>
                  <b>{{ log.action }}</b>
                  <span class="sw-log-detail">{{ log.detail }}</span>
                </li>
              </ul>
            </details>
          </div>
        </article>
        </template>

        <!-- 展开表格时卡片里已经有「改这张表」，这条统一入口先收起来，避免两个输入框上下叠着 -->
        <div v-if="!showComposer && opened.length" class="sw-compose-trigger">
          <span>要新建或融合表格？</span>
          <button class="btn-text" @click="composerOpen = true">一句话新建 / 融合</button>
        </div>

        <div v-if="showComposer" class="sw-compose">
          <div class="sw-compose-head">
            <b>一句话</b>
            <span>新建就说清列名；融合先勾选表格并说明按哪一列；示例只把说法填进输入框，确认后点「生成」发送</span>
            <button v-if="opened.length" class="btn-text sw-compose-close" @click="composerOpen = false">
              收起
            </button>
          </div>
          <div class="sw-compose-row">
            <input
              ref="composerInput"
              v-model="composerText"
              class="sw-compose-input"
              type="text"
              placeholder="例如：新建一张表，列：检查日期、车间、负责人、检查结果；或把选中的两张表按工号合并"
              @keydown.enter="runComposer()"
            />
            <button
              class="btn btn-primary"
              :disabled="composerBusy || !composerText.trim()"
              @click="runComposer()"
            >
              {{ composerBusy ? '处理中…' : '生成' }}
            </button>
          </div>
          <div class="sw-samples">
            <span>示例</span>
            <button
              v-for="text in composerSamples"
              :key="text"
              class="sw-chip"
              :disabled="composerBusy"
              title="点一下填入输入框，不会直接发送"
              @click="fillComposer(text)"
            >
              {{ text }}
            </button>
          </div>
          <p v-if="composerError" class="sw-err">{{ composerError }}</p>
        </div>
      </section>
    </div>

    <div v-if="dialog.open" class="sw-modal" @click.self="dialog.open = false">
      <div class="sw-modal-card">
        <header class="sw-modal-head">
          <b>{{ dialog.title }}</b>
          <button class="sw-icon" title="关闭" @click="dialog.open = false">
            <Icon name="close" :size="14" />
          </button>
        </header>

        <p class="sw-modal-hint">{{ dialog.hint }}</p>

        <div v-if="dialog.kind === 'delete'" class="sw-modal-body">
          确定删除「{{ dialog.name }}」？文件会一起删掉。
        </div>
        <label v-if="dialog.kind !== 'delete'" class="field">
          <span class="field-label">表名</span>
          <input v-model="dialog.name" class="input" placeholder="例如：4 月车间检查记录" />
        </label>
        <label v-if="dialog.kind === 'blank'" class="field">
          <span class="field-label">列名</span>
          <input
            v-model="dialog.columns"
            class="input"
            placeholder="用顿号隔开，例如：日期、车间、负责人、检查结果"
          />
        </label>

        <p v-if="dialog.error" class="sw-err">{{ dialog.error }}</p>

        <footer class="sw-modal-foot">
          <button class="btn" @click="dialog.open = false">取消</button>
          <button
            class="btn"
            :class="dialog.kind === 'delete' ? 'btn-danger-solid' : 'btn-primary'"
            :disabled="dialog.busy"
            @click="submitDialog"
          >
            {{ dialogConfirmText }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sw {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
}

.sw-layout {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

/* 还没有表时左边清单没有内容，直接让右边占满，只留一个上传入口 */
.sw-layout.is-solo {
  grid-template-columns: minmax(0, 1fr);
}

/* ---------- 左侧清单 ---------- */
.sw-side {
  position: sticky;
  top: 96px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 120px);
  padding: 14px 12px 10px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
}

.sw-side-head {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 0 2px 10px;
}

.sw-side-head b {
  font-size: 13px;
  font-weight: 600;
}

.sw-count {
  color: var(--muted);
  font-size: 11px;
}

.sw-icon {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  margin-left: auto;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: none;
  color: var(--muted);
}

.sw-icon:hover {
  background: #f2f3f5;
  color: var(--ink);
}

.sw-side-search {
  display: flex;
  gap: 6px;
  align-items: center;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  color: var(--muted);
}

.sw-side-search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.sw-side-search input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-size: 12px;
}

.sw-side-search input::-webkit-search-cancel-button {
  display: none;
}

.sw-clear {
  display: inline-flex;
  padding: 0;
  border: none;
  background: none;
  color: var(--muted);
}

/* 上传区：和新建任务页同一套拖拽区样式 */
.sw-drop {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  padding: 14px 12px;
  border: 1px dashed #dcdfe6;
  border-radius: 10px;
  background: #fff;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

/* hover / 拖入只加深一圈虚线，不整块铺蓝底、不加彩色投影 */
.sw-drop:hover,
.sw-drop.is-over {
  border-color: #a8b3c2;
  background: #fafafa;
}

.sw-drop.is-busy {
  cursor: progress;
  opacity: 0.75;
}

.sw-drop-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  margin-bottom: 2px;
  border: 1px solid #e4e7ed;
  border-radius: 9px;
  background: #fff;
  color: #606266;
}

.sw-drop b {
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}

.sw-drop-sub {
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
}

.sw-drop-sm {
  margin-top: 8px;
  padding: 12px 10px;
}

.sw-drop-sm .sw-drop-icon {
  width: 30px;
  height: 30px;
}

.sw-drop-lg {
  gap: 8px;
  width: 100%;
  max-width: 760px;
  padding: 64px 28px;
}

.sw-drop-lg .sw-drop-icon {
  width: 52px;
  height: 52px;
  margin-bottom: 6px;
  border-radius: 12px;
}

.sw-drop-lg b {
  font-size: 17px;
  font-weight: 500;
}

.sw-drop-lg .sw-drop-sub {
  font-size: 13px;
}

.sw-list {
  flex: 1;
  min-height: 80px;
  margin: 0 -4px 0;
  padding: 0 4px;
  overflow-y: auto;
  list-style: none;
}

/* 全选行：只放一个复选框，左边距和列表每行的勾选框对齐，右侧留一条细分隔线 */
.sw-list-all {
  display: flex;
  align-items: center;
  margin: 6px -4px 0;
  padding: 4px 4px 5px 10px;
  border-bottom: 1px solid var(--line-soft);
}

.sw-list-all input {
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

.sw-list-item {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 7px 6px;
  border-radius: var(--radius);
  cursor: pointer;
}

.sw-list-item:hover {
  background: #f7f8fa;
}

.sw-list-item.on {
  background: var(--accent-soft);
}

.sw-check {
  display: flex;
  flex: none;
  align-items: center;
}

.sw-check input {
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

.sw-list-main {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: none;
  text-align: left;
}

.sw-list-name {
  display: block;
  overflow: hidden;
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sw-list-item.on .sw-list-name {
  color: var(--accent);
}

.sw-list-meta {
  display: block;
  margin-top: 1px;
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sw-origin {
  flex: none;
  transform: scale(0.92);
}

.sw-list-empty {
  padding: 22px 0;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}

.sw-side-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 9px 2px 2px;
  border-top: 1px solid var(--line-soft);
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}

.sw-foot-count {
  font-variant-numeric: tabular-nums;
}

/* 「合并选中」是这一步真正可点的动作，颜色拉开一点，别再像说明文字 */
.sw-foot-merge {
  margin-left: auto;
  color: var(--accent);
  font-weight: 600;
}

.sw-foot-merge:hover {
  color: var(--accent-hover, var(--accent));
  text-decoration: underline;
}

/* ---------- 主区 ---------- */
.sw-main {
  min-width: 0;
}

.sw-tools {
  display: flex;
  gap: 12px;
  align-items: center;
  /* 建表入口上移后，这里只剩展开/收起，沿用原来的靠右位置 */
  justify-content: flex-end;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.sw-tools-left {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

/* 一句话放在整页最下面：先看数据，想新建或融合时再往下说 */
.sw-compose {
  margin-bottom: 4px;
  padding: 12px 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
}

/* 一张表都没有时，顶部工具条与下方输入卡跟空状态卡片同宽居中，避免上下宽度跳变 */
.sw-layout.is-solo .sw-tools {
  max-width: 1000px;
  margin: 0 auto 12px;
}

.sw-layout.is-solo .sw-compose,
.sw-layout.is-solo .sw-compose-trigger {
  max-width: 1000px;
  margin: 12px auto 0;
}

/* 展开表格时这里只留一行入口，避免和卡片里的输入框重复 */
.sw-compose-trigger {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 4px;
  padding: 9px 14px;
  border: 1px dashed var(--line);
  border-radius: 8px;
  background: #fcfcfd;
  color: var(--muted);
  font-size: 12.5px;
}

.sw-compose-close {
  margin-left: auto;
  font-size: 12px;
}

.sw-compose-head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 8px;
}

.sw-compose-head b {
  font-size: 13px;
  font-weight: 600;
}

.sw-compose-head span {
  color: var(--muted);
  font-size: 11.5px;
}

.sw-compose-row {
  display: flex;
  gap: 8px;
}

.sw-compose-input {
  flex: 1;
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  color: var(--ink);
  font-size: 12.5px;
}

.sw-compose-input:focus {
  border-color: var(--accent);
  outline: none;
  box-shadow: 0 0 0 2px var(--accent-soft);
}

/* 勾选两张以上时列出共有的列，用户照着说「按车间合并」就行 */
.sw-compose-hint {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
}

.sw-samples {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 8px;
  color: var(--muted);
  font-size: 11px;
}

.sw-chip {
  max-width: 100%;
  padding: 2px 8px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fafbfc;
  color: var(--ink-2);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sw-chip:hover:not(:disabled) {
  border-color: #b9cdf5;
  background: var(--accent-soft);
  color: var(--accent);
}

.sw-chip:disabled {
  opacity: 0.5;
}

.sw-err {
  margin: 8px 0 0;
  color: var(--danger);
  font-size: 12px;
  line-height: 1.6;
}

/* 首次加载的骨架屏：宽度、高度与空状态一致，避免数据回来时页面跳动 */
.sw-skeleton {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  max-width: 1000px;
  min-height: 420px;
  margin: 8px auto 0;
  padding: 56px 40px;
  box-shadow: 0 1px 2px rgb(20 30 50 / 3%);
}

.sw-sk-rows {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: min(420px, 100%);
}

.sw-sk-bar {
  display: block;
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef1f6 25%, #f7f9fc 45%, #eef1f6 65%);
  background-size: 400% 100%;
  animation: sw-sk-sweep 1.5s ease-in-out infinite;
}

.sw-sk-text {
  margin: 22px 0 0;
  color: var(--muted);
  font-size: 12px;
}

@keyframes sw-sk-sweep {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

/* ---------- 空状态 ---------- */
/* 空状态收成一列居中的卡片：内容宽度固定，不随窗口拉满，留白更从容 */
.sw-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  max-width: 1000px;
  min-height: 420px;
  margin: 8px auto 0;
  padding: 56px 40px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
  box-shadow: 0 1px 2px rgb(20 30 50 / 3%);
}

.sw-empty-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  margin-bottom: 14px;
  border-radius: 12px;
  background: var(--accent-soft);
  color: var(--accent);
}

.sw-empty b {
  color: var(--ink);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.2px;
}

.sw-empty p {
  max-width: 400px;
  margin: 8px 0 22px;
  font-size: 12.5px;
  line-height: 1.75;
}

/* 空状态本身就是拖拽区：拖到上面时整块描边加深，告诉用户松手就会解析 */
.sw-empty.is-over {
  border-color: #a8b3c2;
  background: #fafafa;
}

.sw-empty-hint {
  margin-top: 10px;
  color: var(--muted);
  font-size: 11.5px;
}

/* 没有标题时的说明行：作为上传区的引导语，位置稍靠下 */
.sw-empty-lead {
  margin: 4px 0 22px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

/* ---------- 表格卡片 ---------- */
.sw-card {
  margin-bottom: 16px;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
}

.sw-card-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.sw-card-title {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  color: var(--ink-2);
}

.sw-card-title b {
  overflow: hidden;
  max-width: 260px;
  color: var(--ink);
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sw-card-meta {
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}

.sw-card-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

.sw-card-actions .icon-btn:hover {
  background: var(--danger-soft);
  color: var(--danger);
}

.sw-card-sub {
  display: flex;
  gap: 14px;
  padding: 7px 14px;
  background: #fafbfc;
  border-bottom: 1px solid var(--line-soft);
  color: var(--muted);
  font-size: 11.5px;
}

.sw-card-edit {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 14px 0;
}

/* 离线预览：只读表格，横向可滚，首列是行号 */
.sw-local-table {
  overflow: auto;
  max-height: 460px;
  border-top: 1px solid var(--line-soft);
}

.sw-local-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.sw-local-table th,
.sw-local-table td {
  padding: 6px 10px;
  border-right: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink-2);
  text-align: left;
  white-space: nowrap;
}

.sw-local-table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f5f8fd;
  color: var(--ink);
  font-weight: 600;
}

.sw-local-table .sw-local-no {
  width: 46px;
  background: #fafbfc;
  color: var(--muted);
  text-align: center;
}

.sw-card-edit input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  color: var(--ink);
  font-size: 12px;
}

.sw-card-edit input:focus {
  border-color: var(--accent);
  outline: none;
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.sw-card-samples {
  padding: 0 14px;
  margin-top: 7px;
}

.sw-card .sw-err {
  padding: 0 14px;
  margin-top: 6px;
}

.sw-card-foot {
  padding: 10px 14px 12px;
  border-top: 1px solid var(--line-soft);
}

.sw-revs {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
  font-size: 11px;
}

.sw-foot-label {
  color: var(--muted);
}

.sw-rev {
  max-width: 260px;
  padding: 2px 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #f2f3f5;
  color: var(--ink-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sw-muted {
  color: var(--muted);
}

.sw-logs summary {
  color: var(--muted);
  font-size: 11.5px;
  cursor: pointer;
}

.sw-logs ul {
  max-height: 180px;
  margin: 8px 0 0;
  padding: 0 0 0 2px;
  overflow-y: auto;
  list-style: none;
}

.sw-logs li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 4px 0;
  border-bottom: 1px dashed var(--line-soft);
  color: var(--ink-2);
  font-size: 11.5px;
}

.sw-logs li:last-child {
  border-bottom: none;
}

.sw-logs b {
  flex: none;
  font-weight: 500;
}

.sw-logs li.warn b {
  color: var(--warn);
}

.sw-logs li.ok b {
  color: var(--ok);
}

.sw-log-time {
  flex: none;
  color: var(--muted);
  font-family: Consolas, "Courier New", monospace;
  font-size: 11px;
}

.sw-log-detail {
  color: var(--muted);
}

/* ---------- 弹窗 ---------- */
.sw-modal {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(15 23 42 / 38%);
}

.sw-modal-card {
  width: 100%;
  max-width: 520px;
  max-height: 86vh;
  padding: 18px 20px 16px;
  overflow-y: auto;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 12px 40px rgb(0 0 0 / 18%);
}

.sw-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.sw-modal-head b {
  font-size: 14px;
  font-weight: 600;
}

.sw-modal-hint {
  margin: 0 0 14px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.sw-modal-body {
  padding: 14px 0;
  color: var(--ink-2);
  font-size: 13px;
}

.sw-modal-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 1080px) {
  .sw-layout {
    grid-template-columns: 1fr;
  }

  .sw-side {
    position: static;
    max-height: none;
  }

  .sw-list {
    max-height: 240px;
  }
}
</style>
