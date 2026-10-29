<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Icon from './Icon.vue'
import logoMark from '../assets/logo-mark.png'
import { api, apiState } from '../api'
import { clearNotice, noticeRetry, noticeText, retryNotice } from '../appNotice'
import {
  railDetail,
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

const navItems = [
  { to: '/', name: 'new-task', label: '新建任务', icon: 'plus' },
  { to: '/sheets', name: 'sheets', label: '表格智作', icon: 'table' },
  { to: '/tasks', name: 'tasks', label: '任务记录', icon: 'history' },
  { to: '/ops', name: 'ops', label: '运行观测', icon: 'shield' }
]

const currentTitle = computed(() => {
  if (route.name === 'task-detail') return '任务记录 / 任务详情'
  if (route.name === 'sheet-repo') return '表格智作 / 我的仓库'
  return route.meta.title || '新建任务'
})

const onlineText = computed(() => {
  if (apiState.online === null) return '正在连接服务'
  return apiState.online ? '服务运行正常' : '服务连接中断'
})

const onlineClass = computed(() => {
  if (apiState.online === null) return 'unknown'
  return apiState.online ? 'on' : 'off'
})

// 左下角那一行汇总：桥接模式的“步”是规划器排出的子任务数，标准模式是已跑完的动作数；
// 没有步数（还没规划 / 正在规划）时只留规则条数，状态已经由上面的状态点交代
function isActive(name) {
  if (name === 'tasks') return route.name === 'tasks' || route.name === 'task-detail'
  if (name === 'sheets') return route.name === 'sheets' || route.name === 'sheet-repo'
  return route.name === name
}

// 后端可能在跑着跑着被停掉，也可能晚于前端启动，所以定期探一次，不要只在打开页面时探
let probeTimer = null

// 换页面时把上一条提示清掉，避免 A 页的报错跟着出现在 B 页
watch(() => route.fullPath, () => {
  clearNotice()
  offlineMuted.value = false
})

/* ---------------- 顶栏下方的统一提示条 ----------------
 * 两种来源：页面自己报的错（noticeText），以及服务连不上。
 * 都只在同一处显示，各页正文里不再各摆一条红带。
 */
const offlineMuted = ref(false)

watch(
  () => apiState.online,
  (online) => {
    if (online) offlineMuted.value = false
  }
)

const noticeBarText = computed(() => {
  if (noticeText.value) return noticeText.value
  if (apiState.online === false && !offlineMuted.value) return '暂时连不上服务，请稍后刷新页面重试。'
  return ''
})

const noticeBarRetry = computed(() => (noticeText.value ? !!noticeRetry.value : apiState.online === false))

function onNoticeRetry() {
  if (noticeText.value) retryNotice()
  else probeHealth()
}

function dismissNotice() {
  if (noticeText.value) clearNotice()
  else offlineMuted.value = true
}

function probeHealth() {
  api.health().catch(() => {})
}

function onVisible() {
  if (document.visibilityState === 'visible') probeHealth()
}

/* ---------------- 左下角设置菜单 ----------------
 * 点齿轮打开，点页面别处或按 Esc 收起，和主流应用的侧边栏设置菜单一致
 */
const settingsOpen = ref(false)

function closeSettings() {
  settingsOpen.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') settingsOpen.value = false
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
            :title="tool.hint"
          >
            <b>{{ tool.label }}</b>
            <em>{{ tool.hint }}</em>
          </span>
        </div>
        <div class="nav-rail-foot">
          <span class="nav-rail-sum">
            <template v-if="railSteps > 0">{{ railSteps }} 步 · </template>{{ railMemory }} {{ railScope === 'bridge' ? '条规则' : '条经验' }}
          </span>
          <button
            type="button"
            class="nav-rail-more"
            :title="railScope === 'bridge' ? '打开自定义字段匹配规则' : '打开历史方案记忆'"
            @click="railMemoryRequest += 1"
          >
            管理
          </button>
        </div>
      </section>

      <div class="sidebar-foot">
        <div class="foot-row">
          <span class="net" title="点一下重新检测" @click="probeHealth">
            <i class="net-dot" :class="onlineClass" />
            {{ onlineText }}
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
              <router-link class="foot-menu-item" to="/ops" @click="closeSettings">
                <Icon name="shield" :size="13" />运行观测
              </router-link>
              <a
                class="foot-menu-item"
                href="/docs/product.html"
                target="_blank"
                rel="noreferrer"
                @click="closeSettings"
              >
                <Icon name="book" :size="13" />产品设计文档
              </a>
              <a
                class="foot-menu-item"
                href="/docs/dev.html"
                target="_blank"
                rel="noreferrer"
                @click="closeSettings"
              >
                <Icon name="code" :size="13" />开发文档
              </a>
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
          <!-- 页面级分段切换：新建任务页的 标准模式 / 智能体模式，放在仓库入口左边 -->
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
        <button v-if="noticeBarRetry" type="button" class="app-notice-retry" @click="onNoticeRetry">重试</button>
        <button type="button" class="app-notice-close" title="关闭提示" @click="dismissNotice">
          <Icon name="close" :size="12" />
        </button>
      </div>

      <main class="content">
        <slot />
      </main>
    </div>
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

.nav-rail-tools {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 5px;
  margin-top: 12px;
  padding-left: 14px;
}

/* 纵向线段：把六个动作串成一条流程，末端渐隐 */
.nav-rail-tools::before {
  content: '';
  position: absolute;
  top: 14px;
  bottom: 14px;
  left: 4px;
  width: 1px;
  background: linear-gradient(180deg, #d7dce4 0%, #e4e8ee 60%, rgb(228 232 238 / 0%) 100%);
}

.nav-rail-tool {
  position: relative;
  display: flex;
  gap: 7px;
  align-items: baseline;
  min-width: 0;
  min-height: 34px;
  padding: 8px 4px 8px 6px;
  border-radius: 5px;
  color: var(--muted);
  font-size: 11.5px;
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
  flex: none;
  color: var(--ink-2);
  font-weight: 400;
}

/* 说明文字放后面、颜色更浅，窄的时候省略，不换行 */
.nav-rail-tool em {
  overflow: hidden;
  color: var(--muted);
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
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
</style>
