<script setup>
import AppShell from './components/AppShell.vue'
import ToastHost from './components/ToastHost.vue'
</script>

<template>
  <AppShell>
    <!--
      这两页要缓存：用户切到别的页面再切回来时，工作台上的上传、方案、执行中任务，
      以及表格智作的清单与已展开的表格都得还在，不能被重新挂载清空、闪一次骨架。
      其他页面每次进来重新拉数据，避免看到过期的列表。
    -->
    <router-view v-slot="{ Component }">
      <keep-alive :include="['AgentWorkbench', 'SheetWorkbench']">
        <component :is="Component" />
      </keep-alive>
    </router-view>
  </AppShell>
  <ToastHost />
</template>
