<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { SPRINTS, MILESTONES } from '@/data/seed'

/* 对齐旧版 renderOverview(L893-907) + renderRealtime(L1181-1186) + 视图结构 L447-485 */
const router = useRouter()
const project = useProjectStore()
const go = v => router.push({ name: v })

const ovTotal = computed(() => String(project.stories.length).padStart(2, '0'))
const ovTasks = computed(() => project.tasks.length)
const overallPct = computed(() => project.groupPct(project.stories))
const doneCount = computed(() => project.stories.filter(s => s.status === 2).length)
const doingCount = computed(() => project.stories.filter(s => s.status === 1).length)
const realtimePct = computed(() => project.stories.length ? Math.round(doneCount.value / project.stories.length * 100) : 0)
const lastUpdate = computed(() => project.log.length ? project.log[project.log.length - 1].t : '—')

const sprintCards = computed(() => SPRINTS.map((sp, i) => {
  const list = project.stories.filter(s => s.sprint === i + 1)
  const d = list.filter(s => s.status === 2).length
  const p = project.groupPct(list)
  return { sp, list, d, p, weeks: ['W1–W2', 'W3–W4', 'W5–W6'][i] }
}))
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

    <div class="stats">
      <div class="stat"><label>故事总数</label><strong id="ov-total">{{ ovTotal }}</strong><span class="small">个用户故事</span></div>
      <div class="stat"><label>规划任务</label><strong id="ov-tasks">{{ ovTasks }}</strong><span class="small">个开发任务</span></div>
      <div class="stat"><label>团队成员</label><strong>04</strong><span class="small">人 · DRI 轮值</span></div>
      <div class="stat"><label>迭代周期</label><strong>06</strong><span class="small">周 · 3 个 Sprint</span></div>
    </div>

    <div class="h-sec">实时进度 · 看板</div>
    <div id="realtime">
      <div class="stats">
        <div class="stat"><label>完成比例</label><strong>{{ realtimePct }}%</strong><span class="small">{{ doneCount }} / {{ project.stories.length }} 故事</span></div>
        <div class="stat"><label>进行中</label><strong>{{ String(doingCount).padStart(2, '0') }}</strong><span class="small">个故事</span></div>
        <div class="stat"><label>待关注 / 阻塞</label><strong>03</strong><span class="small">个任务需确认</span></div>
        <div class="stat"><label>最近更新</label><strong class="mono" style="font-size:20px">{{ lastUpdate }}</strong><span class="small">本机变更时间</span></div>
      </div>
    </div>

    <div class="h-sec">Sprint 进度 · 三阶段递进</div>
    <div class="sprint-grid" id="sprint-grid">
      <div v-for="c in sprintCards" :key="c.sp.name" class="sprint" :class="c.sp.cls">
        <div class="top"><h3>{{ c.sp.name }}</h3><span class="pct">{{ c.p }}%</span></div>
        <div class="tagline">{{ c.sp.tag }} · {{ c.list.length }} 个故事</div>
        <div class="bar"><i :style="{ width: c.p + '%' }"></i></div>
        <div class="meta">完成 {{ c.d }} / {{ c.list.length }} · 工时加权 {{ c.p }}% · {{ c.weeks }}</div>
      </div>
    </div>

    <div class="h-sec">里程碑 · 关键交付节点</div>
    <div class="mile" id="mile-grid">
      <div v-for="m in MILESTONES" :key="m.id" class="milecard" :class="{ done: m.week <= 2 }">
        <span class="wk">W{{ m.week }}</span><span class="mid">{{ m.id }}</span><h4>{{ m.name }}</h4><p>{{ m.desc }}</p>
      </div>
    </div>

    <div class="h-sec">总体完成度</div>
    <div class="stat" style="margin-bottom:14px">
      <div class="inline"><label style="margin:0">需求故事完成进度</label><b class="mono" id="ov-percent">{{ overallPct }}%</b></div>
      <div class="track" role="progressbar" aria-label="总体完成度" aria-valuemin="0" aria-valuemax="100"><i id="ov-fill" :style="{ width: overallPct + '%' }"></i></div>
    </div>

    <div class="h-sec">快速进入</div>
    <div class="quick">
      <button class="quickcard" @click="go('board')"><span class="ic">▦</span><b>故事看板</b><span>拖拽更新状态、编辑与导出</span></button>
      <button class="quickcard" @click="go('gantt')"><span class="ic">▤</span><b>甘特图</b><span>6 周排期与里程碑</span></button>
      <button class="quickcard" @click="go('ai')"><span class="ic">✦</span><b>AI 助手</b><span>六项智能能力演示</span></button>
    </div>
  </section>
</template>
