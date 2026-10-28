<script setup>
import AppShell from './components/AppShell.vue'
import ToastHost from './components/ToastHost.vue'
</script>

<template>
  <AppShell>
    <!--
      工作台要缓存：用户切到「任务记录 / 表格智作」再切回来时，
      上传的表、规划方案、执行中的任务都得还在，不能被重新挂载清空。
      只缓存这一页，其他页面每次进来重新拉数据，避免看到过期的列表。
    -->
    <router-view v-slot="{ Component }">
      <keep-alive include="AgentWorkbench">
        <component :is="Component" />
      </keep-alive>
    </router-view>
  </AppShell>
  <ToastHost />
</template>
