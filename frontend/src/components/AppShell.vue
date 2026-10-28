<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import Icon from './Icon.vue'
import logoMark from '../assets/logo-mark.png'
import { api, apiState } from '../api'
import {
  railMemory,
  railMemoryRequest,
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
  if (apiState.online === null) return '正在连接后端'
  return apiState.online ? '后端服务正常' : '后端未连接'
})

const onlineClass = computed(() => {
  if (apiState.online === null) return 'unknown'
  return apiState.online ? 'on' : 'off'
})

function isActive(name) {
  if (name === 'tasks') return route.name === 'tasks' || route.name === 'task-detail'
  if (name === 'sheets') return route.name === 'sheets' || route.name === 'sheet-repo'
  return route.name === name
}

// 后端可能在跑着跑着被停掉，也可能晚于前端启动，所以定期探一次，不要只在打开页面时探
let probeTimer = null

function probeHealth() {
  api.health().catch(() => {})
}

function onVisible() {
  if (document.visibilityState === 'visible') probeHealth()
}

onMounted(() => {
  probeHealth()
  probeTimer = setInterval(probeHealth, 8000)
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('focus', probeHealth)
})

onBeforeUnmount(() => {
  if (probeTimer) clearInterval(probeTimer)
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener('focus', probeHealth)
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <router-link to="/" class="brand">
        <img class="brand-mark" :src="logoMark" alt="汇表" />
        <span>
          <span class="brand-name">汇表</span>
          <span class="brand-sub">表格桥接智能体</span>
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

      <!-- 新建任务页的智能体进度：就在导航下面，一抬眼能看到，不占正文宽度 -->
      <section
        v-if="railVisible"
        class="nav-rail"
        title="数值口径由确定性代码执行，大模型只做语义判断；任何自动结论都需用户确认后生效"
      >
        <div class="nav-rail-head">
          <b>汇表智能体</b>
          <span class="nav-rail-state" :class="`is-${railTone}`">{{ railState }}</span>
        </div>
        <div class="nav-rail-tools">
          <p v-if="!railTools.length" class="nav-rail-idle">
            点「交给智能体规划」后，这里会按实际进度逐条显示它在做什么
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
            {{ railSteps ? `${railSteps} 步` : railState === '规划中' ? '规划中' : '未开始' }} · {{ railMemory }} 条经验
          </span>
          <button type="button" class="nav-rail-more" @click="railMemoryRequest += 1">管理</button>
        </div>
      </section>

      <div class="sidebar-foot">
        <span class="net" title="点一下重新检测" @click="probeHealth">
          <i class="net-dot" :class="onlineClass" />
          {{ onlineText }}
        </span>
        <div class="foot-links">
          <a
            class="foot-link"
            href="/docs/product.html"
            target="_blank"
            rel="noreferrer"
            data-tip="产品设计文档"
            aria-label="产品设计文档"
          >
            <Icon name="book" :size="15" />
          </a>
          <a
            class="foot-link"
            :href="project.repo"
            target="_blank"
            rel="noreferrer"
            data-tip="GitHub 源码"
            aria-label="GitHub 源码"
          >
            <Icon name="github" :size="15" />
          </a>
          <a
            class="foot-link"
            href="/docs/dev.html"
            target="_blank"
            rel="noreferrer"
            data-tip="开发文档"
            aria-label="开发文档"
          >
            <Icon name="code" :size="15" />
          </a>
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
      <main class="content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
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
