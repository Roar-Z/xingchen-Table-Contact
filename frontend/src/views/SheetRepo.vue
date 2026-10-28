<script setup>
/**
 * 我的仓库 —— 表格文件清单
 *
 * 顶部右侧的「我的仓库」入口点进来：按名称、列名搜索，打开、下载、重命名、删除，
 * 右边一列是所有表格的操作日志，谁在什么时候改了什么一眼能看到。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import { api, isNetworkError } from '../api'
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
const logs = ref([])
const logsLoading = ref(true)
const logsError = ref('')

const dialog = reactive({
  open: false,
  kind: '',
  sheetId: '',
  name: '',
  busy: false,
  error: ''
})

let searchTimer = null

const summary = computed(() => {
  const rows = tables.value.reduce((sum, item) => sum + (Number(item.row_count) || 0), 0)
  const revisions = tables.value.reduce((sum, item) => sum + (Number(item.revision_count) || 0), 0)
  return [
    { label: '表格', value: tables.value.length, unit: '张' },
    { label: '数据行', value: rows, unit: '行' },
    { label: '修改记录', value: revisions, unit: '次' }
  ]
})

function originText(origin) {
  return ({ upload: '上传', template: '模板', nl: '一句话新建', merge: '多表融合' })[origin] || '表格'
}

function fmtTime(value) {
  const text = String(value || '')
  // 列表里只显示到分钟，年份占位置又没什么信息量
  return text.length > 16 ? text.slice(5, 16) : text
}

async function loadTables(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    tables.value = await api.sheets({ q: keyword.value.trim() })
  } catch (err) {
    error.value = isNetworkError(err) ? '后端没有连上，先启动 backend/start.cmd' : err.message
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  logsError.value = ''
  try {
    logs.value = await api.sheetLogs(80)
  } catch (err) {
    logsError.value = err.message
  } finally {
    logsLoading.value = false
  }
}

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadTables(true), 220)
}

function resetSearch() {
  keyword.value = ''
  loadTables()
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
    await loadTables(true)
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
    await loadTables(true)
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
            <div class="repo-head-actions">
              <span class="panel-sub">{{ tables.length }} 张表</span>
              <button class="btn btn-sm" @click="loadTables()">
                <Icon name="refresh" :size="13" />刷新
              </button>
              <router-link class="btn btn-sm btn-primary" to="/sheets">
                <Icon name="plus" :size="13" />去表格智作
              </router-link>
            </div>
          </div>

          <div v-if="error" class="banner banner-err repo-banner">
            {{ error }}
            <button class="btn btn-sm" @click="loadTables()">重试</button>
          </div>

          <div v-if="loading" class="empty">正在读取仓库…</div>

          <div v-else-if="!tables.length" class="empty">
            <span class="empty-icon"><Icon name="folder" :size="18" /></span>
            <b>{{ keyword ? '没有匹配的表格' : '仓库还是空的' }}</b>
            <p>{{ keyword ? '换个关键字，或清空搜索看看全部表格。' : '去表格智作上传一个 Excel，或用模板建一张。' }}</p>
            <router-link class="btn btn-primary" to="/sheets">打开表格智作</router-link>
          </div>

          <table v-else class="data-table repo-table">
            <thead>
              <tr>
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
              <tr v-for="item in tables" :key="item.id" class="row-link" @click="openSheet(item)">
                <td class="repo-col-name">
                  <span class="repo-name">{{ item.name }}</span>
                  <span class="repo-note">{{ item.note }}</span>
                  <span class="task-id">{{ item.id }}</span>
                </td>
                <td class="repo-col-origin">
                  <span class="tag" :class="item.origin === 'merge' ? 'tag-blue' : 'tag-neutral'">
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
        </div>
      </section>

      <aside class="repo-side">
        <div class="panel">
          <div class="panel-head">
            <h2>操作日志</h2>
            <button class="btn-text" :disabled="logsLoading" @click="loadLogs">刷新</button>
          </div>
          <div v-if="logsError" class="banner banner-err repo-banner">{{ logsError }}</div>
          <div v-if="logsLoading && !logs.length" class="repo-log-empty">正在读取日志…</div>
          <div v-else-if="!logs.length" class="repo-log-empty">还没有操作记录。</div>
          <ul v-else class="repo-logs">
            <li v-for="(log, index) in logs" :key="index" :class="log.level">
              <div class="repo-log-head">
                <span class="repo-log-time">{{ fmtTime(log.time) }}</span>
                <span class="repo-log-sheet">{{ log.sheet_name }}</span>
                <b>{{ log.action }}</b>
              </div>
              <p>{{ log.detail }}</p>
            </li>
          </ul>
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

.repo-banner {
  margin: 12px 18px 0;
}

.repo-table {
  table-layout: fixed;
  font-size: 12.5px;
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

.repo-side {
  position: sticky;
  top: 96px;
}

.repo-side .panel {
  margin-bottom: 0;
}

.repo-logs {
  max-height: calc(100vh - 200px);
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.repo-logs li {
  padding: 9px 16px;
  border-bottom: 1px solid var(--line-soft);
}

.repo-logs li:last-child {
  border-bottom: none;
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
  max-width: 140px;
  overflow: hidden;
  color: var(--ink-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-log-head b {
  color: var(--accent);
  font-size: 11px;
  font-weight: 500;
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
