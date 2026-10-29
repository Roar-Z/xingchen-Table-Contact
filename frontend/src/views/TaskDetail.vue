<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import ResultTable from '../components/ResultTable.vue'
import StatusPill from '../components/StatusPill.vue'
import { api, isNetworkError } from '../api'
import { clearNotice, showNotice } from '../appNotice'
import { useToast } from '../toast'

const route = useRoute()
const router = useRouter()
const { show } = useToast()

const task = ref(null)
const loading = ref(true)
const error = ref('')
const activeTab = ref('preview')
const reviewing = ref(false)
const rerunning = ref(false)
const deleting = ref(false)
let timer = null

/* 结果表的自然语言修改：每句话存成一条修改记录，重算时一起重放 */
const editInput = ref('')
const editBusy = ref(false)
const editError = ref('')
// 点「试试」只填词，顺带把焦点放到输入框
const editInputRef = ref(null)

const editLog = computed(() => task.value?.config?.edits || [])
const editChanged = computed(() =>
  editLog.value.reduce((sum, item) => sum + (Number(item.changed_rows) || 0), 0)
)
const editSamples = computed(() => {
  const columns = task.value?.output_columns || []
  if (!columns.length) return []
  const first = columns[0]
  const numeric = columns.find((name) => /小时|时长|数量|金额|天数/.test(name)) || columns[columns.length - 1]
  return [
    `第 2 行的${numeric}改成 8`,
    `把「${first}」为空的记录删掉`,
    `按${numeric}降序排序`
  ]
})
const taskId = computed(() => route.params.id)
const running = computed(() => task.value && ['queued', 'running'].includes(task.value.status))
// 重算期间保持入口可见（按钮显示“改写中”），避免面板一闪一闪
const canEdit = computed(() => {
  if (!task.value) return false
  if (editBusy.value) return true
  return !running.value && ['done', 'warning'].includes(task.value.status)
})
const lastLog = computed(() => {
  const logs = task.value?.logs || []
  return logs.length ? logs[logs.length - 1].message : '处理中'
})

const agentSteps = computed(() => task.value?.agent_steps || [])
const agentRepaired = computed(() => !!task.value?.config?.agent_repaired)
const learnedExperience = computed(() =>
  agentSteps.value.some((item) => item.tool === 'learn_experience')
)

const AGENT_PHASES = {
  inspect: '读取',
  plan: '规划',
  recall: '记忆',
  tool: '工具',
  reflect: '校验',
  fix: '自修',
  handoff: '用户确认'
}

const AGENT_STATUS = {
  ok: '完成',
  warn: '需关注',
  fail: '失败',
  skip: '跳过'
}

function phaseLabel(phase) {
  return AGENT_PHASES[phase] || '执行'
}

function stepStatusLabel(status) {
  return AGENT_STATUS[status] || '完成'
}

function stepTime(ms) {
  if (!ms) return ''
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

const primaryArtifact = computed(() => {
  const artifacts = task.value?.artifacts || []
  return artifacts.find((item) => item.name.toLowerCase().endsWith('.xls')) || artifacts[0]
})

const metricItems = computed(() => {
  if (!task.value) return []
  const defs = task.value.scene === 'leave'
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

function formatNumber(value) {
  if (typeof value === 'number') {
    return Number.isInteger(value) ? value.toLocaleString('zh-CN') : value.toFixed(1)
  }
  return value === null || value === undefined || value === '' ? '—' : value
}

function durationText(ms) {
  if (!ms) return '—'
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)} 分钟`
  return `${(ms / 1000).toFixed(1)} 秒`
}

function downloadUrl(name) {
  return api.downloadUrl(task.value.id, name)
}

function stopPolling() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function schedule() {
  stopPolling()
  timer = setTimeout(() => load(false), 1200)
}

async function load(initial = false) {
  if (initial) loading.value = true
  try {
    const data = await api.task(taskId.value)
    task.value = data
    error.value = ''
    clearNotice()
    if (['queued', 'running'].includes(data.status)) schedule()
    else stopPolling()
  } catch (err) {
    error.value = isNetworkError(err)
      ? '暂时连不上服务，请稍后刷新页面重试。'
      : err.message
    showNotice(error.value, () => load())
    stopPolling()
  } finally {
    if (initial) loading.value = false
  }
}

async function confirmReview() {
  if (!task.value || reviewing.value) return
  reviewing.value = true
  try {
    task.value = await api.confirmReview(task.value.id)
    show('已人工复核，任务标记为已完成')
  } catch (err) {
    show(err.message, 'err')
  } finally {
    reviewing.value = false
  }
}

async function rerunTask() {
  if (!task.value || rerunning.value || deleting.value) return
  rerunning.value = true
  try {
    task.value = await api.rerunTask(task.value.id)
    activeTab.value = 'preview'
    show('已重新排队执行，完成后状态会自动更新')
    schedule()
  } catch (err) {
    show(err.message, 'err')
  } finally {
    rerunning.value = false
  }
}

/**
 * 一句话改结果：后端把话翻译成操作（改值 / 加减 / 删行 / 排序 / 加列），
 * 再按原源表重算、重放、重新导出，页面只负责展示结果和修改记录。
 */
// 「试试」里的句子只填进输入框，不直接发给模型
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
    activeTab.value = 'preview'
    show(res.revision?.summary ? `已按你的话改结果：${res.revision.summary}` : '已按你的话改结果，正在重算')
    await waitIdle()
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
    await waitIdle()
  } catch (err) {
    editError.value = err.message
    show(err.message, 'err')
  } finally {
    editBusy.value = false
  }
}

// 修改会触发整体重算，这里等到任务不再是 queued/running 再放开输入框
async function waitIdle() {
  for (let i = 0; i < 120; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 800))
    const data = await api.task(taskId.value)
    task.value = data
    if (!['queued', 'running'].includes(data.status)) return
  }
}

async function removeTask() {
  if (!task.value || deleting.value || rerunning.value) return
  if (!window.confirm(`确定删除任务「${task.value.name}」？产出文件会一起清理。`)) return
  deleting.value = true
  try {
    await api.deleteTasks([task.value.id])
    show('任务已删除')
    stopPolling()
    router.push('/tasks')
  } catch (err) {
    show(err.message, 'err')
  } finally {
    deleting.value = false
  }
}

watch(
  () => route.params.id,
  () => {
    stopPolling()
    task.value = null
    load(true)
  }
)

onMounted(() => load(true))
onUnmounted(stopPolling)
</script>

<template>
  <div class="page">
    <div v-if="loading" class="empty">正在加载任务…</div>

    <template v-else>
      <div class="detail-head">
        <div>
          <div class="detail-title">
            <h1>{{ task?.name || '任务详情' }}</h1>
            <StatusPill v-if="task" :status="task.status" />
          </div>
          <div v-if="task" class="detail-meta">
            <span class="mono">{{ task.id }}</span>
            <span>{{ task.scene_name }}</span>
            <span>{{ task.created_at }}</span>
          </div>
        </div>
        <div class="page-actions">
          <button class="btn" @click="router.push('/tasks')">返回列表</button>
          <button
            v-if="task && !running"
            class="btn"
            :disabled="rerunning || deleting"
            @click="rerunTask"
          >
            <Icon name="refresh" :size="14" />
            {{ rerunning ? '排队中…' : '重新执行' }}
          </button>
          <button
            v-if="task && task.status === 'warning'"
            class="btn"
            :disabled="reviewing || rerunning"
            @click="confirmReview"
          >
            <Icon name="check" :size="14" />
            {{ reviewing ? '确认中…' : '确认复核' }}
          </button>
          <a
            v-if="primaryArtifact"
            class="btn btn-primary"
            :href="downloadUrl(primaryArtifact.name)"
          >
            <Icon name="download" :size="14" />
            下载导入表
          </a>
        </div>
      </div>

      <!-- 报错统一显示在顶栏下方那条全局提示里 -->

      <template v-if="task">
        <section v-if="running" class="panel">
          <div class="progress-panel">
            <span class="progress-text">{{ task.progress }}%</span>
            <div class="progress-track">
              <div class="progress-bar" :style="{ width: `${task.progress}%` }" />
            </div>
            <span class="progress-text">{{ lastLog }}</span>
          </div>
        </section>

        <div v-if="task.status === 'failed'" class="banner banner-err">
          {{ task.error || '任务执行失败，没有生成结果文件。' }}
        </div>

        <div v-if="task.status === 'warning'" class="banner banner-warn">
          本次有 {{ task.anomaly_rows }} 行被隔离到异常日志，结果文件已照常生成。核对异常行确认无误后，可以点右上角「确认复核」标记为已完成。
        </div>

        <div v-if="metricItems.length" class="metrics">
          <div v-for="item in metricItems" :key="item.key" class="metric" :class="{ warn: item.warn }">
            <div class="metric-label">{{ item.label }}</div>
            <div class="metric-value">{{ item.value }}<span class="metric-unit">{{ item.unit }}</span></div>
          </div>
        </div>

        <div class="detail-grid">
          <div>
            <section class="panel">
              <div class="panel-head">
                <h2>处理结果</h2>
                <div class="tabs">
                  <button class="tab" :class="{ on: activeTab === 'preview' }" @click="activeTab = 'preview'">
                    结果表
                  </button>
                  <button class="tab" :class="{ on: activeTab === 'anomalies' }" @click="activeTab = 'anomalies'">
                    异常日志<template v-if="task.anomaly_rows">（{{ task.anomaly_rows }}）</template>
                  </button>
                </div>
              </div>

              <template v-if="activeTab === 'preview'">
                <ResultTable
                  v-if="task && task.output_columns && task.output_columns.length && !running"
                  :task-id="task.id"
                  :columns="task.output_columns"
                  :refresh-key="editLog.length"
                />
                <div v-else-if="running" class="empty">
                  <b>正在处理</b>
                  <p>任务完成后会自动刷新，结果表支持搜索和翻页。</p>
                </div>
                <div v-else class="empty">
                  <b>没有可预览的结果</b>
                  <p>可以到异常日志里看具体原因。</p>
                </div>
              </template>

              <template v-else>
                <div v-if="task.anomalies && task.anomalies.length" class="panel-body flush table-scroll">
                  <table class="data-table">
                    <thead>
                      <tr>
                        <th>源文件</th>
                        <th>行号</th>
                        <th>类型</th>
                        <th>原因</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(item, index) in task.anomalies" :key="index">
                        <td class="muted">{{ item.source }}</td>
                        <td class="mono">{{ item.row }}</td>
                        <td><span class="tag tag-amber">{{ item.type }}</span></td>
                        <td>{{ item.reason }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div v-else class="empty">
                  <b>没有异常行</b>
                  <p>这张表可以直接交给 HR 导入。</p>
                </div>
              </template>
            </section>

            <section v-if="canEdit" class="panel">
              <div class="panel-head">
                <div>
                  <h2>一句话改结果</h2>
                  <p class="panel-sub">说人话就行，智能体自己识别要改哪一行、哪一列，改完按源表重算并重新导出</p>
                </div>
                <div class="edit-meta">
                  <span v-if="editLog.length" class="muted">
                    已改 {{ editLog.length }} 处 · 影响 {{ editChanged }} 行
                  </span>
                  <button v-if="editLog.length" class="btn btn-sm" :disabled="editBusy" @click="undoEdit">
                    <Icon name="left" :size="12" />
                    撤销最近一条
                  </button>
                </div>
              </div>
              <div class="panel-body">
                <div v-if="editLog.length" class="edit-log">
                  <span v-for="(item, index) in editLog" :key="item.id || index" class="edit-chip">
                    <em>{{ index + 1 }}</em>{{ item.summary || item.instruction }}
                  </span>
                </div>

                <div class="edit-compose">
                  <textarea
                    ref="editInputRef"
                    v-model="editInput"
                    class="textarea edit-input"
                    :disabled="editBusy"
                    placeholder="比如：第 3 行起始时间改成 09:00 / 姓名为张三的行删掉 / 合计小时全部加 1 / 按合计小时降序排序"
                    @keydown.enter.exact.prevent="submitEdit()"
                  />
                  <button class="btn btn-primary" :disabled="editBusy || !editInput.trim()" @click="submitEdit()">
                    <Icon name="spark" :size="14" />
                    {{ editBusy ? '智能体改写中…' : '让智能体改' }}
                  </button>
                </div>

                <div v-if="editSamples.length" class="edit-samples">
                  <span class="muted">试试：</span>
                  <button
                    v-for="text in editSamples"
                    :key="text"
                    class="btn btn-sm"
                    :disabled="editBusy"
                    title="点一下填进输入框，改完再点「让智能体改」"
                    @click="useEditSample(text)"
                  >
                    {{ text }}
                  </button>
                </div>

                <p v-if="editError" class="edit-err">{{ editError }}</p>
                <p class="edit-note">
                  每句话都会存成一条修改记录，跟着这套源表一起重算，下载的 Excel 和上面的预览是同一份；
                  修改有先后顺序（先排序再改第 3 行就不一样了），所以只能从最近一条往回撤。
                </p>
              </div>
            </section>

            <section v-if="task.warnings && task.warnings.length" class="panel">
              <div class="panel-head"><h2>处理提示</h2></div>
              <div class="panel-body">
                <ul class="notice-list">
                  <li v-for="(item, index) in task.warnings" :key="index">{{ item }}</li>
                </ul>
              </div>
            </section>

            <section v-if="agentSteps.length" class="panel">
              <div class="panel-head">
                <h2>智能体执行轨迹</h2>
                <span class="agent-badges">
                  <span v-if="agentRepaired" class="tag tag-amber">执行期自愈</span>
                  <span v-if="learnedExperience" class="tag tag-blue">已写入经验库</span>
                </span>
              </div>
              <div class="panel-body">
                <ol class="agent-timeline">
                  <li v-for="item in agentSteps" :key="item.index" class="agent-step" :class="`is-${item.status || 'ok'}`">
                    <span class="agent-dot">{{ item.index }}</span>
                    <div class="agent-main">
                      <div class="agent-step-head">
                        <b>{{ item.title }}</b>
                        <span class="agent-phase">{{ phaseLabel(item.phase) }}</span>
                        <code class="agent-tool">{{ item.tool }}</code>
                        <em v-if="item.elapsed_ms" class="agent-time">{{ stepTime(item.elapsed_ms) }}</em>
                        <span class="agent-status">{{ stepStatusLabel(item.status) }}</span>
                      </div>
                      <p v-if="item.detail" class="agent-detail">{{ item.detail }}</p>
                      <ul v-if="item.items && item.items.length" class="agent-items">
                        <li v-for="text in item.items" :key="text">{{ text }}</li>
                      </ul>
                    </div>
                  </li>
                </ol>
              </div>
            </section>
          </div>

          <aside class="detail-side">
            <section class="panel">
              <div class="panel-head"><h2>产出文件</h2></div>
              <div v-if="task.artifacts && task.artifacts.length" class="panel-body flush">
                <a
                  v-for="item in task.artifacts"
                  :key="item.name"
                  class="artifact"
                  :href="downloadUrl(item.name)"
                >
                  <span class="artifact-icon"><Icon name="download" :size="14" /></span>
                  <span class="artifact-main">
                    <span class="artifact-name">{{ item.name }}</span>
                    <span class="artifact-label">{{ item.label }}</span>
                  </span>
                  <span class="artifact-size">{{ item.size }}</span>
                </a>
              </div>
              <div v-else class="empty">
                <b>{{ running ? '正在生成' : '没有产出文件' }}</b>
                <p>{{ running ? '任务完成后这里会出现下载链接。' : '请检查异常日志。' }}</p>
              </div>
            </section>

            <section class="panel">
              <div class="panel-head"><h2>任务信息</h2></div>
              <dl class="info-list">
                <div class="info-row">
                  <dt>目标模板</dt>
                  <dd>{{ task.target || '—' }}</dd>
                </div>
                <div class="info-row">
                  <dt>耗时</dt>
                  <dd>{{ durationText(task.duration_ms) }}</dd>
                </div>
                <div class="info-row">
                  <dt>作业要求</dt>
                  <dd>{{ task.brief || '—' }}</dd>
                </div>
              </dl>
              <div v-if="task.source_files && task.source_files.length" class="source-list">
                <div class="source-list-title">源文件</div>
                <div v-for="file in task.source_files" :key="file.file_id" class="source-item">
                  <span class="source-name">{{ file.name }}</span>
                  <span class="source-meta">{{ file.role }} · {{ file.rows }} 行</span>
                </div>
              </div>
              <div class="info-actions">
                <button
                  class="btn btn-sm btn-danger"
                  :disabled="deleting || rerunning"
                  @click="removeTask"
                >
                  <Icon name="trash" :size="13" />
                  {{ deleting ? '删除中…' : '删除任务' }}
                </button>
              </div>
            </section>
          </aside>
        </div>
      </template>

      <div v-else-if="!error" class="empty">
        <b>没有找到这个任务</b>
        <p>任务编号可能写错了。</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.agent-badges {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-left: auto;
}

.agent-timeline {
  position: relative;
  margin: 0;
  padding: 0;
  list-style: none;
}

.agent-timeline::before {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 10px;
  width: 1px;
  background: #e3e9f4;
  content: '';
}

.agent-step {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 6px 0 10px;
}

.agent-dot {
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

.agent-step.is-warn .agent-dot {
  border-color: #f0ddb8;
  background: #fff7e8;
  color: #b7701a;
}

.agent-step.is-fail .agent-dot {
  border-color: #f0cece;
  background: #fff1f1;
  color: #c43f3f;
}

.agent-step.is-skip .agent-dot {
  border-color: #e2e5ea;
  background: #f5f6f8;
  color: #9aa0a8;
}

.agent-main {
  flex: 1;
  min-width: 0;
  padding-top: 1px;
}

.agent-step-head {
  display: flex;
  gap: 7px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 12px;
}

.agent-step-head b {
  color: #1f2329;
  font-size: 13px;
  font-weight: 600;
}

.agent-phase {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f2f5fb;
  color: #5b6475;
  font-size: 11px;
}

.agent-tool {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef2ff;
  color: #3652c8;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.agent-time {
  color: #9aa0a8;
  font-size: 11px;
  font-style: normal;
}

.agent-status {
  margin-left: auto;
  color: #1f7a4d;
  font-size: 11px;
}

.agent-step.is-warn .agent-status {
  color: #b7701a;
}

.agent-step.is-fail .agent-status {
  color: #c43f3f;
}

.agent-step.is-skip .agent-status {
  color: #9aa0a8;
}

.agent-detail {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.65;
}

.agent-items {
  margin: 4px 0 0;
  padding-left: 15px;
  color: #8b919b;
  font-size: 12px;
  line-height: 1.7;
}

/* 一句话改结果 */
.edit-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  color: var(--muted);
  font-size: 12px;
}

.edit-log {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.edit-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 100%;
  padding: 4px 9px 4px 5px;
  border: 1px solid #dbe4f7;
  border-radius: 999px;
  background: var(--accent-soft);
  color: #3652c8;
  font-size: 12px;
  line-height: 1.5;
}

.edit-chip em {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  color: var(--accent);
  font-size: 10px;
  font-style: normal;
}

.edit-compose {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.edit-input {
  min-height: 54px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.edit-compose .btn-primary {
  flex: none;
  height: 40px;
}

.edit-samples {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 10px;
  font-size: 12px;
}

.edit-samples .btn {
  color: var(--ink-2);
  background: #fafbfc;
}

.edit-err {
  margin: 10px 0 0;
  color: var(--danger);
  font-size: 12px;
}

.edit-note {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
