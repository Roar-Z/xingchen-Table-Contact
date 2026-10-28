<script setup>
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'

const router = useRouter()
</script>

<template>
  <div class="page">
    <div class="panel">
      <div class="empty">
        <span class="empty-icon"><Icon name="alert" :size="18" /></span>
        <b>页面不存在</b>
        <p>链接可能写错了，或者任务已经被清理。</p>
        <button class="btn btn-primary" @click="router.push('/')">返回新建任务</button>
      </div>
    </div>
  </div>
</template>
