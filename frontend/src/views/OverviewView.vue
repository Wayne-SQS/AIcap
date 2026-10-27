<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'

const router = useRouter()
const project = useProjectStore()
const projects = useProjectsStore()
const session = useSessionStore()
const go = v => router.push({ name: project.currentProjectId ? `project-${v}` : v, params: project.currentProjectId ? { projectId: project.currentProjectId } : {} })
const INSIGHT_DEFAULT_TAB = 'forecast'
const goInsight = () => router.push({
  name: project.currentProjectId ? 'project-insights' : 'insights',
  params: project.currentProjectId ? { projectId: project.currentProjectId } : {},
  query: { tab: INSIGHT_DEFAULT_TAB }
})

const ovTotal = computed(() => String(project.stories.length).padStart(2, '0'))
const ovTasks = computed(() => project.tasks.length)
/** 工时加权口径:与 Sprint 卡片、看板总完成度一致 */
const overallPct = computed(() => project.groupPct(project.stories))
const doneCount = computed(() => project.stories.filter(s => s.status === 2).length)
const doingCount = computed(() => project.stories.filter(s => s.status === 1).length)
/** 完成比例:纯计数口径(与后端 /api/dashboard.percent 同口径) */
const realtimePct = computed(() => project.stories.length ? Math.round(doneCount.value / project.stories.length * 100) : 0)
/** 待关注/阻塞:取真实 blocked 标记数 */
const blockedCount = computed(() => project.tasks.filter(t => t.blocked).length)
const lastUpdate = computed(() => project.log.length ? project.log[project.log.length - 1].t : '—')
</script>

<template>
  <section class="view" id="view-overview">
    <div class="hero ov-hero">
      <div class="ov-hero-main">
        <div class="eyebrow">PROJECT OVERVIEW / 项目总览</div>
        <h1>{{ projects.currentProject?.name || '我的项目' }}</h1>
        <p>{{ projects.currentProject?.description || '当前项目的需求、排期与协作数据。' }}</p>
        <div class="ov-chips">
          <span class="ov-chip">{{ projects.currentProjectId || '未选择项目' }}</span>
          <span class="ov-chip">{{ String(project.members.length).padStart(2, '0') }} 名成员</span>
          <span class="ov-chip">最近更新 {{ lastUpdate }}</span>
        </div>
      </div>
      <div class="ov-hero-acts">
        <button class="primary" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="go('board')">＋ 新建用户故事</button>
      </div>
    </div>

    <div class="stats">
      <div class="stat ov-stat">
        <label><span class="ov-ic" aria-hidden="true">◈</span>故事总数</label>
        <strong id="ov-total">{{ ovTotal }}</strong>
        <span class="small">个用户故事</span>
      </div>
      <div class="stat ov-stat">
        <label><span class="ov-ic" aria-hidden="true">▤</span>规划任务</label>
        <strong id="ov-tasks">{{ ovTasks }}</strong>
        <span class="small">个开发任务</span>
      </div>
      <div class="stat ov-stat">
        <label><span class="ov-ic" aria-hidden="true">☗</span>团队成员</label>
        <strong>{{ String(project.members.length).padStart(2, '0') }}</strong>
        <span class="small">人 · 含只读查看者</span>
      </div>
      <div class="stat ov-stat">
        <label><span class="ov-ic" aria-hidden="true">◷</span>迭代周期</label>
        <strong>06</strong>
        <span class="small">周 · 3 个 Sprint + 后续路线</span>
      </div>
    </div>

    <div class="h-sec">实时进度 · 看板</div>
    <div class="ov-progress">
      <div class="ov-progress-main">
        <span class="ov-progress-label">需求故事完成进度</span>
        <b class="ov-progress-num" id="ov-percent">{{ overallPct }}%</b>
        <div class="track" role="progressbar" aria-label="总体完成度" aria-valuemin="0" aria-valuemax="100"><i id="ov-fill" :style="{ width: overallPct + '%' }"></i></div>
        <p class="small">工时加权口径 · 已完成 {{ doneCount }} / {{ project.stories.length }} 个故事</p>
      </div>
      <div id="realtime" class="stats ov-realtime">
        <div class="stat"><label>完成比例</label><strong>{{ realtimePct }}%</strong><span class="small">{{ doneCount }} / {{ project.stories.length }} 故事</span></div>
        <div class="stat"><label>进行中</label><strong>{{ String(doingCount).padStart(2, '0') }}</strong><span class="small">个故事</span></div>
        <div class="stat"><label>待关注 / 阻塞</label><strong>{{ String(blockedCount).padStart(2, '0') }}</strong><span class="small">个任务需确认</span></div>
        <div class="stat"><label>最近更新</label><strong class="mono" style="font-size:20px">{{ lastUpdate }}</strong><span class="small">本机变更时间</span></div>
      </div>
    </div>

    <div class="h-sec">里程碑 · 关键交付节点</div>
    <div class="mile" id="mile-grid">
      <div v-if="!project.milestones.length" class="empty-state">暂无项目里程碑</div>
      <div v-for="m in project.milestones" :key="m.id" class="milecard" :class="{ done: m.status === 'done' }">
        <span class="wk">W{{ m.week }}</span><span class="mid">{{ m.id }}</span><h4>{{ m.name }}</h4><p>{{ m.desc }}</p>
      </div>
    </div>

    <button type="button" class="ov-insight-entry" @click="goInsight">
      <span class="ov-entry-ic" aria-hidden="true">◫</span>
      <span class="ov-entry-body">
        <b>项目分析中心</b>
        <small>进度预测 · 进度基线 · 风险预警 · 效率分析 · 质量分析 · Sprint 进度</small>
      </span>
      <span class="ov-entry-go" aria-hidden="true">→</span>
    </button>
  </section>
</template>

<style scoped>
.ov-hero { align-items: flex-end; }
.ov-hero-main { min-width: 0; }
.ov-chips { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.ov-chip { font-family: var(--mono); font-size: 11px; letter-spacing: .5px; border: 2px solid var(--ink); background: var(--paper-2); padding: 3px 8px; }
.ov-hero-acts { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.ov-stat label { display: flex; align-items: center; gap: 6px; }
.ov-ic { font-family: var(--mono); font-size: 13px; line-height: 1; border: 1.5px solid var(--ink); background: #fff; padding: 1px 4px; }

.ov-progress { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr); gap: 14px; align-items: stretch; }
.ov-progress-main { border: 2px solid var(--ink); background: var(--paper); box-shadow: var(--shadow); padding: 16px 18px 14px; display: flex; flex-direction: column; }
.ov-progress-label { font-family: var(--mono); font-size: 11px; letter-spacing: 1.5px; color: var(--muted); text-transform: uppercase; }
.ov-progress-num { display: block; font-family: var(--mono); font-size: 52px; line-height: 1.05; margin: 6px 0 2px; }
.ov-progress-main .small { margin-top: auto; padding-top: 8px; color: var(--muted); }
.stats.ov-realtime { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }

.ov-insight-entry { display: grid; grid-template-columns: 46px minmax(0, 1fr) 20px; align-items: center; gap: 14px; width: 100%; margin-top: 18px; padding: 16px 18px; font: inherit; color: var(--ink); text-align: left; border: 2px solid var(--ink); background: var(--green); box-shadow: var(--shadow); cursor: pointer; transition: transform .12s, box-shadow .12s; }
.ov-insight-entry:hover { transform: translate(-2px, -2px); box-shadow: 5px 5px 0 var(--ink); }
.ov-entry-ic { display: grid; place-items: center; width: 46px; height: 46px; border: 2px solid var(--ink); background: #fff; font-family: var(--mono); font-size: 22px; line-height: 1; }
.ov-entry-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.ov-entry-body b { font-size: 17px; letter-spacing: .5px; }
.ov-entry-body small { font-size: 12px; color: var(--ink-soft); }
.ov-entry-go { justify-self: end; font-family: var(--mono); font-size: 20px; }

@media (max-width: 900px){
  .ov-progress { grid-template-columns: 1fr; }
}
@media (max-width: 560px){
  .ov-progress-num { font-size: 42px; }
}
</style>
