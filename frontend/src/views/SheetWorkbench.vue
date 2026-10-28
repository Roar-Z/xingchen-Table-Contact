<script setup>
/**
 * 表格智作 —— 页面主体
 *
 * 左边是「我的表格」清单，右边一张表一张卡片：
 * 上传 / 模板建表 / 一句话建表或融合 / 双击改格子 / 一句话改表 / 分页查询 / 日志。
 * 每张表始终对应一个 xlsx，下载到的文件和页面上看到的完全一致。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import ResultTable from '../components/ResultTable.vue'
import { api, isNetworkError } from '../api'
import { useToast } from '../toast'
import { clearTopbar, useTopbar } from '../topbar'

const route = useRoute()
const { show } = useToast()

useTopbar({
  title: '表格智作',
  desc: '上传 Excel 直接改，或者用一句话建表、合并；每张表都是一份独立的文件，改完就能下载。'
})

onBeforeUnmount(() => clearTopbar())

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

/* ==================== 一句话 ==================== */
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

/* ==================== 弹窗（模板 / 空白表 / 加列 / 重命名 / 删除） ==================== */
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
  ({ upload: '上传', template: '模板', nl: '新建', merge: '融合' })[origin] || '表格'

function fmtTime(value) {
  const text = String(value || '')
  return text.length >= 16 ? text.slice(5, 16) : text
}

/* ==================== 清单 ==================== */

async function loadTables(silent = false) {
  if (!silent) loading.value = tables.value.length === 0
  listError.value = ''
  try {
    tables.value = await api.sheets({ q: keyword.value.trim() })
    for (const id of opened.value) {
      if (!details[id]) await loadDetail(id)
    }
  } catch (err) {
    listError.value = isNetworkError(err) ? '后端没有连上，先启动 backend/start.cmd' : err.message
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

/* ==================== 上传 ==================== */

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

/* ==================== 一句话：建表 / 融合 / 改表 ==================== */

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

/**
 * 按这张表的列给两三条现成的说法，点了就执行，省得用户猜能怎么说。
 * 有数字列优先演示改数和排序，纯文本表演示改文案和删空行。
 */
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

/* ==================== 单表操作 ==================== */

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

/* ==================== 弹窗操作 ==================== */

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
    if (dialog.kind !== 'delete') await loadTables(true)
  } catch (err) {
    dialog.error = err.message
  } finally {
    dialog.busy = false
  }
}

/* ==================== 生命周期 ==================== */

onMounted(async () => {
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

    <div class="sw-layout" :class="{ 'is-solo': !tables.length && !keyword }">
      <!-- 首次加载时不要先摆一个空清单壳子，否则屏幕上是两块空面板并排闪一下 -->
      <aside v-if="tables.length || keyword" class="sw-side">
        <div class="sw-side-head">
          <b>我的表格</b>
          <span class="sw-count">{{ tables.length }}</span>
          <button class="sw-icon" title="刷新" @click="loadTables(true)">
            <Icon name="refresh" :size="14" />
          </button>
        </div>

        <!-- 一张表都没有时，右侧已经有大块上传入口，左边不再重复；搜索词还在时要留着框好清空 -->
        <div v-if="tables.length || keyword" class="sw-side-search">
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
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <span class="sw-drop-icon"><Icon name="upload" :size="16" /></span>
          <b>{{ uploadBusy ? '正在导入…' : '上传表格' }}</b>
          <span class="sw-drop-sub">拖入或点击选择，可多选</span>
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
          <li v-if="!tables.length && keyword" class="sw-list-empty">没有匹配的表格</li>
        </ul>

        <div v-if="tables.length" class="sw-side-foot">
          <template v-if="selected.length">
            已选 {{ selected.length }} 张
            <button class="btn-text" @click="selected = []">清空</button>
          </template>
          <template v-else>勾选两张以上，就可以合并</template>
        </div>
      </aside>

      <section class="sw-main">
        <div class="sw-tools">
          <div class="sw-tools-left">
            <button class="btn" @click="openDialog('blank')">
              <Icon name="plus" :size="14" />新建空白表
            </button>
          </div>
          <div class="sw-tools-right">
            <button v-if="tables.length" class="btn-text" @click="openAll">全部展开</button>
            <button v-if="opened.length" class="btn-text" @click="closeAll">全部收起</button>
            <router-link class="btn btn-sm" to="/sheets/repo">
              <Icon name="folder" :size="13" />我的仓库
            </router-link>
          </div>
        </div>

        <div v-if="listError" class="banner banner-err">
          {{ listError }}
          <button class="btn btn-sm" @click="loadTables()">重试</button>
        </div>

        <div v-if="loading && !tables.length" class="panel sw-loading">正在读取表格…</div>

        <div v-else-if="!tables.length" class="panel sw-empty">
          <span class="sw-empty-icon"><Icon :name="keyword ? 'search' : 'table'" :size="24" /></span>
          <b>{{ keyword ? `没有匹配「${keyword}」的表格` : '还没有表格' }}</b>
          <p v-if="keyword">换个关键词，或者清空搜索看看全部表格。</p>
          <p v-else>上传一个或多个 Excel，数据都存在本地，随时可以下载。</p>
          <button v-if="keyword" class="btn" @click="keyword = ''; loadTables()">清空搜索</button>
          <div
            v-else
            class="sw-drop sw-drop-lg"
            :class="{ 'is-over': dragging, 'is-busy': uploadBusy }"
            role="button"
            tabindex="0"
            @click="pickFiles"
            @keydown.enter.prevent="pickFiles"
            @dragenter.prevent="dragging = true"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="onDrop"
          >
            <span class="sw-drop-icon"><Icon name="upload" :size="19" /></span>
            <b>{{ uploadBusy ? '正在读表…' : '上传 Excel' }}</b>
            <span class="sw-drop-sub">拖入或点击选择，可多选 · .xlsx / .xls</span>
          </div>
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
            <span>新建就说清列名；融合先勾选表格；示例只把说法填进输入框，确认后点「生成」发送</span>
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
  border: 1px dashed #cfd5df;
  border-radius: 10px;
  background:
    repeating-linear-gradient(135deg, rgb(43 95 217 / 2.2%) 0 8px, transparent 8px 16px),
    #fbfcfe;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.sw-drop:hover,
.sw-drop.is-over {
  border-color: var(--accent);
  background: var(--accent-soft);
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
  border: 1px solid #d9e2f5;
  border-radius: 9px;
  background: #fff;
  color: var(--accent);
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
  width: 100%;
  max-width: 420px;
  padding: 24px 16px;
}

.sw-list {
  flex: 1;
  min-height: 80px;
  margin: 10px -4px 0;
  padding: 0 4px;
  overflow-y: auto;
  list-style: none;
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
  accent-color: var(--accent);
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
  padding: 9px 2px 2px;
  border-top: 1px solid var(--line-soft);
  color: var(--muted);
  font-size: 11px;
}

/* ---------- 主区 ---------- */
.sw-main {
  min-width: 0;
}

.sw-tools {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.sw-tools-left,
.sw-tools-right {
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

.sw-loading {
  padding: 40px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}

/* ---------- 空状态 ---------- */
.sw-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 54px 20px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}

.sw-empty-icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: 12px;
  border-radius: 50%;
  background: #f2f3f5;
  color: var(--muted);
}

.sw-empty b {
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
}

.sw-empty p {
  max-width: 420px;
  margin: 6px 0 18px;
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
