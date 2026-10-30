<script setup>
// 我的仓库 —— 表格文件清单
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import { api, isNetworkError } from '../api'
import { clearNotice, showNotice } from '../appNotice'
import { useToast } from '../toast'
import { clearTopbar, useTopbar } from '../topbar'

const router = useRouter()
const { show } = useToast()

useTopbar({
  title: '我的仓库',
  desc: '上传、模板、新建、融合出来的表格都在这里；文件保存在本机，随时下载或继续改。'
})

onBeforeUnmount(() => clearTopbar())

const tables = ref([])
const loading = ref(true)
const error = ref('')
const keyword = ref('')

// ---------------- 表格列表：服务端分页，一页 10 张 ----------------
const TABLE_PAGE_SIZE = 10
const tablesPage = ref(1)
const tablesPages = ref(1)
const tablesTotal = ref(0)
// 顶部统计按全库算，翻页不跟着变
const totals = ref({ sheets: 0, rows: 0, revisions: 0 })
// 表格列表的勾选：跨页保留会让“删了页外的表”变得不可预期，所以勾选只在本页有效，翻页即清空
const tableChecked = ref([])
const tableBusy = ref(false)

const allTablesChecked = computed(
  () => tables.value.length > 0 && tableChecked.value.length === tables.value.length
)

function isTableChecked(id) {
  return tableChecked.value.includes(id)
}

function toggleTableOne(id) {
  tableChecked.value = isTableChecked(id)
    ? tableChecked.value.filter((item) => item !== id)
    : [...tableChecked.value, id]
}

function toggleTableAll() {
  tableChecked.value = allTablesChecked.value ? [] : tables.value.map((item) => item.id)
}

// 勾选多张后统一删：每张表都会在操作日志里留一条「删除表格」，和单张删除一致
async function removeCheckedTables() {
  const ids = [...tableChecked.value]
  if (!ids.length || tableBusy.value) return
  const names = tables.value.filter((item) => ids.includes(item.id)).map((item) => item.name)
  const tip = ids.length === 1
    ? `确定删除「${names[0]}」？文件会一起删掉，删除后不能恢复。`
    : `确定删除选中的 ${ids.length} 张表？\n${names.slice(0, 5).join('、')}${names.length > 5 ? ' 等' : ''}\n文件会一起删掉，删除后不能恢复。`
  if (!window.confirm(tip)) return
  tableBusy.value = true
  try {
    const res = await api.deleteSheets(ids)
    const count = res?.deleted ?? ids.length
    tableChecked.value = []
    show(`已删除 ${count} 张表`)
    await loadTables(tablesPage.value, true)
    if (!tables.value.length && tablesPage.value > 1) await loadTables(tablesPage.value - 1)
    await loadLogs()
  } catch (err) {
    show(err.message || '删除失败', 'err')
  } finally {
    tableBusy.value = false
  }
}

// ---------------- 操作日志：服务端分页 + 勾选删除 ----------------
const LOG_PAGE_SIZE = 10
const logs = ref([])
const logsPage = ref(1)
const logsPages = ref(1)
const logsTotal = ref(0)
const logsKeep = ref(100)
const logsLoading = ref(true)
const logChecked = ref([])
const logBusy = ref(false)

const allChecked = computed(() => logs.value.length > 0 && logChecked.value.length === logs.value.length)

const dialog = reactive({
  open: false,
  kind: '',
  sheetId: '',
  name: '',
  busy: false,
  error: ''
})

let searchTimer = null

const summary = computed(() => [
  { label: '表格', value: totals.value.sheets, unit: '张' },
  { label: '数据行', value: totals.value.rows, unit: '行' },
  { label: '修改记录', value: totals.value.revisions, unit: '次' }
])

// 当前页是第几条到第几条，底栏按主流表格的说法写「第 1–10 条，共 37 张」
const pageFrom = computed(() => (tablesTotal.value ? (tablesPage.value - 1) * TABLE_PAGE_SIZE + 1 : 0))
const pageTo = computed(() => Math.min(tablesTotal.value, pageFrom.value + tables.value.length - 1))

const tablePageList = computed(() => pageWindow(tablesPage.value, tablesPages.value))

// 主流分页器的页码规则：首尾页常驻，当前页左右各一页，中间跳过的用省略号
function pageWindow(current, pages) {
  const total = Math.max(pages, 1)
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  const items = [1]
  let start = Math.max(2, current - 1)
  let end = Math.min(total - 1, current + 1)
  if (current <= 3) {
    start = 2
    end = 4
  }
  if (current >= total - 2) {
    start = total - 3
    end = total - 1
  }
  if (start > 2) items.push('...')
  for (let i = start; i <= end; i += 1) items.push(i)
  if (end < total - 1) items.push('...')
  items.push(total)
  return items
}

function originText(origin) {
  return ({ upload: '上传', template: '模板', nl: '一句话新建', merge: '多表融合', task: '任务归档' })[origin] || '表格'
}

function fmtTime(value) {
  const text = String(value || '')
  // 列表里只显示到分钟，年份占位置又没什么信息量
  return text.length > 16 ? text.slice(5, 16) : text
}

// 「处理完成」这类结论在日志标题里点明，勾选删除时也好辨认
function logActionText(log) {
  return log.action || '操作'
}

async function loadTables(page = tablesPage.value, silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    const data = await api.sheetPage({ page, pageSize: TABLE_PAGE_SIZE, q: keyword.value.trim() })
    tables.value = data?.items || []
    tablesPage.value = data?.page || 1
    tablesPages.value = data?.pages || 1
    tablesTotal.value = data?.total || 0
    totals.value = {
      sheets: data?.sheet_total || 0,
      rows: data?.row_total || 0,
      revisions: data?.revision_total || 0
    }
    // 翻页或刷新后清空勾选，避免删到当前页看不见的表
    tableChecked.value = []
    // 删掉当前页最后一张表时页码会越界，服务端已把 page 收回来，这里跟着再取一次
    if (!tables.value.length && tablesPage.value > 1) {
      await loadTables(tablesPage.value)
    }
    clearNotice()
  } catch (err) {
    error.value = isNetworkError(err) ? '暂时连不上服务，请稍后刷新重试' : err.message
    // 统一显示在顶栏下方的全局提示里，正文不单独摆红带
    showNotice(error.value, () => loadTables(tablesPage.value))
  } finally {
    loading.value = false
  }
}

function goPage(page) {
  if (page < 1 || page > tablesPages.value || page === tablesPage.value || loading.value) return
  loadTables(page)
}

async function loadLogs(page = logsPage.value) {
  logsLoading.value = true
  try {
    const data = await api.sheetLogs(page, LOG_PAGE_SIZE)
    logs.value = data?.items || []
    logsPage.value = data?.page || 1
    logsPages.value = data?.pages || 1
    logsTotal.value = data?.total || 0
    logsKeep.value = data?.keep || 100
    logChecked.value = []
    clearNotice()
  } catch (err) {
    // 统一交给顶栏下方的全局提示，面板里保持“还没有处理记录”的空态，不重复报错
    showNotice(err.message, () => loadLogs(page))
  } finally {
    logsLoading.value = false
  }
}

function goLogPage(page) {
  if (page < 1 || page > logsPages.value || page === logsPage.value || logsLoading.value) return
  loadLogs(page)
}

function isLogChecked(id) {
  return logChecked.value.includes(id)
}

function toggleLogOne(id) {
  logChecked.value = isLogChecked(id)
    ? logChecked.value.filter((item) => item !== id)
    : [...logChecked.value, id]
}

function toggleLogAll() {
  logChecked.value = allChecked.value ? [] : logs.value.map((log) => log.id)
}

// 不传 ids 就是清空全部；两种都在删之前确认一次，避免误点
async function removeLogs(ids) {
  if (logBusy.value) return
  const target = ids || []
  const count = target.length || logsTotal.value
  if (!count) return
  const tip = target.length
    ? `确定删除选中的 ${target.length} 条操作日志？`
    : `确定清空全部 ${count} 条操作日志？表格数据不受影响。`
  if (!window.confirm(tip)) return
  logBusy.value = true
  try {
    const res = await api.deleteSheetLogs(target)
    show(`已删除 ${res?.deleted ?? count} 条日志`)
    await loadLogs(target.length ? logsPage.value : 1)
  } catch (err) {
    show(err.message || '删除日志失败', 'err')
  } finally {
    logBusy.value = false
  }
}

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  // 改了关键字就从第 1 页重新查，停在旧页码上大概率是空页
  searchTimer = setTimeout(() => loadTables(1), 220)
}

function resetSearch() {
  keyword.value = ''
  loadTables(1)
}

function openSheet(item) {
  router.push({ name: 'sheets', query: { id: item.id } })
}

function openDialog(kind, item) {
  dialog.open = true
  dialog.kind = kind
  dialog.sheetId = item.id
  dialog.name = item.name
  dialog.busy = false
  dialog.error = ''
}

async function submitRename() {
  const name = dialog.name.trim()
  if (!name) {
    dialog.error = '表名不能为空'
    return
  }
  dialog.busy = true
  dialog.error = ''
  try {
    await api.renameSheet(dialog.sheetId, name)
    dialog.open = false
    show('已重命名')
    await loadTables(tablesPage.value, true)
    await loadLogs()
  } catch (err) {
    dialog.error = err.message
  } finally {
    dialog.busy = false
  }
}

async function submitDelete() {
  dialog.busy = true
  dialog.error = ''
  try {
    await api.deleteSheet(dialog.sheetId)
    dialog.open = false
    show('已删除表格')
    await loadTables(tablesPage.value, true)
    await loadLogs()
  } catch (err) {
    dialog.error = err.message
  } finally {
    dialog.busy = false
  }
}

onMounted(async () => {
  await Promise.all([loadTables(), loadLogs()])
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <div class="repo">
    <div class="repo-layout">
      <section class="repo-main">
        <div class="repo-metrics">
          <div v-for="item in summary" :key="item.label" class="repo-metric">
            <span>{{ item.label }}</span>
            <b>{{ item.value }}<em>{{ item.unit }}</em></b>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head repo-head">
            <div class="repo-search">
              <Icon name="search" :size="14" />
              <input
                v-model="keyword"
                type="search"
                placeholder="按表名、列名搜索"
                @input="onSearchInput"
                @keydown.esc="resetSearch"
              />
              <button v-if="keyword" class="repo-clear" title="清空" @click="resetSearch">
                <Icon name="close" :size="12" />
              </button>
            </div>
            <!-- 勾选后，已选数量和动作直接占用工具栏中间的空位，不再另起一条横幅、表格也不会往下跳 -->
            <div v-if="tableChecked.length" class="repo-head-picked">
              <span class="repo-picked-count">已选 <b>{{ tableChecked.length }}</b> 张表</span>
              <button type="button" class="btn-text" :disabled="tableBusy" @click="tableChecked = []">
                取消选择
              </button>
              <button
                type="button"
                class="btn-text btn-text-danger"
                :disabled="tableBusy"
                @click="removeCheckedTables"
              >
                {{ tableBusy ? '删除中…' : '删除' }}
              </button>
            </div>
            <div class="repo-head-actions">
              <span class="panel-sub">共 {{ tablesTotal }} 张表</span>
              <button class="btn btn-sm" @click="loadTables()">
                <Icon name="refresh" :size="13" />刷新
              </button>
              <router-link class="btn btn-sm btn-primary" to="/sheets">
                <Icon name="plus" :size="13" />去表格智作
              </router-link>
            </div>
          </div>

          <!-- 报错统一显示在顶栏下方那条全局提示里 -->

          <div v-if="loading" class="empty">正在读取仓库…</div>

          <div v-else-if="!tables.length" class="empty">
            <span class="empty-icon"><Icon name="folder" :size="18" /></span>
            <b>{{ keyword ? '没有匹配的表格' : '仓库还是空的' }}</b>
            <p>{{ keyword ? '换个关键字，或清空搜索看看全部表格。' : '去表格智作上传一个 Excel，或用模板建一张。' }}</p>
            <router-link class="btn btn-primary" to="/sheets">打开表格智作</router-link>
          </div>

          <!-- 表格列表：勾选后工具栏中间显示「已选 N 张表 · 取消选择 · 删除」 -->
          <template v-else>
          <table class="data-table repo-table">
            <thead>
              <tr>
                <th class="repo-col-pick">
                  <input
                    type="checkbox"
                    :checked="allTablesChecked"
                    :indeterminate.prop="tableChecked.length > 0 && !allTablesChecked"
                    title="全选本页"
                    @change="toggleTableAll"
                  />
                </th>
                <th>名称</th>
                <th class="repo-col-origin">来源</th>
                <th class="repo-col-num right">行数</th>
                <th class="repo-col-num right">列数</th>
                <th class="repo-col-num right">修改</th>
                <th class="repo-col-time">更新时间</th>
                <th class="repo-col-ops">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in tables"
                :key="item.id"
                class="row-link"
                :class="{ 'is-picked': isTableChecked(item.id) }"
                @click="openSheet(item)"
              >
                <td class="repo-col-pick" @click.stop>
                  <input
                    type="checkbox"
                    :checked="isTableChecked(item.id)"
                    @change="toggleTableOne(item.id)"
                  />
                </td>
                <td class="repo-col-name">
                  <span class="repo-name">{{ item.name }}</span>
                  <span class="repo-note">{{ item.note }}</span>
                  <span class="task-id">{{ item.id }}</span>
                </td>
                <td class="repo-col-origin">
                  <span class="tag" :class="item.origin === 'merge' || item.origin === 'task' ? 'tag-blue' : 'tag-neutral'">
                    {{ originText(item.origin) }}
                  </span>
                </td>
                <td class="repo-col-num right">{{ item.row_count }}</td>
                <td class="repo-col-num right">{{ item.column_count }}</td>
                <td class="repo-col-num right">{{ item.revision_count }}</td>
                <td class="repo-col-time repo-time">{{ fmtTime(item.updated_at) }}</td>
                <td class="repo-col-ops" @click.stop>
                  <div class="repo-ops">
                    <a class="btn-text" :href="api.sheetDownloadUrl(item.id)" download @click.stop>下载</a>
                    <button class="btn-text" @click="openDialog('rename', item)">重命名</button>
                    <button class="btn-text btn-text-danger" @click="openDialog('delete', item)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>

          <!-- 底栏：左边是「第 1–10 条，共 37 张」，右边是标准分页器 -->
          <div v-if="tablesTotal && !loading" class="repo-pager">
            <span class="repo-pager-total">第 {{ pageFrom }}–{{ pageTo }} 条，共 {{ tablesTotal }} 张</span>
            <div class="repo-pager-ctrl">
              <button
                type="button"
                class="repo-page-arrow"
                title="上一页"
                :disabled="tablesPage <= 1"
                @click="goPage(tablesPage - 1)"
              >
                <Icon name="left" :size="12" />
              </button>
              <template v-for="(item, index) in tablePageList" :key="`${item}-${index}`">
                <span v-if="item === '...'" class="repo-page-dots">…</span>
                <button
                  v-else
                  type="button"
                  class="repo-page-btn"
                  :class="{ 'is-active': item === tablesPage }"
                  @click="goPage(item)"
                >
                  {{ item }}
                </button>
              </template>
              <button
                type="button"
                class="repo-page-arrow"
                title="下一页"
                :disabled="tablesPage >= tablesPages"
                @click="goPage(tablesPage + 1)"
              >
                <Icon name="right" :size="12" />
              </button>
            </div>
          </div>
          </template>
        </div>
      </section>

      <aside class="repo-side">
        <div class="panel">
          <div class="panel-head">
            <h2>操作日志</h2>
            <div class="repo-log-tools">
              <span class="panel-sub">最近 {{ logsKeep }} 条</span>
              <button class="btn-text" :disabled="logsLoading" @click="loadLogs(logsPage)">刷新</button>
            </div>
          </div>
          <!-- 日志读取失败统一显示在顶栏下方那条全局提示里，这里不再单独摆红带 -->
          <div v-if="logsLoading && !logs.length" class="repo-log-empty">正在读取日志…</div>
          <div v-else-if="!logs.length" class="repo-log-empty">还没有处理记录。</div>
          <template v-else>
            <div class="repo-log-select">
              <label class="repo-check">
                <input type="checkbox" :checked="allChecked" @change="toggleLogAll" />
                本页全选
              </label>
              <span class="repo-log-count">已选 {{ logChecked.length }} / 共 {{ logsTotal }} 条</span>
            </div>
            <ul class="repo-logs">
              <li v-for="log in logs" :key="log.id" :class="log.level">
                <label class="repo-check repo-log-pick">
                  <input type="checkbox" :checked="isLogChecked(log.id)" @change="toggleLogOne(log.id)" />
                </label>
                <div class="repo-log-main">
                  <div class="repo-log-head">
                    <span class="repo-log-time">{{ fmtTime(log.time) }}</span>
                    <b>{{ logActionText(log) }}</b>
                  </div>
                  <p>
                    <span class="repo-log-sheet">{{ log.sheet_name }}</span>
                    <span v-if="log.deleted" class="repo-log-gone">已删除</span>
                    <span class="repo-log-dot">·</span>
                    {{ log.detail }}
                  </p>
                </div>
              </li>
            </ul>
            <div class="repo-log-foot">
              <div class="repo-log-pager">
                <button class="btn-text" :disabled="logsPage <= 1 || logsLoading" @click="goLogPage(logsPage - 1)">
                  上一页
                </button>
                <span class="repo-log-page-num">{{ logsPage }} / {{ logsPages }}</span>
                <button
                  class="btn-text"
                  :disabled="logsPage >= logsPages || logsLoading"
                  @click="goLogPage(logsPage + 1)"
                >
                  下一页
                </button>
              </div>
              <div class="repo-log-actions">
                <button
                  class="btn-text"
                  :disabled="!logChecked.length || logBusy"
                  @click="removeLogs(logChecked)"
                >
                  删除所选
                </button>
                <button class="btn-text btn-text-danger" :disabled="!logsTotal || logBusy" @click="removeLogs([])">
                  清空全部
                </button>
              </div>
            </div>
          </template>
        </div>
      </aside>
    </div>

    <div v-if="dialog.open" class="repo-modal" @click.self="dialog.open = false">
      <div class="repo-modal-card">
        <header class="repo-modal-head">
          <b>{{ dialog.kind === 'rename' ? '重命名表格' : '删除表格' }}</b>
          <button class="repo-icon" title="关闭" @click="dialog.open = false">
            <Icon name="close" :size="14" />
          </button>
        </header>

        <template v-if="dialog.kind === 'rename'">
          <label class="field">
            <span class="field-label">表名</span>
            <input v-model="dialog.name" class="input" @keydown.enter="submitRename" />
          </label>
          <p class="repo-modal-hint">只改名字，表格内容不变。</p>
        </template>
        <p v-else class="repo-modal-hint">
          确定删除「{{ dialog.name }}」？文件会一起删掉，删除后不能恢复。
        </p>

        <p v-if="dialog.error" class="repo-err">{{ dialog.error }}</p>

        <footer class="repo-modal-foot">
          <button class="btn" @click="dialog.open = false">取消</button>
          <button
            class="btn"
            :class="dialog.kind === 'rename' ? 'btn-primary' : 'btn-danger-solid'"
            :disabled="dialog.busy"
            @click="dialog.kind === 'rename' ? submitRename() : submitDelete()"
          >
            {{ dialog.busy ? '处理中…' : dialog.kind === 'rename' ? '确定' : '删除' }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.repo {
  width: 100%;
  max-width: 1320px;
  margin: 0 auto;
}

.repo-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 16px;
  align-items: start;
}

.repo-main {
  min-width: 0;
}

.repo-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 14px;
}

.repo-metric {
  padding: 12px 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
}

.repo-metric span {
  color: var(--muted);
  font-size: 11px;
}

.repo-metric b {
  display: block;
  margin-top: 3px;
  font-size: 19px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.3;
}

.repo-metric em {
  margin-left: 3px;
  color: var(--muted);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
}

.repo-head {
  flex-wrap: wrap;
}

.repo-search {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 260px;
  max-width: 100%;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  color: var(--muted);
}

.repo-search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.repo-search input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-size: 12px;
}

.repo-search input::-webkit-search-cancel-button {
  display: none;
}

.repo-clear {
  padding: 0;
  border: none;
  background: none;
  color: var(--muted);
}

.repo-head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.repo-table {
  table-layout: fixed;
  font-size: 12.5px;
}

/* 勾选列：固定窄宽，和名称列拉开一点距离，避免表头挤在一起 */
.repo-col-pick {
  width: 40px;
  padding-left: 14px !important;
  padding-right: 0 !important;
}

.repo-col-pick input {
  display: block;
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

/* 勾选后占住工具栏中间那块空白：靠右贴近动作按钮，高度不变，表格位置不跳动 */
.repo-head-picked {
  display: flex;
  flex: 1;
  gap: 14px;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
  font-size: 12.5px;
}

.repo-picked-count {
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.repo-picked-count b {
  margin: 0 2px;
  color: var(--text);
  font-weight: 600;
}

.repo-table tbody tr.is-picked {
  background: #f8faff;
}

.repo-table th {
  padding: 9px 14px;
  white-space: normal;
}

.repo-table td {
  padding: 10px 14px;
  white-space: normal;
}

.repo-col-name {
  min-width: 0;
}

.repo-name {
  display: block;
  overflow: hidden;
  color: var(--ink);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-note {
  display: block;
  max-width: 100%;
  margin-top: 1px;
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-table .task-id {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-col-origin {
  width: 90px;
}

.repo-col-num {
  width: 62px;
}

.repo-col-time {
  width: 106px;
}

.repo-col-ops {
  width: 140px;
}

.repo-time {
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.repo-ops {
  display: flex;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.repo-ops .btn-text {
  padding: 0;
}

/* 底部分页器：和主流表格一致，左说明右页码 */
.repo-pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 10px 16px;
  border-top: 1px solid var(--line-soft);
  background: #fbfcfd;
}

.repo-pager-total {
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.repo-pager-ctrl {
  display: flex;
  gap: 4px;
  align-items: center;
}

.repo-page-btn,
.repo-page-arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 26px;
  height: 26px;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: none;
  color: var(--ink-2);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.repo-page-btn:hover,
.repo-page-arrow:hover:not(:disabled) {
  background: #f1f3f6;
  color: var(--ink);
}

.repo-page-btn.is-active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}

.repo-page-arrow:disabled {
  color: #c8ccd4;
  cursor: not-allowed;
}

.repo-page-dots {
  min-width: 20px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
  user-select: none;
}

.repo-side {
  position: sticky;
  top: 96px;
}

.repo-side .panel {
  margin-bottom: 0;
}

.repo-log-tools {
  display: flex;
  gap: 8px;
  align-items: center;
}

.repo-log-select {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 16px;
  border-bottom: 1px solid var(--line-soft);
  background: #fbfcfd;
}

.repo-check {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: var(--muted);
  font-size: 11.5px;
  cursor: pointer;
  user-select: none;
}

.repo-check input {
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

.repo-log-count {
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.repo-logs {
  max-height: calc(100vh - 330px);
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.repo-logs li {
  display: flex;
  gap: 8px;
  padding: 9px 16px;
  border-bottom: 1px solid var(--line-soft);
}

.repo-logs li:last-child {
  border-bottom: none;
}

.repo-log-pick {
  padding-top: 2px;
}

.repo-log-main {
  flex: 1;
  min-width: 0;
}

.repo-log-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  border-top: 1px solid var(--line-soft);
  background: #fbfcfd;
}

.repo-log-pager,
.repo-log-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.repo-log-page-num {
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.repo-log-foot .btn-text {
  padding: 0;
  font-size: 11.5px;
}

.repo-log-foot .btn-text:disabled {
  color: #c8ccd4;
  cursor: not-allowed;
}

.repo-log-head {
  display: flex;
  gap: 6px;
  align-items: baseline;
  flex-wrap: wrap;
  font-size: 11px;
}

.repo-log-time {
  color: var(--muted);
  font-family: Consolas, "Courier New", monospace;
  font-size: 10.5px;
}

.repo-log-sheet {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  vertical-align: bottom;
  color: var(--ink-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-log-head b {
  color: var(--accent);
  font-size: 11px;
  font-weight: 500;
}

/* 表已被删掉：名字后面挂个小标签，说明这是历史记录 */
.repo-log-gone {
  display: inline-block;
  margin-left: 5px;
  padding: 0 4px;
  border: 1px solid var(--line-soft);
  border-radius: 4px;
  color: var(--muted);
  font-size: 10px;
  line-height: 14px;
  vertical-align: bottom;
}

.repo-log-dot {
  color: #c8ccd4;
}

.repo-logs li.warn .repo-log-head b {
  color: var(--warn);
}

.repo-logs li.ok .repo-log-head b {
  color: var(--ok);
}

.repo-logs p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
  word-break: break-word;
}

.repo-log-empty {
  padding: 32px 16px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}

/* 弹窗 */
.repo-modal {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(15 23 42 / 38%);
}

.repo-modal-card {
  width: 100%;
  max-width: 420px;
  padding: 18px 20px 16px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 12px 40px rgb(0 0 0 / 18%);
}

.repo-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.repo-modal-head b {
  font-size: 14px;
  font-weight: 600;
}

.repo-icon {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: none;
  color: var(--muted);
}

.repo-icon:hover {
  background: #f2f3f5;
  color: var(--ink);
}

.repo-modal-hint {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.repo-err {
  margin: 10px 0 0;
  color: var(--danger);
  font-size: 12px;
}

.repo-modal-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 1080px) {
  .repo-layout {
    grid-template-columns: 1fr;
  }

  .repo-side {
    position: static;
  }

  .repo-logs {
    max-height: 360px;
  }
}

@media (max-width: 720px) {
  .repo-metrics {
    grid-template-columns: 1fr;
  }

  .repo-col-origin {
    display: none;
  }
}
</style>
