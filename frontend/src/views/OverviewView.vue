<script setup>
import { useRouter } from 'vue-router'
const router = useRouter()
const go = v => router.push({ name: v })
</script>

<template>
  <section class="view" id="view-overview">
    <div class="hero">
      <div>
        <div class="eyebrow">PROJECT OVERVIEW / 项目总览</div>
        <h1>爱管理</h1>
        <p>支持软件团队管理需求、安排任务、四视图联动与六项 AI 能力的协作平台。</p>
      </div>
      <button class="primary" @click="go('board')">＋ 新建用户故事</button>
    </div>
    <div class="empty">总览数据视图 · PR-1 接入数据后完整呈现</div>
  </section>
</template>
