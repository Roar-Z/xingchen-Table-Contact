<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import logoMark from '../assets/logo-mark.png'
import { api, apiState } from '../api'
import { clearNotice, noticeRetry, noticeText, retryNotice } from '../appNotice'
import { OFFLINE_HINT } from '../offline'
import { useToast } from '../toast'
import {
  railDetail,
  railLessons,
  railMemory,
  railMemoryRequest,
  railScope,
  railState,
  railSteps,
  railTools,
  railTone,
  railVisible
} from '../agentRail'
import { project } from '../project'
import { topbar } from '../topbar'

const route = useRoute()
const router = useRouter()

// 运行观测不占侧边栏的位置，收在左下角设置菜单的「系统与文档」里，避免导航越堆越长
const navItems = [
  { to: '/about', name: 'about', label: '项目简介', icon: 'info' },
  { to: '/', name: 'new-task', label: '新建任务', icon: 'plus' },
  { to: '/sheets', name: 'sheets', label: '表格智作', icon: 'table' },
  { to: '/tasks', name: 'tasks', label: '任务记录', icon: 'history' }
]

const currentTitle = computed(() => {
  if (route.name === 'task-detail') return '任务记录 / 任务详情'
  if (route.name === 'sheet-repo') return '表格智作 / 我的仓库'
  return route.meta.title || '新建任务'
})

// 不依赖后端的页面：文档是 public/docs 里的静态页，项目简介是纯前端静态内容，
// 后端口停了照样能翻，所以这两类页面不挂离线提示条
const staticPage = computed(
  () => route.name === 'doc-product' || route.name === 'doc-dev' || route.name === 'about'
)

// 断网不再说「服务连接中断」——那像故障。断网时能用的功能都还在，
// 手动「重试」时要能看出真的在检测：请求期间状态文案换成「正在检测连接」
const probing = ref(false)

const onlineText = computed(() => {
  if (probing.value) return '正在检测连接'
  if (apiState.online === null) return '正在连接服务'
  return apiState.online ? '服务运行正常' : '离线模式'
})

const onlineClass = computed(() => {
  if (apiState.online === null) return 'unknown'
  return apiState.online ? 'on' : 'offline'
})

const onlineTitle = computed(() => {
  if (probing.value) return '正在重新检测连接'
  return apiState.online === false ? OFFLINE_HINT : '点一下重新检测'
})

// 左下角那一行汇总：桥接模式的“步”是规划器排出的子任务数，普通模式是已跑完的动作数；
// 没有步数（还没规划 / 正在规划）时只留规则条数，状态已经由上面的状态点交代
function isActive(name) {
  if (name === 'tasks') return route.name === 'tasks' || route.name === 'task-detail'
  if (name === 'sheets') return route.name === 'sheets' || route.name === 'sheet-repo'
  return route.name === name
}

// 后端可能在跑着跑着被停掉，也可能晚于前端启动，所以定期探一次，不要只在打开页面时探
let probeTimer = null

// 换页面时把上一条提示清掉，避免 A 页的报错跟着出现在 B 页；
// 离线说明条不跟着翻页消失（它只在用户点 × 或恢复连接后才收起）
watch(() => route.fullPath, () => {
  clearNotice()
  if (apiState.online !== false) offlineMuted.value = false
})

// ---------------- 顶栏下方的统一提示条 ----------------
const offlineMuted = ref(false)
// 手动重试失败时，提示条文案要换成「重试失败」，否则和之前一字不差，看起来像没点
const retryFailed = ref(false)

// 亮出离线说明（已经关掉的，用户点重试时再挂回来）
function startOfflineNotice() {
  offlineMuted.value = false
}

watch(
  () => apiState.online,
  (online) => {
    retryFailed.value = false
    if (online) {
      offlineMuted.value = false
      return
    }
    // 刚进入离线：说明条一直挂着，由用户自己关
    if (apiState.online === false) startOfflineNotice()
  }
)

const noticeBarText = computed(() => {
  if (noticeText.value) return noticeText.value
  // 文档页不提示离线：文档在 public/docs 里，后端停了照样能翻
  if (staticPage.value) return ''
  if (apiState.online === false && !offlineMuted.value) {
    if (retryFailed.value) {
      return '重试失败：还是连不上服务，当前保持离线模式；本地解析、规则对齐和下载 CSV 照常可用。'
    }
    return '当前为离线模式：断网时表格仍在本地解析、字段按内置词典对齐，表格智作可按规则建表并下载 CSV；AI 规划与大模型问答暂不可用。'
  }
  return ''
})

const noticeBarRetry = computed(() => (noticeText.value ? !!noticeRetry.value : apiState.online === false))

function onNoticeRetry() {
  if (noticeText.value) {
    retryNotice()
    return
  }
  retryConnection()
}

function dismissNotice() {
  if (noticeText.value) {
    clearNotice()
    return
  }
  offlineMuted.value = true
}

// 定时探测：静默处理，成功失败都由 api.js 写回 apiState.online
async function probeHealth() {
  if (probing.value) return
  probing.value = true
  try {
    await api.health()
  } catch (e) {
    // 失败已经记在 apiState 里，状态点和提示条会自己更新
  } finally {
    probing.value = false
  }
}

// 用户手动点「重试」：不仅探一次，还要给出明确结果——
async function retryConnection() {
  if (probing.value) return
  startOfflineNotice()
  probing.value = true
  try {
    await api.health()
    retryFailed.value = false
    show('已恢复连接，正在切回在线模式')
  } catch (e) {
    retryFailed.value = true
    show('仍然连不上服务，当前保持离线模式', 'err')
  } finally {
    probing.value = false
  }
}

// 点左下角状态点：离线时当作「重试」，在线时只是重新探一次，不弹提示
function onNetClick() {
  if (apiState.online === false) retryConnection()
  else probeHealth()
}

function onVisible() {
  if (document.visibilityState === 'visible') probeHealth()
}

// ---------------- 左下角设置菜单 ----------------
const settingsOpen = ref(false)

function closeSettings() {
  settingsOpen.value = false
}

// 设置菜单里的「运行观测」：先收起菜单，再显式跳转。
function openOps() {
  closeSettings()
  if (route.name === 'ops') {
    window.dispatchEvent(new Event('huibiao:ops-cleared'))
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  router.push('/ops')
}

// 设置菜单里的「产品设计文档 / 开发文档」：文档在右侧内容区里打开，
function openDoc(path, name) {
  closeSettings()
  if (route.name === name) return
  router.push(path)
}

// ---------------- 设置菜单里的「清理数据 / 清理日志 / 删除所有表格」 ----------------
const { show } = useToast()

const clean = reactive({
  open: false,
  kind: '',
  input: '',
  busy: false,
  error: ''
})

// 清理数据 → 「确认清理数据」，清理日志 → 「确认清理日志」，删表格 → 「确认删除表格」
const confirmWord = computed(() => {
  if (clean.kind === 'audit') return '确认清理日志'
  if (clean.kind === 'sheets') return '确认删除表格'
  return '确认清理数据'
})

const cleanMeta = computed(() => {
  if (clean.kind === 'audit') {
    return {
      title: '清理运行日志',
      desc: '只清空审计日志，运行记录、统计与经验记忆都保留。',
      items: ['全部审计日志（本地最多保留 100 条）', '操作留痕删除后无法恢复'],
      action: '清理'
    }
  }
  if (clean.kind === 'sheets') {
    return {
      title: '删除所有表格数据',
      desc: '清空「表格智作」仓库，并删除任务记录、智能体数据集和上传/导出副本，运行观测不受影响。',
      items: [
        '所有表格及其表格文件（data/sheets 下的 xlsx）',
        '表格操作日志、删表留痕和撤销快照',
        '任务记录及任务输出文件（任务列表随之清空）',
        '智能体模式的数据集（data/bridge/datasets，含源表、方案与产出）',
        '上传副本（data/uploads）与默认导出副本（frontend/downloads）',
        '你电脑上的原始文件、自定义导出目录里的文件不会被删',
        '删除后无法恢复'
      ],
      action: '全部删除'
    }
  }
  return {
    title: '清空运行数据',
    desc: '把运行观测恢复到初始状态，任务和表格数据不受影响。',
    items: [
      '全部运行记录，成功率 / 兜底率 / Token 统计随之归零',
      '智能体沉淀的经验记忆',
      '全部审计日志（含本次清理动作本身的留痕）'
    ],
    action: '清空'
  }
})

function openClean(kind) {
  closeSettings()
  clean.open = true
  clean.kind = kind
  clean.input = ''
  clean.busy = false
  clean.error = ''
}

function closeClean() {
  if (clean.busy) return
  clean.open = false
}

// 删完数据后让当前页面自己刷新一次。
function refreshPage() {
  window.setTimeout(() => window.location.reload(), 400)
}

async function submitClean() {
  if (clean.busy) return
  if (clean.input.trim() !== confirmWord.value) {
    clean.error = `请先输入「${confirmWord.value}」`
    return
  }
  clean.busy = true
  clean.error = ''
  try {
    if (clean.kind === 'sheets') {
      const res = await api.clearAllSheets(confirmWord.value)
      const sheets = res?.cleared ?? 0
      const tasks = res?.tasks_cleared ?? 0
      const bridge = res?.bridge_cleared ?? 0
      const uploads = res?.uploads_cleared ?? 0
      const exports = res?.exports_cleared ?? 0
      clean.open = false
      show(`已删除 ${sheets} 张表格、${tasks} 条任务记录、${bridge} 个智能体数据集，并清理 ${uploads} 份上传副本、${exports} 份导出副本`)
      refreshPage()
    } else {
      const res = clean.kind === 'audit'
        ? await api.clearAuditLogs(confirmWord.value)
        : await api.clearAgentRuns(confirmWord.value)
      const count = res?.cleared ?? 0
      clean.open = false
      show(clean.kind === 'audit' ? `已清理 ${count} 条运行日志` : `已清空 ${count} 条运行记录`)
      // 清理的是运行观测，停在该页时让它就地重新拉一遍，不用手动刷新
      if (route.name === 'ops') window.dispatchEvent(new Event('huibiao:ops-cleared'))
      else refreshPage()
    }
  } catch (err) {
    clean.error = err.message || '操作失败，请稍后再试'
  } finally {
    clean.busy = false
  }
}

function onKeydown(event) {
  if (event.key !== 'Escape') return
  if (clean.open) closeClean()
  else settingsOpen.value = false
}

onMounted(() => {
  probeHealth()
  probeTimer = setInterval(probeHealth, 8000)
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('focus', probeHealth)
  document.addEventListener('click', closeSettings)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  if (probeTimer) clearInterval(probeTimer)
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener('focus', probeHealth)
  document.removeEventListener('click', closeSettings)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <router-link to="/" class="brand">
        <img class="brand-mark" :src="logoMark" :alt="project.team" />
        <span>
          <span class="brand-name">{{ project.team }}</span>
          <span class="brand-sub">跨系统表格桥接与处理智能体</span>
        </span>
      </router-link>

      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="item.to"
          class="nav-item"
          :class="{ active: isActive(item.name) }"
        >
          <Icon :name="item.icon" :size="15" />
          {{ item.label }}
        </router-link>
      </nav>

      <!-- 新建任务页的操作步骤：就在导航下面，一抬眼能看到，不占正文宽度 -->
      <section
        v-if="railVisible"
        class="nav-rail"
        title="这里按顺序列出本次要做的每个环节；涉及数据与数值的结论都需要你确认后才会生效"
      >
        <div class="nav-rail-head">
          <b>操作步骤</b>
          <span class="nav-rail-scope" :class="railScope === 'bridge' ? 'is-agent' : ''">
            {{ railScope === 'bridge' ? '智能体' : '半自动' }}
          </span>
          <span class="nav-rail-state" :class="`is-${railTone}`">{{ railState }}</span>
        </div>
        <p v-if="railDetail" class="nav-rail-detail">{{ railDetail }}</p>
        <div class="nav-rail-tools">
          <p v-if="!railTools.length" class="nav-rail-idle">
            <template v-if="railScope === 'bridge'">
              提交数据集并说明业务目标后，智能体按「识别数据表 → 对齐字段 → 跨表计算 → 输出结果表」推进，环节与进度显示在这里。
            </template>
            <template v-else>
              提交源表与目标模板后，系统按「读取源表 → 生成字段映射 → 人工核对 → 按模板导出」推进，环节与进度显示在这里。
            </template>
          </p>
          <span
            v-for="tool in railTools"
            :key="tool.key"
            class="nav-rail-tool"
            :class="`is-${tool.state}`"
            :title="tool.hint || tool.label"
          >
            <span class="nav-rail-tool-text">
              <b>{{ tool.label }}</b>
              <em v-if="tool.hint">{{ tool.hint }}</em>
            </span>
          </span>
        </div>
        <div class="nav-rail-foot">
          <span class="nav-rail-sum">
            <template v-if="railSteps > 0">{{ railSteps }} 步 · </template>
            <template v-if="railScope === 'bridge'">{{ railMemory }} 条规则 · {{ railLessons }} 条经验</template>
            <template v-else>{{ railMemory }} 条经验</template>
          </span>
          <button
            type="button"
            class="nav-rail-more"
            :title="railScope === 'bridge' ? '打开字段规则与经验记忆' : '打开历史方案记忆'"
            @click="railMemoryRequest += 1"
          >
            管理
          </button>
        </div>
      </section>

      <div class="sidebar-foot">
        <div class="foot-row">
          <span class="net" :title="onlineTitle" @click="onNetClick">
            <i class="net-dot" :class="onlineClass" />
            {{ probing ? '正在检测连接' : onlineText }}
          </span>
          <!-- 设置入口贴在最右侧；产品/开发文档和运行观测收进菜单，图标行不再堆一排 -->
          <div class="foot-links">
            <button
              type="button"
              class="foot-link foot-link-btn"
              :class="{ 'is-open': settingsOpen }"
              data-tip="设置"
              aria-label="设置"
              :aria-expanded="settingsOpen"
              @click.stop="settingsOpen = !settingsOpen"
            >
              <Icon name="gear" :size="15" />
            </button>
            <div v-if="settingsOpen" class="foot-menu" @click.stop>
              <span class="foot-menu-head">系统与文档</span>
              <button
                type="button"
                class="foot-menu-item"
                :class="{ 'is-current': route.name === 'ops' }"
                @click="openOps"
              >
                <Icon name="shield" :size="13" />运行观测
                <span v-if="route.name === 'ops'" class="foot-menu-hint">当前</span>
              </button>
              <button
                type="button"
                class="foot-menu-item"
                :class="{ 'is-current': route.name === 'doc-product' }"
                @click="openDoc('/docs/product', 'doc-product')"
              >
                <Icon name="book" :size="13" />产品设计文档
                <span v-if="route.name === 'doc-product'" class="foot-menu-hint">当前</span>
              </button>
              <button
                type="button"
                class="foot-menu-item"
                :class="{ 'is-current': route.name === 'doc-dev' }"
                @click="openDoc('/docs/dev', 'doc-dev')"
              >
                <Icon name="code" :size="13" />开发文档
                <span v-if="route.name === 'doc-dev'" class="foot-menu-hint">当前</span>
              </button>
              <!-- 清理与删除都收在设置里，避免误点；两个分区用分隔线区分严重程度 -->
              <div class="foot-menu-sep"></div>
              <span class="foot-menu-head">清理运行数据</span>
              <button type="button" class="foot-menu-item is-danger" @click="openClean('runs')">
                <Icon name="database" :size="13" />清理数据
              </button>
              <button type="button" class="foot-menu-item is-danger" @click="openClean('audit')">
                <Icon name="trash" :size="13" />清理日志
              </button>
              <div class="foot-menu-sep"></div>
              <span class="foot-menu-head">表格数据</span>
              <button type="button" class="foot-menu-item is-danger" @click="openClean('sheets')">
                <Icon name="table" :size="13" />删除所有表格
              </button>
            </div>
          </div>
        </div>
        <span class="foot-version">v{{ project.version }} · {{ project.team }}</span>
      </div>
    </aside>

    <div class="main-area">
      <header class="topbar">
        <div class="topbar-text">
          <span class="topbar-title">{{ topbar.title || currentTitle }}</span>
          <span v-if="topbar.desc" class="topbar-desc">{{ topbar.desc }}</span>
        </div>
        <div class="topbar-side">
          <!-- 页面级分段切换：新建任务页的 普通模式 / 智能体模式，放在仓库入口左边 -->
          <div v-if="topbar.segments.length" class="topbar-seg" role="tablist">
            <button
              v-for="item in topbar.segments"
              :key="item.key"
              class="topbar-seg-btn"
              :class="{ 'is-on': topbar.segment === item.key }"
              type="button"
              role="tab"
              :aria-selected="topbar.segment === item.key"
              :title="item.hint"
              @click="topbar.onSegment && topbar.onSegment(item.key)"
            >
              {{ item.label }}
              <b v-if="item.badge" class="topbar-seg-badge">{{ item.badge }}</b>
            </button>
          </div>
          <!-- 页面级操作（如表格智作的「新建空白表」）：和右侧仓库入口并排，放在它左边 -->
          <button
            v-for="item in topbar.actions"
            :key="item.key"
            class="topbar-action"
            type="button"
            :title="item.hint || item.label"
            @click="topbar.handler && topbar.handler(item.key)"
          >
            <Icon v-if="item.icon" :name="item.icon" :size="14" />
            {{ item.label }}
          </button>
          <router-link
            to="/sheets/repo"
            class="topbar-repo"
            :class="{ active: route.name === 'sheet-repo' }"
            title="我的仓库"
          >
            <Icon name="folder" :size="14" />
            我的仓库
          </router-link>
        </div>
      </header>

      <!-- 全局提示条：所有页面的报错都收在这一处，位置固定在顶栏下方，样式统一 -->
      <div v-if="noticeBarText" class="app-notice" role="alert">
        <Icon name="alert" :size="14" />
        <span class="app-notice-text">{{ noticeBarText }}</span>
        <button
          v-if="noticeBarRetry"
          type="button"
          class="app-notice-retry"
          :disabled="probing"
          @click="onNoticeRetry"
        >{{ probing ? '检测中…' : '重试' }}</button>
        <button type="button" class="app-notice-close" title="关闭提示" @click="dismissNotice">
          <Icon name="close" :size="12" />
        </button>
      </div>

      <main class="content">
        <slot />
      </main>
    </div>

    <!-- 危险操作确认弹窗：标题 + 说明 + 影响清单 + 手输确认，形制对齐主流后台的确认框 -->
    <Transition name="clean-fade">
      <div v-if="clean.open" class="clean-mask" @click.self="closeClean">
        <div class="clean-dialog" role="dialog" aria-modal="true" :aria-label="cleanMeta.title">
          <header class="clean-head">
            <h3 class="clean-title">{{ cleanMeta.title }}</h3>
            <button type="button" class="clean-close" aria-label="关闭" @click="closeClean">
              <Icon name="close" :size="14" />
            </button>
          </header>

          <p class="clean-desc">{{ cleanMeta.desc }}</p>

          <div class="clean-panel">
            <span class="clean-panel-head">将删除以下内容</span>
            <ul class="clean-list">
              <li v-for="item in cleanMeta.items" :key="item">{{ item }}</li>
            </ul>
          </div>

          <div class="clean-field">
            <label class="clean-label" for="clean-confirm">
              请输入 <b>{{ confirmWord }}</b> 以继续
            </label>
            <input
              id="clean-confirm"
              v-model="clean.input"
              class="clean-input"
              type="text"
              autocomplete="off"
              spellcheck="false"
              :placeholder="confirmWord"
              @keydown.enter="submitClean"
            />
            <p v-if="clean.error" class="clean-error">{{ clean.error }}</p>
          </div>

          <footer class="clean-foot">
            <button type="button" class="clean-btn" :disabled="clean.busy" @click="closeClean">取消</button>
            <button
              type="button"
              class="clean-btn is-primary"
              :disabled="clean.busy || clean.input.trim() !== confirmWord"
              @click="submitClean"
            >
              {{ clean.busy ? '处理中…' : cleanMeta.action }}
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* 全局提示条：统一贴顶栏下方通栏显示，各页不再各自在正文里摆红带 */
.app-notice {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 9px 28px;
  border-bottom: 1px solid #f3d2cf;
  background: #fdf0ef;
  color: #b4453c;
  font-size: 12.5px;
  line-height: 18px;
}

.app-notice-text {
  flex: 1;
  min-width: 0;
}

.app-notice-retry {
  padding: 0;
  border: none;
  background: none;
  color: #b4453c;
  font-size: 12.5px;
  font-weight: 600;
  text-decoration: underline;
  cursor: pointer;
}

.app-notice-close {
  display: inline-flex;
  padding: 2px;
  border: none;
  background: none;
  color: #b4453c;
  opacity: 0.55;
  cursor: pointer;
}

.app-notice-close:hover {
  opacity: 1;
}

@media (max-width: 720px) {
  .app-notice {
    padding: 8px 16px;
  }
}

/* 导航下方的智能体状态：只用一条细线和几行小字，不加底色和阴影 */
.nav-rail {
  margin-top: 18px;
  padding: 12px 8px 0;
  border-top: 1px solid var(--line-soft);
}

.nav-rail-head {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

/* 模式标记：智能体 / 半自动，小号胶囊，不抢状态点的视觉重量 */
.nav-rail-scope {
  margin-right: auto;
  padding: 1px 6px;
  border-radius: 9px;
  background: var(--line-soft);
  color: var(--ink-2);
  font-size: 10.5px;
  line-height: 1.5;
}

/* 智能体模式是自动规划，用主色把「智能体」三个字提亮，和半自动区分开 */
.nav-rail-scope.is-agent {
  background: rgb(43 95 217 / 12%);
  color: var(--accent);
  font-weight: 600;
}

.nav-rail-head b {
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
}

.nav-rail-state {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  color: var(--ink-2);
  font-size: 11.5px;
  white-space: nowrap;
}

.nav-rail-state::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #cbd0d8;
}

.nav-rail-state.is-busy::before {
  background: var(--accent);
}

.nav-rail-state.is-ok::before {
  background: #2f9e68;
}

.nav-rail-state.is-wait::before {
  background: #cc9426;
}

/* 方案摘要：一句话说明本次桥接的规模，和上面的状态点形成“现在到哪 + 这次做了什么” */
.nav-rail-detail {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.6;
}

/* 步骤列表固定高度、内部滚动：条目再多也不会撑高这一块，
 * 也不会把底部的统计行挤出侧栏 */
.nav-rail-tools {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 5px;
  /* 高度上限跟着窗口走：正常屏高时约 300px（和原来六七步的高度相当），
   * 窗口矮的时候自动收窄，保证底部统计行不会被挤出侧栏 */
  max-height: clamp(120px, calc(100vh - 380px), 300px);
  margin-top: 12px;
  padding-left: 14px;
  padding-right: 2px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: #dfe3e9 transparent;
  /* 纵向线段画在容器背景上：滚动时不会跟着条目一起滚走，末端渐隐 */
  background-image: linear-gradient(180deg, #d7dce4 0%, #e4e8ee 60%, rgb(228 232 238 / 0%) 100%);
  background-repeat: no-repeat;
  background-position: 4px 14px;
  background-size: 1px calc(100% - 28px);
}

.nav-rail-tools::-webkit-scrollbar {
  width: 6px;
}

.nav-rail-tools::-webkit-scrollbar-track {
  background: transparent;
}

.nav-rail-tools::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: #dfe3e9;
}

.nav-rail-tools::-webkit-scrollbar-thumb:hover {
  background: #cbd2dc;
}

.nav-rail-tool {
  position: relative;
  display: flex;
  gap: 7px;
  align-items: flex-start;
  min-width: 0;
  min-height: 34px;
  padding: 8px 4px 8px 6px;
  border-radius: 5px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.5;
}

/* 环节文字整体最多两行：长结论（如导出文件清单）折到第二行后省略，
 * 以前是不换行、直接横向越出侧栏 */
.nav-rail-tool-text {
  display: -webkit-box;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-height: 1.5;
}

.nav-rail-tool:hover {
  background: #f7f8fa;
}

/* 还没开始规划时的占位说明，避免把 4 个能力一次性摆出来 */
.nav-rail-idle {
  margin: 0;
  padding: 10px 6px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
}

/* 正在跑的那一步：文字加深、圆点呼吸，让进度“动”起来 */
.nav-rail-tool.is-busy b {
  color: var(--accent);
}

.nav-rail-tool.is-busy::before {
  background: var(--accent);
  animation: railPulse 1.1s ease-in-out infinite;
}

@keyframes railPulse {
  0%,
  100% {
    box-shadow: 0 0 0 3px var(--panel);
  }

  50% {
    box-shadow: 0 0 0 5px rgb(43 95 217 / 16%);
  }
}

.nav-rail-tool b {
  color: var(--ink-2);
  font-weight: 400;
}

/* 说明文字跟在名称后面、颜色更浅；整体受上面两行截断约束，不再单独省略 */
.nav-rail-tool em {
  margin-left: 6px;
  color: var(--muted);
  font-style: normal;
}

.nav-rail-tool::before {
  content: '';
  position: absolute;
  top: 50%;
  left: -12px;
  width: 5px;
  height: 5px;
  margin-top: -2.5px;
  border-radius: 50%;
  background: #cfd5dd;
  box-shadow: 0 0 0 3px var(--panel);
}

/* 节点到文字的一小段连接线，让每行更像流程节点 */
.nav-rail-tool::after {
  content: '';
  position: absolute;
  top: 50%;
  left: -7px;
  width: 13px;
  height: 1px;
  background: #e2e6ec;
}

.nav-rail-tool.is-done b {
  color: var(--ink);
}

.nav-rail-tool.is-done::before {
  background: var(--accent);
}

.nav-rail-tool.is-done::after {
  background: #b9c8f0;
}

.nav-rail-foot {
  display: flex;
  gap: 6px;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 10px;
  color: var(--muted);
  font-size: 11.5px;
}

.nav-rail-sum {
  white-space: nowrap;
}

.nav-rail-more {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
}

.nav-rail-more:hover {
  text-decoration: underline;
}

.topbar-side {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}

/* 顶栏右侧的模式切换：一行两段，选中的那段落白色底，和旁边的仓库按钮同一高度 */
.topbar-seg {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  height: 32px;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg);
}

.topbar-seg-btn {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 26px;
  padding: 0 11px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
  transition: color 0.15s, background 0.15s, box-shadow 0.15s;
}

.topbar-seg-btn:hover {
  color: var(--ink-2);
}

.topbar-seg-btn.is-on {
  background: var(--panel);
  color: var(--ink);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(31, 35, 41, 0.08);
}

/* “核心”标签：橙色胶囊，和页面里的智能体标记保持一致 */
.topbar-seg-badge {
  padding: 0 5px;
  border: 1px solid #f0d9b5;
  border-radius: 999px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 10px;
  font-weight: 600;
  line-height: 14px;
}

/* 顶栏里的页面级操作：和「我的仓库」同形制（白底细边框、30px 高），放在它左边 */
.topbar-action {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  color: var(--ink-2);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.topbar-action:hover {
  border-color: var(--accent);
  color: var(--accent);
}

/* 顶部右侧固定入口：表格文件都在仓库里，不用先回工作台 */
.topbar-repo {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
  text-decoration: none;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.topbar-repo:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.topbar-repo.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 500;
}

@media (max-width: 720px) {
  .topbar-side {
    display: none;
  }
}

/* 危险操作确认弹窗：企业后台常见形制 ——
 * 白色卡片、8px 圆角、中性灰文字，只有一个主色按钮，不用红底图标、不用彩色描边。
 * 层级只靠留白和一条浅灰分隔面板，尽量少用颜色。 */
.clean-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.45);
}

.clean-dialog {
  width: 100%;
  max-width: 400px;
  padding: 20px 24px 20px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.12);
}

.clean-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.clean-title {
  flex: 1;
  min-width: 0;
  margin: 0;
  color: var(--ink);
  font-size: 15px;
  font-weight: 600;
  line-height: 24px;
}

.clean-close {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  margin-top: 0;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: none;
  color: var(--muted);
  cursor: pointer;
}

.clean-close:hover {
  background: var(--line-soft);
  color: var(--ink-2);
}

.clean-desc {
  margin: 6px 0 0;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
}

/* 影响清单放一块浅灰底，比彩色提示更安静，也更好扫读 */
.clean-panel {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 6px;
  background: #f7f8fa;
}

.clean-panel-head {
  display: block;
  margin-bottom: 6px;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 500;
}

.clean-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 4px;
}

.clean-list li {
  position: relative;
  padding-left: 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.clean-list li::before {
  content: '';
  position: absolute;
  top: 8px;
  left: 2px;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #c9ced8;
}

.clean-field {
  margin-top: 16px;
}

.clean-label {
  display: block;
  margin-bottom: 6px;
  color: var(--ink-2);
  font-size: 12px;
}

.clean-label b {
  color: var(--ink);
  font-weight: 600;
}

.clean-input {
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.clean-input::placeholder {
  color: #c0c4cc;
}

.clean-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgba(43, 95, 217, 0.12);
}

.clean-error {
  margin: 6px 0 0;
  color: var(--danger);
  font-size: 12px;
}

.clean-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 20px;
}

/* 取消是白底描边按钮，确认用主题蓝色实心按钮，两个按钮同高同圆角 */
.clean-btn {
  height: 32px;
  min-width: 72px;
  padding: 0 16px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.clean-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.clean-btn.is-primary {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}

.clean-btn.is-primary:hover:not(:disabled) {
  border-color: var(--accent-dark);
  background: var(--accent-dark);
  color: #fff;
}

.clean-btn:disabled {
  cursor: not-allowed;
  border-color: var(--line);
  background: #f7f8fa;
  color: #c0c4cc;
}

.clean-btn.is-primary:disabled {
  border-color: #c7d4f0;
  background: #c7d4f0;
  color: #fff;
}

/* 只做一次很短的淡入，不做位移和缩放，避免“动效感” */
.clean-fade-enter-active,
.clean-fade-leave-active {
  transition: opacity 0.12s linear;
}

.clean-fade-enter-from,
.clean-fade-leave-to {
  opacity: 0;
}
</style>
