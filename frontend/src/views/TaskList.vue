<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import { api, isNetworkError } from '../api'
import { useToast } from '../toast'

const router = useRouter()
const { show } = useToast()

const loading = ref(true)
const error = ref('')
const data = ref(null)
const status = ref('all')
const keyword = ref('')
const page = ref(1)
const pageSize = 12
const busyId = ref('')
const deleting = ref(false)
const selected = ref([])

const tabs = computed(() => {
  const counts = data.value?.counts || {}
  return [
    { key: 'all', label: '全部', count: counts.all || 0 },
    { key: 'running', label: '进行中', count: counts.running || 0 },
    { key: 'done', label: '已完成', count: counts.done || 0 },
    { key: 'warning', label: '待复核', count: counts.warning || 0 },
    { key: 'failed', label: '失败', count: counts.failed || 0 }
  ]
})

const items = computed(() => data.value?.items || [])
const total = computed(() => data.value?.total || 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const allSelected = computed(
  () => items.value.length > 0 && items.value.every((item) => selected.value.includes(item.id))
)
const someSelected = computed(
  () => !allSelected.value && items.value.some((item) => selected.value.includes(item.id))
)

function isRunning(item) {
  return ['queued', 'running'].includes(item.status)
}

function toggle(item) {
  if (selected.value.includes(item.id)) {
    selected.value = selected.value.filter((id) => id !== item.id)
  } else {
    selected.value = [...selected.value, item.id]
  }
}

function toggleAll() {
  selected.value = allSelected.value ? [] : items.value.filter((item) => !isRunning(item)).map((item) => item.id)
}

function clearSelection() {
  selected.value = []
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.tasks({
      status: status.value === 'all' ? '' : status.value,
      q: keyword.value.trim(),
      page: page.value,
      page_size: pageSize
    })
    const visible = new Set((data.value?.items || []).map((item) => item.id))
    selected.value = selected.value.filter((id) => visible.has(id))
  } catch (err) {
    error.value = isNetworkError(err)
      ? '后端没有连上，先在 backend 目录运行 start.cmd，再刷新页面。'
      : err.message
    data.value = null
  } finally {
    loading.value = false
  }
}

function setStatus(key) {
  if (status.value === key) return
  status.value = key
  page.value = 1
  clearSelection()
  load()
}

function search() {
  page.value = 1
  clearSelection()
  load()
}

function resetSearch() {
  keyword.value = ''
  page.value = 1
  clearSelection()
  load()
}

function goPage(next) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  clearSelection()
  load()
}

function openTask(item) {
  router.push({ name: 'task-detail', params: { id: item.id } })
}

async function confirmReview(item) {
  if (busyId.value) return
  busyId.value = item.id
  try {
    await api.confirmReview(item.id)
    show(`任务 ${item.id} 已标记为已完成`)
    await load()
  } catch (err) {
    show(err.message, 'err')
  } finally {
    busyId.value = ''
  }
}

async function rerun(item) {
  if (busyId.value) return
  busyId.value = item.id
  try {
    await api.rerunTask(item.id)
    show(`任务 ${item.id} 已重新排队执行`)
    await load()
  } catch (err) {
    show(err.message, 'err')
  } finally {
    busyId.value = ''
  }
}

function removeOne(item) {
  removeTasks([item.id], `确定删除任务「${item.name}」？产出文件会一起清理。`)
}

function removeSelected() {
  removeTasks(selected.value, `确定删除选中的 ${selected.value.length} 个任务？产出文件会一起清理。`)
}

async function removeTasks(ids, message) {
  if (!ids.length || deleting.value) return
  if (!window.confirm(message)) return
  deleting.value = true
  try {
    await api.deleteTasks(ids)
    show(`已删除 ${ids.length} 个任务`)
    clearSelection()
    await load()
    if (!items.value.length && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (err) {
    show(err.message, 'err')
  } finally {
    deleting.value = false
  }
}

function durationText(ms) {
  if (!ms) return '—'
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)} 分钟`
  return `${(ms / 1000).toFixed(1)} 秒`
}

function timeText(value) {
  return value ? value.slice(0, 16) : '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">任务记录</h1>
        <p class="page-desc">每一次任务都会保留源文件、处理结果、异常日志和两份导入表。</p>
      </div>
      <div class="page-actions">
        <button class="btn btn-primary" @click="router.push('/')">
          <Icon name="plus" :size="14" />
          新建任务
        </button>
      </div>
    </div>

    <div class="filter-bar">
      <div class="filter-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="filter-tab"
          :class="{ on: status === tab.key }"
          @click="setStatus(tab.key)"
        >
          {{ tab.label }}<span>{{ tab.count }}</span>
        </button>
      </div>
      <form class="search-box" @submit.prevent="search">
        <Icon name="search" :size="14" />
        <input v-model="keyword" placeholder="任务名称或编号" />
        <button v-if="keyword" class="icon-btn" type="button" title="清空" @click="resetSearch">
          <Icon name="close" :size="13" />
        </button>
        <button class="search-btn" type="submit">搜索</button>
      </form>
    </div>

    <div v-if="error" class="banner banner-err">
      {{ error }}
      <button class="btn btn-sm" @click="load">重试</button>
    </div>

    <div v-if="selected.length" class="bulk-bar">
      <span>已选 {{ selected.length }} 项</span>
      <button class="btn-text" @click="clearSelection">取消选择</button>
      <span class="spacer" />
      <button class="btn btn-sm btn-danger" :disabled="deleting" @click="removeSelected">
        {{ deleting ? '删除中…' : '删除所选' }}
      </button>
    </div>

    <section class="panel">
      <div class="panel-body flush">
        <div v-if="loading && !items.length" class="empty">正在加载任务…</div>

        <div v-else-if="!items.length" class="empty">
          <span class="empty-icon"><Icon name="history" :size="18" /></span>
          <b>暂无任务</b>
          <p>{{ keyword || status !== 'all' ? '换个筛选条件试试。' : '新建一个任务，处理完会出现在这里。' }}</p>
        </div>

        <table v-else class="data-table task-table">
          <thead>
            <tr>
              <th class="check-col">
                <input
                  type="checkbox"
                  title="全选当前页"
                  :checked="allSelected"
                  :indeterminate.prop="someSelected"
                  :disabled="!items.length"
                  @change="toggleAll"
                />
              </th>
              <th>任务</th>
              <th>场景</th>
              <th>输入 / 输出</th>
              <th>异常</th>
              <th>状态</th>
              <th>耗时</th>
              <th>创建时间</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id" class="row-link" @click="openTask(item)">
              <td class="check-col" @click.stop>
                <input
                  type="checkbox"
                  :checked="selected.includes(item.id)"
                  :disabled="isRunning(item)"
                  :title="isRunning(item) ? '执行中的任务不能删除' : ''"
                  @change="toggle(item)"
                />
              </td>
              <td>
                <div class="task-name">{{ item.name }}</div>
                <div class="task-id">{{ item.id }}</div>
              </td>
              <td>
                <span class="tag" :class="item.scene === 'leave' ? 'tag-blue' : 'tag-neutral'">
                  {{ item.scene_name }}
                </span>
              </td>
              <td class="mono">{{ item.total_rows }} → {{ item.output_rows }}</td>
              <td>
                <span v-if="item.anomaly_rows" class="tag tag-amber">{{ item.anomaly_rows }} 行</span>
                <span v-else class="muted">—</span>
              </td>
              <td><StatusPill :status="item.status" /></td>
              <td class="muted">{{ durationText(item.duration_ms) }}</td>
              <td class="muted">{{ timeText(item.created_at) }}</td>
              <td class="right">
                <span class="task-actions">
                  <button class="btn-text" @click.stop="openTask(item)">查看</button>
                  <button
                    v-if="!isRunning(item)"
                    class="btn-text"
                    :disabled="busyId === item.id"
                    @click.stop="rerun(item)"
                  >
                    重新执行
                  </button>
                  <button
                    v-if="item.status === 'warning'"
                    class="btn-text"
                    :disabled="busyId === item.id"
                    @click.stop="confirmReview(item)"
                  >
                    确认复核
                  </button>
                  <button
                    v-if="!isRunning(item)"
                    class="btn-text btn-text-danger"
                    :disabled="deleting"
                    @click.stop="removeOne(item)"
                  >
                    删除
                  </button>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="!loading && total > pageSize" class="pager">
      <span>共 {{ total }} 条</span>
      <button class="btn btn-sm" :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
      <span>{{ page }} / {{ totalPages }}</span>
      <button class="btn btn-sm" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</button>
    </div>
  </div>
</template>
