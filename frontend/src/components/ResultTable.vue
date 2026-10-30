<script setup>
// 结果表：后端分页 + 模糊查询。
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from './Icon.vue'
import { api } from '../api'

const props = defineProps({
  // 任务结果表传 taskId，表格智作传 sheetId，其余交互完全一样
  taskId: { type: String, default: '' },
  sheetId: { type: String, default: '' },
  columns: { type: Array, default: () => [] },
  // 改完结果会重新导出，这里用一个变化的 key 让表格重新拉第一页
  refreshKey: { type: [String, Number], default: '' },
  defaultPageSize: { type: Number, default: 10 },
  // 表格智作里允许双击单元格直接改、行尾删除整行
  editable: { type: Boolean, default: false },
  // 加行后用这个信号翻到最后一页，让新行直接出现在眼前
  showLast: { type: Number, default: 0 }
})

const emit = defineEmits(['edited', 'deleted-row'])

const rows = ref([])
const columns = ref([])
const numericColumns = ref([])
const total = ref(0)
const allRows = ref(0)
const page = ref(1)
// 当前屏幕上这批行属于第几页：翻页请求还没回来时，行号和底栏先保持旧值，避免出现“第 11–20 行”实际只有 3 行
const loadedPage = ref(1)
const loadedSize = ref(props.defaultPageSize)
const pages = ref(1)
const size = ref(props.defaultPageSize)
const keyword = ref('')
const column = ref('all')
const jump = ref('')
const loading = ref(false)
const slow = ref(false)
const error = ref('')
const partial = ref(false)
const source = ref('excel')
const updatedAt = ref('')

const sizeOptions = [10, 20, 50, 100]
const scroller = ref(null)
const searchInput = ref(null)

// 双击编辑：一次只改一个格子，行号用绝对行号（1 开始），和后端保持一致
const editCell = ref(null)
const editInput = ref(null)
const savingCell = ref(false)

let debounceTimer = null
let slowTimer = null
let seq = 0

const numericSet = computed(() => new Set(numericColumns.value))
const rowStart = computed(() => (loadedPage.value - 1) * loadedSize.value)
const hasFilter = computed(() => !!keyword.value.trim() || column.value !== 'all')

// 底栏按主流表格的说法：“第 1–10 行，共 13 行”；筛选后先说筛出多少
const rangeText = computed(() => {
  if (!rows.value.length) return '0 行'
  const from = rowStart.value + 1
  const to = rowStart.value + rows.value.length
  return from === to ? `第 ${from} 行` : `第 ${from}–${to} 行`
})

const pageItems = computed(() => {
  const last = pages.value
  const cur = page.value
  if (last <= 7) {
    return Array.from({ length: last }, (_, i) => i + 1)
  }
  const seen = new Set([1, last, cur - 1, cur, cur + 1])
  if (cur <= 3) {
    seen.add(2)
    seen.add(3)
    seen.add(4)
  }
  if (cur >= last - 2) {
    seen.add(last - 1)
    seen.add(last - 2)
    seen.add(last - 3)
  }
  const sorted = [...seen].filter((n) => n >= 1 && n <= last).sort((a, b) => a - b)
  const items = []
  sorted.forEach((n, i) => {
    if (i && n - sorted[i - 1] > 1) items.push('...')
    items.push(n)
  })
  return items
})

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

// 把命中的关键字标黄，拼接前先转义，避免表格里的内容被当成 HTML 执行。
function highlight(value) {
  const text = value == null ? '' : String(value)
  const tokens = keyword.value
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
  if (!tokens.length) return escapeHtml(text)
  const lower = text.toLowerCase()
  const ranges = []
  tokens.forEach((token) => {
    let at = lower.indexOf(token)
    while (at >= 0) {
      ranges.push([at, at + token.length])
      at = lower.indexOf(token, at + token.length)
    }
  })
  if (!ranges.length) return escapeHtml(text)
  ranges.sort((a, b) => a[0] - b[0])
  const merged = []
  ranges.forEach((range) => {
    const last = merged[merged.length - 1]
    if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1])
    else merged.push([range[0], range[1]])
  })
  let html = ''
  let cursor = 0
  merged.forEach(([from, to]) => {
    html += escapeHtml(text.slice(cursor, from))
    html += `<mark>${escapeHtml(text.slice(from, to))}</mark>`
    cursor = to
  })
  html += escapeHtml(text.slice(cursor))
  return html
}

async function load() {
  const current = ++seq
  loading.value = true
  if (slowTimer) clearTimeout(slowTimer)
  slowTimer = setTimeout(() => {
    if (current === seq) slow.value = true
  }, 180)
  try {
    const params = {
      page: page.value,
      page_size: size.value,
      q: keyword.value.trim(),
      column: column.value === 'all' ? '' : column.value
    }
    const data = props.sheetId
      ? await api.sheetRows(props.sheetId, params)
      : await api.taskRows(props.taskId, params)
    if (current !== seq) return
    rows.value = data.rows || []
    columns.value = data.columns && data.columns.length ? data.columns : props.columns
    numericColumns.value = data.numeric_columns || []
    total.value = Number(data.total) || 0
    allRows.value = Number(data.total_rows) || 0
    pages.value = Math.max(1, Number(data.pages) || 1)
    const respPage = Math.max(1, Number(data.page) || 1)
    loadedPage.value = respPage
    loadedSize.value = size.value
    page.value = respPage
    partial.value = !!data.partial
    source.value = data.source || 'excel'
    updatedAt.value = data.updated_at || ''
    error.value = ''
  } catch (err) {
    if (current !== seq) return
    error.value = err.message
  } finally {
    if (current === seq) {
      loading.value = false
      slow.value = false
      if (slowTimer) {
        clearTimeout(slowTimer)
        slowTimer = null
      }
    }
  }
}

function resetAndLoad() {
  if (page.value !== 1) page.value = 1
  else load()
}

function go(n) {
  const target = Math.min(Math.max(1, Number(n) || 1), pages.value)
  if (target !== page.value) page.value = target
}

function jumpTo() {
  const n = Number(jump.value)
  jump.value = ''
  if (Number.isFinite(n) && n >= 1) go(n)
}

function scrollTop() {
  if (scroller.value) scroller.value.scrollTop = 0
}

function prevPage() {
  go(page.value - 1)
}

function nextPage() {
  go(page.value + 1)
}

function firstPage() {
  go(1)
}

function lastPage() {
  go(pages.value)
}

function clearKeyword() {
  keyword.value = ''
  resetAndLoad()
}

// ==================== 双击改单元格 ====================

const editableSheet = computed(() => props.editable && !!props.sheetId)

function isEditing(rowNo, name) {
  return editCell.value && editCell.value.row === rowNo && editCell.value.column === name
}

function startEdit(row, name, index) {
  if (!editableSheet.value || savingCell.value) return
  const rowNo = rowStart.value + index + 1
  const original = row[name] == null ? '' : String(row[name])
  editCell.value = { row: rowNo, column: name, original, value: original }
  nextTick(() => {
    if (editInput.value) {
      editInput.value.focus()
      editInput.value.select()
    }
  })
}

function cancelEdit() {
  editCell.value = null
}

// 编辑框在 v-for 里，用函数 ref 只记住当前这一个
function setEditInput(el) {
  if (el) editInput.value = el
}

async function commitEdit() {
  const cell = editCell.value
  if (!cell || savingCell.value) return
  if (cell.value === cell.original) {
    editCell.value = null
    return
  }
  savingCell.value = true
  try {
    await api.updateSheetCell(props.sheetId, {
      row: cell.row,
      column: cell.column,
      value: cell.value
    })
    editCell.value = null
    emit('edited', { row: cell.row, column: cell.column, value: cell.value })
    await load()
  } catch (err) {
    error.value = err.message
  } finally {
    savingCell.value = false
  }
}

watch(keyword, () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(resetAndLoad, 220)
})

watch(size, resetAndLoad)
watch(column, resetAndLoad)
watch(page, () => {
  scrollTop()
  load()
})

watch(
  () => props.refreshKey,
  () => {
    columns.value = props.columns
    // 数据在原来的页上被改了，就地刷新，不用把用户弹回第一页
    load()
  }
)

watch(
  () => props.showLast,
  async () => {
    await load()
    if (pages.value > 1) go(pages.value)
  }
)

watch(
  () => [props.taskId, props.sheetId],
  () => {
    keyword.value = ''
    column.value = 'all'
    rows.value = []
    editCell.value = null
    resetAndLoad()
  }
)

// 用惯了 Excel 的人会直接按 Ctrl+F 找数据，这里让搜索框接住这个习惯
function onWindowKey(event) {
  if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 'f') {
    const input = searchInput.value
    if (!input) return
    event.preventDefault()
    input.focus()
    input.select()
  }
}

onMounted(() => {
  columns.value = props.columns
  window.addEventListener('keydown', onWindowKey)
  load()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKey)
  if (debounceTimer) clearTimeout(debounceTimer)
  if (slowTimer) clearTimeout(slowTimer)
  seq += 1
})
</script>

<template>
  <div class="rt">
    <div class="rt-toolbar">
      <div class="rt-tools">
        <div class="rt-search">
          <Icon name="search" :size="14" />
          <input
            ref="searchInput"
            v-model="keyword"
            type="search"
            placeholder="搜索表格内容，空格分隔多个关键词"
            title="Ctrl+F 快速定位到这里"
            @keydown.esc="clearKeyword"
          />
          <button v-if="keyword" class="rt-clear" type="button" title="清空" @click="clearKeyword">
            <Icon name="close" :size="12" />
          </button>
        </div>
        <select v-model="column" class="rt-select" title="选择搜索范围">
          <option value="all">全部列</option>
          <option v-for="name in columns" :key="name" :value="name">{{ name }}</option>
        </select>
        <span class="rt-info">
          <template v-if="hasFilter">筛出 <b>{{ total }}</b> 行 / 共 {{ allRows }} 行</template>
          <template v-else>共 <b>{{ total }}</b> 行</template>
        </span>
      </div>
      <div class="rt-size">
        <span>每页</span>
        <select v-model.number="size" class="rt-select">
          <option v-for="n in sizeOptions" :key="n" :value="n">{{ n }}</option>
        </select>
        <span>条</span>
      </div>
    </div>

    <div v-if="error" class="rt-err">
      {{ error }}
      <button class="btn btn-sm" @click="load">重试</button>
    </div>

    <div
      ref="scroller"
      class="rt-scroll"
      tabindex="0"
      :title="editableSheet ? '双击单元格直接改，点击表格后可用 ← → 翻页' : '点击表格后可用 ← → 翻页'"
      @keydown.left.prevent="prevPage"
      @keydown.right.prevent="nextPage"
      @keydown.home.prevent="firstPage"
      @keydown.end.prevent="lastPage"
    >
      <table class="rt-table" :class="{ 'is-editable': editableSheet }">
        <thead>
          <tr>
            <th class="rt-no">#</th>
            <th v-for="name in columns" :key="name" :class="{ 'is-num': numericSet.has(name) }">
              {{ name }}
            </th>
            <th v-if="editableSheet" class="rt-ops"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="rowStart + index">
            <td class="rt-no">{{ rowStart + index + 1 }}</td>
            <td
              v-for="name in columns"
              :key="name"
              :class="{ 'is-num': numericSet.has(name), 'is-editing': isEditing(rowStart + index + 1, name) }"
              :title="editableSheet ? '双击修改' : row[name] || ''"
              @dblclick="startEdit(row, name, index)"
            >
              <input
                v-if="isEditing(rowStart + index + 1, name)"
                :ref="setEditInput"
                v-model="editCell.value"
                class="rt-edit"
                @keydown.enter.prevent="commitEdit"
                @keydown.esc.prevent="cancelEdit"
                @blur="commitEdit"
              />
              <template v-else-if="row[name]">
                <span v-html="highlight(row[name])"></span>
              </template>
              <span v-else class="rt-empty">—</span>
            </td>
            <td v-if="editableSheet" class="rt-ops">
              <button
                type="button"
                class="rt-row-del"
                title="删除这一行（可以撤销）"
                @click="emit('deleted-row', rowStart + index + 1)"
              >
                <Icon name="trash" :size="13" />
              </button>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="columns.length + (editableSheet ? 2 : 1)" class="rt-none">
              {{ loading ? '正在加载…' : hasFilter ? '没有匹配的记录，换个关键字试试' : '还没有数据，点上方「加行」开始录入' }}
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading && slow" class="rt-mask">正在查询…</div>
    </div>

    <div class="rt-footer">
      <div class="rt-summary">
        <template v-if="hasFilter">
          筛出 <b>{{ total }}</b> 行 · <span class="rt-range">{{ rangeText }}</span>
        </template>
        <template v-else>
          <span class="rt-range">{{ rangeText }}</span> · 共 <b>{{ allRows }}</b> 行
        </template>
        <span v-if="partial" class="rt-warn">· 导出文件不完整，仅显示前 {{ allRows }} 行</span>
        <span v-else-if="source === 'excel' && updatedAt" class="rt-time">· 数据更新于 {{ updatedAt }}</span>
      </div>
      <div v-if="pages > 1" class="rt-pager">
        <button class="rt-page" :disabled="page <= 1" title="首页" @click="firstPage">«</button>
        <button class="rt-page" :disabled="page <= 1" title="上一页" @click="prevPage">
          <Icon name="left" :size="12" />
        </button>
        <template v-for="(item, index) in pageItems" :key="`${item}-${index}`">
          <span v-if="item === '...'" class="rt-gap">…</span>
          <button v-else class="rt-page" :class="{ on: item === page }" @click="go(item)">
            {{ item }}
          </button>
        </template>
        <button class="rt-page" :disabled="page >= pages" title="下一页" @click="nextPage">
          <Icon name="right" :size="12" />
        </button>
        <button class="rt-page" :disabled="page >= pages" title="末页" @click="lastPage">»</button>
        <span class="rt-jump">
          前往
          <input v-model="jump" type="number" min="1" :max="pages" @keydown.enter="jumpTo" />
          页
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rt {
  background: #fff;
}

.rt-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.rt-tools,
.rt-size {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.rt-search {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 260px;
  max-width: 100%;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  color: var(--muted);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.rt-search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.rt-search input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-size: 12px;
}

.rt-search input::-webkit-search-cancel-button {
  display: none;
}

.rt-clear {
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--muted);
}

.rt-select {
  height: 32px;
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
}

.rt-info {
  color: var(--muted);
  font-size: 12px;
}

.rt-info b {
  color: var(--ink);
  font-weight: 600;
}

.rt-size {
  color: var(--muted);
  font-size: 12px;
}

.rt-size .rt-select {
  height: 28px;
}

.rt-err {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 14px;
  border-bottom: 1px solid var(--line-soft);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 12px;
}

.rt-scroll {
  position: relative;
  max-height: 520px;
  overflow: auto;
  outline: none;
  background: #fff;
}

.rt-scroll:focus-visible {
  box-shadow: inset 0 0 0 2px var(--accent-soft);
}

/* 列宽按内容收缩，不用 100% 去拉伸，否则空列/短数据列会被撑得很大片空白 */
.rt-table {
  width: max-content;
  min-width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12.5px;
}

.rt-table th {
  position: sticky;
  top: 0;
  z-index: 2;
  max-width: 240px;
  padding: 8px 10px;
  overflow: hidden;
  border-bottom: 1px solid var(--line);
  background: #fafbfc;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 行号列和操作列保持窄一些，不参与最小宽度 */
.rt-table th:not(.rt-no, .rt-ops) {
  min-width: 72px;
}

.rt-table td {
  max-width: 280px;
  padding: 8px 10px;
  overflow: hidden;
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-table tbody tr:hover td {
  background: #f5f9ff;
}

.rt-table .is-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* 可编辑表格：单元格像 Excel 一样双击进入编辑 */
.rt-table.is-editable tbody td:not(.rt-no, .rt-ops) {
  cursor: text;
}

.rt-table td.is-editing {
  padding: 2px 4px;
  background: #fff;
  box-shadow: inset 0 0 0 2px var(--accent);
}

.rt-table tbody tr:hover td.is-editing {
  background: #fff;
}

.rt-edit {
  width: 100%;
  min-width: 60px;
  height: 28px;
  padding: 0 6px;
  border: none;
  outline: none;
  background: #fff;
  color: var(--ink);
  font-size: 12.5px;
}

.rt-ops {
  width: 36px;
  padding-right: 6px !important;
  padding-left: 0 !important;
  text-align: center !important;
}

.rt-row-del {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: #c9ced6;
  opacity: 0;
  transition: opacity 0.12s, color 0.12s, background 0.12s;
}

.rt-table tbody tr:hover .rt-row-del {
  opacity: 1;
}

.rt-row-del:hover {
  border-color: #f3c7c1;
  background: var(--danger-soft);
  color: var(--danger);
}

.rt-no {
  width: 46px;
  color: var(--muted) !important;
  font-size: 11px;
  text-align: right !important;
}

.rt-empty {
  color: #c9ced6;
}

.rt-none {
  padding: 42px 14px !important;
  color: var(--muted);
  font-size: 12px;
  text-align: center !important;
}

.rt-mask {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.62);
  color: var(--muted);
  font-size: 12px;
}

.rt-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-top: 1px solid var(--line-soft);
  background: #fff;
}

.rt-summary {
  color: var(--muted);
  font-size: 12px;
}

.rt-summary b {
  color: var(--ink);
  font-weight: 600;
}

.rt-warn,
.rt-time {
  margin-left: 4px;
}

.rt-warn {
  color: var(--warn);
}

.rt-time {
  color: var(--muted);
}

.rt-pager {
  display: flex;
  gap: 4px;
  align-items: center;
}

.rt-page {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
}

.rt-page:hover:not(:disabled) {
  border-color: #b9cdf5;
  background: #f5f8ff;
  color: var(--accent);
}

.rt-page.on {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}

.rt-page:disabled {
  opacity: 0.4;
}

.rt-gap {
  padding: 0 2px;
  color: var(--muted);
}

.rt-jump {
  display: flex;
  gap: 5px;
  align-items: center;
  margin-left: 6px;
  color: var(--muted);
  font-size: 12px;
}

.rt-jump input {
  width: 48px;
  height: 28px;
  padding: 0 4px;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 12px;
  text-align: center;
}

.rt-jump input:focus {
  border-color: var(--accent);
  outline: none;
}

.rt :deep(mark) {
  padding: 0 1px;
  border-radius: 2px;
  background: #ffe9a8;
  color: inherit;
}
</style>
