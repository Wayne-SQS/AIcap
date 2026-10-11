<script setup>
import PlanningAgentPanel from '@/components/ai/PlanningAgentPanel.vue'
</script>

<template>
  <section class="view" id="view-drawing-agent">
    <div class="hero">
      <div>
        <div class="eyebrow">DRAWING AGENT / 智能协作</div>
        <h1>画图智能体</h1>
        <p>用自然语言调整项目规划，查看修改方案与影响分析，确认后同步更新四类项目视图。</p>
      </div>
    </div>

    <PlanningAgentPanel />
  </section>
</template>
