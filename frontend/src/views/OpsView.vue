<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api'
import { clearNotice, showNotice } from '../appNotice'
import { clearTopbar, useTopbar } from '../topbar'

useTopbar({
  title: '运行观测',
  desc: '智能体的运行次数、成功率、兜底比例、token 消耗、工具注册表和审计留痕。没有接外部监控，单机也能给出可核对的数字。'
})

onBeforeUnmount(() => clearTopbar())

const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const stats = ref(null)
const checks = ref([])
const score = ref(0)
const tools = ref([])
const generatedAt = ref('')
let timer = null

// 骨架屏的四个面板：行数按各自真实内容量给，数据回来时高度不会突然缩一截
const skPanels = [
  { key: 'checks', rows: 3 },
  { key: 'tools', rows: 2 },
  { key: 'runs', rows: 6 },
  { key: 'audits', rows: 4 }
]

/* ---------------- 分页：运行记录与审计日志都按页向服务端取，本地各留 100 条 ---------------- */
const RUN_PAGE_SIZE = 10
const AUDIT_PAGE_SIZE = 20

const runs = ref([])
const runPage = ref(1)
const runPages = ref(1)
const runTotal = ref(0)
const runKeep = ref(100)
const runsLoading = ref(false)

const audits = ref([])
const auditPage = ref(1)
const auditPages = ref(1)
const auditTotal = ref(0)
const auditKeep = ref(100)
const auditsLoading = ref(false)

const kpis = computed(() => {
  const s = stats.value || {}
  return [
    { label: '配置编排次数', value: s.total || 0, unit: '次' },
    { label: '成功率', value: s.success_rate || 0, unit: '%', tone: (s.success_rate || 0) < 90 && (s.total || 0) > 0 ? 'warn' : '' },
    { label: '规则兜底率', value: s.fallback_rate || 0, unit: '%', tone: (s.fallback_rate || 0) > 30 ? 'warn' : '' },
    { label: '平均耗时', value: ((s.avg_duration_ms || 0) / 1000).toFixed(1), unit: 's' },
    { label: '累计 TOKEN', value: formatNumber((s.tokens_in || 0) + (s.tokens_out || 0)), unit: '' },
    { label: '单次平均 TOKEN', value: formatNumber(s.avg_tokens || 0), unit: '' },
    { label: '模型调用次数', value: s.model_calls || 0, unit: '次' },
    { label: '经验命中', value: s.experience_hits || 0, unit: '次' }
  ]
})

const recent = computed(() => runs.value)

const runPageList = computed(() => pageWindow(runPage.value, runPages.value))
const auditPageList = computed(() => pageWindow(auditPage.value, auditPages.value))

// 页码只显示当前页前后两页，最多 5 个，避免 10 页时把工具栏挤满
function pageWindow(current, pages) {
  const total = Math.max(pages, 1)
  const start = Math.max(1, Math.min(current - 2, total - 4))
  const end = Math.min(total, start + 4)
  const list = []
  for (let i = start; i <= end; i += 1) list.push(i)
  return list
}

const toolRows = computed(() =>
  tools.value.map((tool) => ({ ...tool, phaseLabel: phaseName(tool.phase) }))
)

const statusMeta = computed(() => {
  const map = { ok: ['通过', 'ok'], warn: ['待完善', 'warn'], fail: ['异常', 'fail'] }
  return (status) => map[status] || map.warn
})

function phaseName(phase) {
  return (
    {
      inspect: '读结构',
      plan: '定方案',
      recall: '查记忆',
      tool: '用工具',
      reflect: '做校验',
      fix: '自修复',
      handoff: '用户确认'
    }[phase] || '工具'
  )
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString('zh-CN')
}

function formatDuration(ms) {
  if (!ms) return '—'
  return `${(ms / 1000).toFixed(1)}s`
}

function modeLabel(run) {
  if (run.fallback) return '规则兜底'
  return run.mode === 'llm' ? '模型编排' : '规则链'
}

function actionLabel(action) {
  const map = {
    'agent.plan': '生成方案',
    'agent.plan.failed': '方案失败',
    'agent.experience.delete': '删除经验',
    'task.create': '创建任务',
    'task.review': '确认执行',
    'task.rerun': '重跑任务',
    'task.revise': '改结果',
    'task.undo-edit': '撤销修改',
    'task.delete': '删除任务'
  }
  return map[action] || action || '—'
}

// 后端全局用 SNAKE_CASE 序列化，运行记录字段也是 snake_case
function tokensOf(run) {
  const total = (run.tokens_in || 0) + (run.tokens_out || 0)
  return total ? formatNumber(total) : '—'
}

// silent = 15 秒的定时刷新：不把表格换成“正在读取”，否则列表每 15 秒闪一次
async function loadRuns(page = runPage.value, silent = false) {
  if (!silent) runsLoading.value = true
  try {
    const data = await api.agentRuns(page, RUN_PAGE_SIZE)
    runs.value = data?.items || []
    runPage.value = data?.page || 1
    runPages.value = data?.pages || 1
    runTotal.value = data?.total || 0
    runKeep.value = data?.keep || 100
  } catch (err) {
    error.value = err.message || '运行记录加载失败'
    showNotice(error.value, () => loadRuns())
  } finally {
    runsLoading.value = false
  }
}

async function loadAudits(page = auditPage.value, silent = false) {
  if (!silent) auditsLoading.value = true
  try {
    const data = await api.auditLogs(page, AUDIT_PAGE_SIZE)
    audits.value = data?.items || []
    auditPage.value = data?.page || 1
    auditPages.value = data?.pages || 1
    auditTotal.value = data?.total || 0
    auditKeep.value = data?.keep || 100
  } catch (err) {
    error.value = err.message || '审计日志加载失败'
    showNotice(error.value, () => loadAudits())
  } finally {
    auditsLoading.value = false
  }
}

function goRunPage(page) {
  if (page < 1 || page > runPages.value || page === runPage.value) return
  loadRuns(page)
}

function goAuditPage(page) {
  if (page < 1 || page > auditPages.value || page === auditPage.value) return
  loadAudits(page)
}

async function load(silent = false) {
  if (silent) {
    refreshing.value = true
  } else {
    loading.value = true
  }
  error.value = ''
  try {
    const [statsData, checkData, toolData] = await Promise.all([
      api.agentStats(),
      api.agentSelfCheck(),
      api.agentTools()
    ])
    stats.value = statsData
    checks.value = checkData.checks || []
    score.value = checkData.score || 0
    generatedAt.value = checkData.generated_at || ''
    tools.value = toolData || []
    // 列表各自分页取，刷新时停在当前页，不把用户翻到第 1 页
    await Promise.all([loadRuns(runPage.value, silent), loadAudits(auditPage.value, silent)])
    clearNotice()
  } catch (err) {
    error.value = err.message || '运行数据加载失败'
    showNotice(error.value, () => load())
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

onMounted(() => {
  load()
  timer = setInterval(() => load(true), 15000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="page ops">
    <!-- 首次进入先给整页骨架：不先摆一排 0% 的指标和空面板，数据回来再整体替换 -->
    <div v-if="loading" class="ops-skeleton" aria-busy="true">
      <div class="metrics ops-metrics">
        <div v-for="n in 8" :key="`sk-metric-${n}`" class="metric sk-metric">
          <span class="sk-line" style="width: 62%"></span>
          <span class="sk-line is-value" style="width: 44%"></span>
        </div>
      </div>
      <section v-for="panel in skPanels" :key="panel.key" class="panel sk-panel">
        <div class="panel-head sk-panel-head">
          <span class="sk-line" style="width: 108px"></span>
          <span class="sk-line" style="width: 64px"></span>
        </div>
        <div class="panel-body sk-panel-body" :style="{ minHeight: `${panel.rows * 26 + 18}px` }">
          <span
            v-for="r in panel.rows"
            :key="r"
            class="sk-line is-row"
            :style="{ width: r === panel.rows ? '58%' : '100%' }"
          ></span>
        </div>
      </section>
      <p class="sk-caption">正在汇总运行数据…</p>
    </div>

    <template v-else>
    <div class="metrics ops-metrics">
      <div v-for="item in kpis" :key="item.label" class="metric" :class="item.tone">
        <div class="metric-label">{{ item.label }}</div>
        <div class="metric-value">
          {{ item.value }}<span v-if="item.unit" class="metric-unit">{{ item.unit }}</span>
        </div>
      </div>
    </div>

    <!-- 报错统一显示在顶栏下方那条全局提示里 -->

    <section class="panel">
      <div class="panel-head">
        <h2>
          智能体自检
          <span class="ops-score" :class="{ warn: score < 80 }">健康度 {{ score }}%</span>
        </h2>
        <div class="ops-head-side">
          <span class="panel-sub">{{ generatedAt || '实时检查' }}</span>
          <button type="button" class="btn btn-sm ops-refresh" :disabled="refreshing" @click="load(true)">
            <Icon name="refresh" :size="13" />
            {{ refreshing ? '刷新中…' : '刷新' }}
          </button>
        </div>
      </div>
      <div class="panel-body">
        <div v-if="!checks.length" class="ops-empty">暂无自检结果</div>
        <ul v-else class="ops-checks">
          <li v-for="item in checks" :key="item.key" class="ops-check">
            <span class="ops-check-icon" :class="`is-${item.status}`">
              <Icon :name="item.status === 'ok' ? 'check' : 'alert'" :size="13" />
            </span>
            <div class="ops-check-main">
              <div class="ops-check-head">
                <b>{{ item.label }}</b>
                <span class="tag" :class="item.status === 'ok' ? 'tag-blue' : 'tag-amber'">
                  {{ statusMeta(item.status)[0] }}
                </span>
              </div>
              <p>{{ item.detail }}</p>
            </div>
          </li>
        </ul>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>工具注册表</h2>
        <span class="panel-sub">由 @Tool 注解实时反射，和模型可调用的一致</span>
      </div>
      <div class="panel-body">
        <div v-if="!toolRows.length" class="ops-empty">暂无工具注册信息</div>
        <div v-else class="ops-tools">
          <article v-for="tool in toolRows" :key="tool.name" class="ops-tool">
            <div class="ops-tool-head">
              <code>{{ tool.name }}</code>
              <span class="ops-phase">{{ tool.phaseLabel }}</span>
            </div>
            <p>{{ tool.description }}</p>
          </article>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>最近运行</h2>
        <span class="panel-sub">模型自主编排与规则兜底都计入统计；本地保留最近 {{ runKeep }} 条</span>
      </div>
      <div class="panel-body flush">
        <div v-if="runsLoading" class="ops-empty">正在读取运行记录…</div>
        <p v-else-if="!recent.length" class="ops-empty">还没有运行记录，去「新建任务」跑一次配置编排就会出现。</p>
        <table v-else class="ops-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>场景</th>
              <th>模式</th>
              <th>工具</th>
              <th>映射</th>
              <th>耗时</th>
              <th>TOKEN</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(run, index) in recent" :key="`${run.at}-${index}`">
              <td class="ops-mono">{{ run.at }}</td>
              <td>{{ run.scene_name || '—' }}</td>
              <td>
                <span class="tag" :class="run.fallback ? 'tag-amber' : run.mode === 'llm' ? 'tag-blue' : 'tag-neutral'">
                  {{ modeLabel(run) }}
                </span>
              </td>
              <td class="ops-mono">{{ run.tools.length }}</td>
              <td class="ops-mono">{{ run.mappings }}</td>
              <td class="ops-mono">{{ formatDuration(run.duration_ms) }}</td>
              <td class="ops-mono">{{ tokensOf(run) }}</td>
              <td>
                <span class="ops-dot" :class="run.status === 'ok' ? 'is-ok' : 'is-fail'"></span>
                {{ run.status === 'ok' ? '完成' : '失败' }}
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="runTotal > 0" class="ops-pager">
          <span class="ops-pager-sum">共 {{ runTotal }} 条 · 每页 {{ RUN_PAGE_SIZE }} 条</span>
          <div class="ops-pager-btns">
            <button type="button" class="ops-pager-btn" :disabled="runPage <= 1" @click="goRunPage(runPage - 1)">
              上一页
            </button>
            <button
              v-for="no in runPageList"
              :key="`run-page-${no}`"
              type="button"
              class="ops-pager-btn"
              :class="{ active: no === runPage }"
              @click="goRunPage(no)"
            >
              {{ no }}
            </button>
            <button type="button" class="ops-pager-btn" :disabled="runPage >= runPages" @click="goRunPage(runPage + 1)">
              下一页
            </button>
          </div>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>审计日志</h2>
        <span class="panel-sub">只追加不改写；操作人取 X-Operator，未接入时记来源；本地保留最近 {{ auditKeep }} 条</span>
      </div>
      <div class="panel-body flush">
        <div v-if="auditsLoading" class="ops-empty">正在读取审计日志…</div>
        <p v-else-if="!audits.length" class="ops-empty">还没有审计记录。</p>
        <ul v-else class="ops-audit">
          <li v-for="(item, index) in audits" :key="`${item.at}-${index}`">
            <span class="ops-mono">{{ item.at }}</span>
            <span class="ops-action">{{ actionLabel(item.action) }}</span>
            <span class="ops-target" :title="item.target">{{ item.target || '—' }}</span>
            <span class="ops-detail" :title="item.detail">{{ item.detail }}</span>
            <span class="ops-actor">{{ item.actor }}</span>
          </li>
        </ul>
        <div v-if="auditTotal > 0" class="ops-pager">
          <span class="ops-pager-sum">共 {{ auditTotal }} 条 · 每页 {{ AUDIT_PAGE_SIZE }} 条</span>
          <div class="ops-pager-btns">
            <button type="button" class="ops-pager-btn" :disabled="auditPage <= 1" @click="goAuditPage(auditPage - 1)">
              上一页
            </button>
            <button
              v-for="no in auditPageList"
              :key="`audit-page-${no}`"
              type="button"
              class="ops-pager-btn"
              :class="{ active: no === auditPage }"
              @click="goAuditPage(no)"
            >
              {{ no }}
            </button>
            <button type="button" class="ops-pager-btn" :disabled="auditPage >= auditPages" @click="goAuditPage(auditPage + 1)">
              下一页
            </button>
          </div>
        </div>
      </div>
    </section>
    </template>
  </div>
</template>

<style scoped>
.ops-metrics {
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
}

/* 首次进入的整页骨架：尺寸对齐真实卡片和面板，数据回来整体替换，
   不会先闪一排 0% 的指标和空表格 */
.sk-metric {
  display: flex;
  flex-direction: column;
  gap: 10px;
  justify-content: center;
}

.sk-line {
  display: block;
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef1f6 25%, #f8fafc 45%, #eef1f6 65%);
  background-size: 400% 100%;
  animation: sk-sweep 1.4s ease-in-out infinite;
}

.sk-line.is-value {
  height: 20px;
}

.sk-line.is-row {
  height: 14px;
}

.sk-panel-head {
  min-height: 46px;
}

.sk-panel-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sk-caption {
  margin: 0 0 16px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}

@keyframes sk-sweep {
  0% {
    background-position: 100% 50%;
  }

  100% {
    background-position: 0 50%;
  }
}

.ops-error {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 0 0 14px;
  padding: 9px 12px;
  border: 1px solid #f3d3cf;
  border-radius: 6px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 12px;
}

.ops-head-side {
  display: flex;
  gap: 10px;
  align-items: center;
}

.ops-refresh {
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
}

.ops-score {
  margin-left: 8px;
  color: var(--ok);
  font-size: 12px;
  font-weight: 500;
}

.ops-score.warn {
  color: var(--warn);
}

.ops-checks {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ops-check {
  display: flex;
  gap: 9px;
  padding: 11px 12px;
  border: 1px solid var(--line-soft);
  border-radius: 6px;
  background: #fbfcfd;
}

.ops-check-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  margin-top: 1px;
  border-radius: 50%;
}

.ops-check-icon.is-ok {
  background: var(--ok-soft);
  color: var(--ok);
}

.ops-check-icon.is-warn,
.ops-check-icon.is-fail {
  background: var(--warn-soft);
  color: var(--warn);
}

.ops-check-main {
  min-width: 0;
}

.ops-check-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ops-check-head b {
  font-size: 12.5px;
}

.ops-check-main p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
}

.ops-tools {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
}

.ops-tool {
  padding: 11px 12px;
  border: 1px solid var(--line-soft);
  border-radius: 6px;
}

.ops-tool-head {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.ops-tool code {
  color: var(--accent);
  font-family: Consolas, "Courier New", monospace;
  font-size: 12px;
}

.ops-phase {
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 10.5px;
}

.ops-tool p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.65;
}

.ops-empty {
  margin: 0;
  padding: 22px 18px;
  color: var(--muted);
  font-size: 12px;
}

.ops-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.ops-table th {
  padding: 8px 14px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--muted);
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}

.ops-table td {
  padding: 9px 14px;
  border-bottom: 1px solid var(--line-soft);
  vertical-align: middle;
}

.ops-table tbody tr:last-child td {
  border-bottom: none;
}

.ops-table tbody tr:hover {
  background: #fafbfc;
}

.ops-mono {
  color: var(--ink-2);
  font-family: Consolas, "Courier New", monospace;
  font-size: 11.5px;
  white-space: nowrap;
}

.ops-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 50%;
  vertical-align: middle;
}

.ops-dot.is-ok {
  background: var(--ok);
}

.ops-dot.is-fail {
  background: var(--danger);
}

.ops-audit {
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 分页条：左边写总数，右边是上一页 / 页码 / 下一页，和表格同一条底线 */
.ops-pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-top: 1px solid var(--line-soft);
  background: #fbfcfd;
}

.ops-pager-sum {
  color: var(--muted);
  font-size: 11.5px;
}

.ops-pager-btns {
  display: flex;
  gap: 4px;
  align-items: center;
}

.ops-pager-btn {
  min-width: 28px;
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--line-soft);
  border-radius: 5px;
  background: #fff;
  color: var(--ink-2);
  font-size: 11.5px;
  cursor: pointer;
}

.ops-pager-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.ops-pager-btn.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}

.ops-pager-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ops-audit li {
  display: grid;
  grid-template-columns: 132px 92px minmax(80px, 140px) minmax(0, 1fr) 80px;
  gap: 10px;
  align-items: baseline;
  padding: 9px 16px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 11.5px;
}

.ops-audit li:last-child {
  border-bottom: none;
}

.ops-audit li:hover {
  background: #fafbfc;
}

.ops-action {
  color: var(--ink-2);
  font-weight: 500;
}

.ops-target {
  overflow: hidden;
  color: var(--ink-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-detail {
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-actor {
  color: var(--muted);
  text-align: right;
  white-space: nowrap;
}

@media (max-width: 860px) {
  .ops-audit li {
    grid-template-columns: 110px 1fr;
  }

  .ops-target,
  .ops-detail {
    grid-column: 1 / -1;
    white-space: normal;
  }

  .ops-actor {
    text-align: left;
  }
}
</style>
