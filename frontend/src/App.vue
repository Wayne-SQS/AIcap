<script setup>
import AppSidebar from '@/components/AppSidebar.vue'
import TopBar from '@/components/TopBar.vue'
import ToastBox from '@/components/ToastBox.vue'
</script>

<template>
  <div class="shell">
    <AppSidebar />
    <main class="main">
      <TopBar />
      <router-view />
    </main>
  </div>
  <ToastBox />
</template>
