<script setup>
import { computed, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import { clearTopbar, useTopbar } from '../topbar'

// 产品设计文档 / 开发文档是独立的静态 HTML，放在 frontend/public/docs 下，
// 由 Vite 原样拷进 dist，跟应用同源，所以不依赖后端服务
const route = useRoute()

const doc = computed(() => route.meta.doc || { title: '文档', src: '/docs/product.html', icon: 'book' })

// 两个文档共用这一个组件，顶栏标题跟着路由变，切换文档时不会还挂着上一篇的名字
watch(doc, (value) => {
  useTopbar({
    title: value.title,
    desc: '文档在右侧完整显示，需要放大或另存时可以新窗口打开。'
  })
}, { immediate: true })

onBeforeUnmount(() => clearTopbar())
</script>

<template>
  <div class="doc-page">
    <div class="doc-bar">
      <span class="doc-name"><Icon :name="doc.icon" :size="14" />{{ doc.title }}</span>
      <span class="doc-hint">左侧目录可以跳转章节，正文在这里滚动</span>
      <a class="doc-btn" :href="doc.word" :download="`${doc.title}.docx`" title="下载 Word 版，断网也能看">
        <Icon name="download" :size="13" />下载文档
      </a>
      <a class="doc-btn" :href="doc.src" target="_blank" rel="noreferrer" title="在新标签页打开">
        <Icon name="link" :size="13" />新窗口打开
      </a>
    </div>
    <iframe class="doc-frame" :src="doc.src" :title="doc.title"></iframe>
  </div>
</template>

<style scoped>
/* 顶栏 72 + 内容区上下留白 24 / 48，剩下的高度全给文档，页面本身不再出现第二条滚动条 */
.doc-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  height: calc(100vh - 144px);
  min-height: 460px;
  width: 100%;
}

.doc-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}

.doc-name {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}

.doc-hint {
  flex: 1;
  min-width: 0;
  color: var(--muted);
  font-size: 12px;
}

.doc-btn {
  display: inline-flex;
  flex: none;
  gap: 4px;
  align-items: center;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
  text-decoration: none;
}

.doc-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
  text-decoration: none;
}

/* 边框和圆角跟内容区的面板一致，文档内页自己的白底不会被外层样式串色 */
.doc-frame {
  flex: 1;
  min-height: 0;
  width: 100%;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
}

@media (max-width: 720px) {
  .doc-page {
    height: calc(100vh - 120px);
  }
}
</style>
