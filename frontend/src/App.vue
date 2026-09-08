<script setup>
import AppSidebar from '@/components/AppSidebar.vue'
import TopBar from '@/components/TopBar.vue'
import ToastBox from '@/components/ToastBox.vue'
import LoginDialog from '@/components/LoginDialog.vue'
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
  <LoginDialog />
</template>
