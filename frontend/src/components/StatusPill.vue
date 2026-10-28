<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: 'queued' }
})

const map = {
  done: { label: '已完成', cls: 'done' },
  success: { label: '已完成', cls: 'done' },
  warning: { label: '待复核', cls: 'warning' },
  running: { label: '处理中', cls: 'running' },
  queued: { label: '排队中', cls: 'queued' },
  failed: { label: '失败', cls: 'failed' }
}

const item = computed(() => map[props.status] || { label: props.status, cls: 'queued' })
</script>

<template>
  <span class="status" :class="item.cls"><i />{{ item.label }}</span>
</template>
