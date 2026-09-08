<script setup>
import { useRouter } from 'vue-router'
// 侧栏结构逐字对齐旧版 index.html L407-433(选择器保留:.navitem[data-view]/.navtitle/.teamdots)
const router = useRouter()
const navGroups = [
  {
    title: '项目空间',
    items: [
      { view: 'overview', icon: '◈', label: '项目总览' },
      { view: 'pool', icon: '◆', label: '需求池' },
      { view: 'board', icon: '▦', label: '用户故事看板' },
      { view: 'gantt', icon: '▤', label: '甘特图' },
      { view: 'members', icon: '▥', label: '成员任务图' },
      { view: 'uml', icon: '◇', label: 'UML 图' }
    ]
  },
  {
    title: '智能协作',
    topGap: true,
    items: [
      { view: 'ai', icon: '✦', label: 'AI 助手' },
      { view: 'review', icon: '✓', label: 'AI 审核中心' }
    ]
  }
]
function go(v) { router.push({ name: v }) }
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <span class="mark" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span>
      <span>爱管理<small>TEAM WORKSPACE</small></span>
    </div>
    <div class="project"><span class="small">当前项目 / PROJECT</span><br>爱管理 · 开发小队</div>
    <nav aria-label="功能导航">
      <template v-for="group in navGroups" :key="group.title">
        <div class="navtitle" :style="group.topGap ? 'margin-top:24px' : ''">{{ group.title }}</div>
        <div
          v-for="item in group.items"
          :key="item.view"
          class="navitem"
          :class="{ active: $route.name === item.view }"
          :data-view="item.view"
          @click="go(item.view)"
        ><span class="navicon">{{ item.icon }}</span>{{ item.label }}</div>
      </template>
    </nav>
    <div class="sidebottom">
      <span class="eyebrow">PARTY / 04</span>
      <div class="teamdots">
        <span class="avatar">P1</span><span class="avatar a1">P2</span><span class="avatar a2">P3</span><span class="avatar a3">P4</span>
      </div>
      <p class="small">四人小队，共同推进。</p>
      <p class="small" style="margin-top:-6px">像素风可交互原型 · 本机自动保存</p>
    </div>
  </aside>
</template>
