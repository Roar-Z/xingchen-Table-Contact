<script setup>
import AppShell from './components/AppShell.vue'
import ToastHost from './components/ToastHost.vue'
</script>

<template>
  <AppShell>
    <router-view />
  </AppShell>
  <ToastHost />
</template>
