<script setup>
import { computed, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const projects = useProjectsStore()
const session = useSessionStore()
const agents = [
  { id: 'meeting', icon: '◉', name: '会议智能体', subtitle: 'MEETING AGENT', description: '录入会议文本或语音，选择分析类型，提取决议、行动项和项目建议。', input: '会议文本 / 录音 / 已保存会议', output: '会议分析、行动项、待审核提案', impact: '提案经人工审核和执行后更新项目。', color: 'var(--green)', component: defineAsyncComponent(() => import('./MeetingView.vue')) },
  { id: 'commit', icon: '↗', name: '任务提交智能体', subtitle: 'COMMIT AGENT', description: '同步 GitHub 开发活动，结合项目任务分析提交记录、工作进展和风险。', input: 'GitHub 活动 / 项目任务 / 时间范围', output: '活动摘要、提交风险、协调建议', impact: '同步活动并保存分析；协调建议可单独送审。', color: 'var(--orange)', component: defineAsyncComponent(() => import('@/components/ai/SubmitAgentPanel.vue')) },
  { id: 'profile', icon: '◌', name: '成员画像智能体', subtitle: 'PROFILE AGENT', description: '从真实活动和任务数据分析成员能力、任务难度和团队负载，查看每条结论的依据。', input: '成员活动 / 任务数据 / 时间范围', output: '成员能力画像、负载分析、团队风险', impact: '保存画像分析；任务分配由人工确认。', color: 'var(--blue)', component: defineAsyncComponent(() => import('@/components/ai/ProfileAgentPanel.vue')) },
  { id: 'planning', icon: '✎', name: '项目规划智能体', subtitle: 'PLANNING AGENT', description: '用自然语言查询或调整负责人、任务排期、依赖和里程碑，先查看方案与影响分析。', input: '自然语言指令 / 当前项目数据', output: '项目规划草案、修改清单、影响分析', impact: '点击确认执行后，同步更新项目视图。', color: 'var(--lilac)', component: defineAsyncComponent(() => import('@/components/ai/PlanningAgentPanel.vue')) },
  { id: 'generator', icon: '◇', name: '项目生成智能体', subtitle: 'PROJECT GENERATOR', description: '描述项目目标或导入表格，生成需求、任务、排期和依赖草案。', input: '项目描述 / Excel / CSV', output: '项目规划草案、用户故事、任务和排期', impact: '核对草案并确认后，写入当前项目。', color: 'var(--yellow)', component: defineAsyncComponent(() => import('./ProjectGeneratorView.vue')) },
  { id: 'efficiency', icon: '⌁', name: '项目效率分析', subtitle: 'EFFICIENCY ANALYSIS / US23', description: '基于当前项目任务、核心依赖、成员容量、状态历史和真实活动识别效率瓶颈。', input: '当前项目真实数据 / 成员画像 / 已同步活动', output: '有证据的瓶颈、原因与待审核优化建议', impact: '建议只进入审核，不会未经确认修改正式项目数据。', color: 'var(--orange)', component: defineAsyncComponent(() => import('@/components/ai/EfficiencyAnalysisPanel.vue')) }
]
const selected = computed(() => agents.find(agent => agent.id === route.query.agent) || agents[0])
const contextKey = computed(() => `${projects.currentProjectId || ''}:${session.currentUser?.id || ''}:${session.online}`)
const canInvoke = computed(() => session.online && Boolean(projects.currentProjectId))

function selectAgent(id) {
  // Only agent selection survives: meeting/analysis query parameters belong to the previous panel.
  router.replace({ query: { agent: id } })
}
function openReview() {
  const scoped = Boolean(projects.currentProjectId)
  router.push({ name: scoped ? 'project-review' : 'review', params: scoped ? { projectId: projects.currentProjectId } : undefined })
}
</script>

<template>
  <section class="view agent-center" id="view-agents">
    <header class="hero">
      <div><div class="eyebrow">AGENT CENTER / 智能协作</div><h1>智能体调用中心</h1><p>选择一个智能体，在这里发起任务、查看结果并跟进处理。</p></div>
      <button type="button" @click="openReview">提案审核中心 ↗</button>
    </header>

    <div class="agent-context" aria-label="当前调用环境">
      <span><span class="status-dot" :class="{ online: session.online }"></span>{{ session.online ? '在线服务' : session.apiMode ? '等待登录' : '离线模式' }}</span>
      <span>项目：<b>{{ projects.currentProject?.name || projects.currentProjectId || '未选择项目' }}</b></span>
      <span v-if="session.currentUser">角色：{{ session.roleName(session.currentUser.role) }}</span>
      <span class="context-count">{{ agents.length }} 个智能体</span>
    </div>

    <div class="agent-layout">
      <nav class="agent-picker" aria-label="选择智能体">
        <div class="eyebrow picker-title">选择能力 / AGENTS</div>
        <button v-for="agent in agents" :key="agent.id" type="button" class="agent-choice" :class="{ selected: selected.id === agent.id }" :aria-pressed="selected.id === agent.id" :style="{ '--agent-color': agent.color }" @click="selectAgent(agent.id)">
          <span class="agent-symbol" aria-hidden="true">{{ agent.icon }}</span><span><strong>{{ agent.name }}</strong><small>{{ agent.subtitle }}</small></span><span class="choice-arrow" aria-hidden="true">↗</span>
        </button>
      </nav>

      <section class="agent-workspace" aria-labelledby="selected-agent-title">
        <header class="agent-heading" :style="{ '--agent-color': selected.color }">
          <div class="eyebrow">{{ selected.subtitle }}</div><h2 id="selected-agent-title">{{ selected.name }}</h2><p>{{ selected.description }}</p>
          <dl class="agent-contract"><div><dt>输入</dt><dd>{{ selected.input }}</dd></div><div><dt>产出</dt><dd>{{ selected.output }}</dd></div></dl>
          <p class="agent-impact">{{ selected.impact }}</p>
        </header>

        <div v-if="!canInvoke" class="agent-unavailable" role="status">
          <strong>{{ !session.online ? '连接在线服务后即可调用智能体' : '请先选择当前项目' }}</strong>
          <p>{{ !session.online ? '登录后使用当前项目的真实数据发起分析，运行结果会显示在此处。' : '智能体会使用所选项目的数据，请先进入项目或在左侧切换项目。' }}</p>
          <button v-if="!session.online" class="primary" type="button" @click="session.openLogin()">登录在线服务</button>
          <button v-else type="button" @click="router.push({ name: 'projects' })">选择项目</button>
        </div>
        <div v-else class="agent-content" :aria-label="`${selected.name}操作区`">
          <component :is="selected.component" :key="`${contextKey}:${selected.id}`" embedded />
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.agent-context{display:flex;align-items:center;flex-wrap:wrap;gap:12px 24px;border:2px solid var(--ink);background:var(--paper-2);padding:12px 16px;margin-bottom:24px;font-size:13px}.agent-context>span:first-child{display:flex;align-items:center;gap:8px}.status-dot{width:8px;height:8px;background:var(--orange);border:1px solid var(--ink)}.status-dot.online{background:var(--green-2)}.context-count{margin-left:auto;font-family:var(--mono);color:var(--muted)}
.agent-layout{display:grid;grid-template-columns:230px minmax(0,1fr);gap:24px;align-items:start}.agent-picker{display:grid;gap:12px}.picker-title{margin-bottom:3px}.agent-choice{display:flex;align-items:center;gap:12px;width:100%;padding:14px 12px;text-align:left;box-shadow:none;background:var(--paper);border-color:var(--line)}.agent-choice.selected{border-color:var(--ink);background:var(--agent-color);box-shadow:var(--shadow)}.agent-symbol{font-size:25px;line-height:1;width:28px;text-align:center;flex-shrink:0}.agent-choice strong{display:block;font-size:14px}.agent-choice small{font:10px/1.8 var(--mono);color:var(--ink-soft)}.choice-arrow{margin-left:auto}.agent-workspace{min-width:0;border:2px solid var(--ink);background:var(--paper);box-shadow:var(--shadow)}.agent-heading{padding:22px;border-bottom:2px solid var(--ink);border-top:7px solid var(--agent-color)}.agent-heading h2{margin:7px 0;font-size:23px}.agent-heading p{margin:8px 0;font-size:14px}.agent-contract{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:20px 0 12px}.agent-contract dt{font-size:12px;color:var(--muted);font-weight:700}.agent-contract dd{font-size:13px;margin:4px 0 0}.agent-heading .agent-impact{border-top:1px dashed var(--line);padding-top:12px;color:var(--ink-soft);font-size:12px}.agent-content,.agent-unavailable{padding:22px;min-width:0}.agent-unavailable{min-height:230px;display:flex;flex-direction:column;align-items:flex-start;justify-content:center}.agent-unavailable p{font-size:14px;color:var(--muted);max-width:470px}.agent-content :deep(.embedded-agent-view){padding:0;animation:none}.agent-content :deep(.sum-grid){grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr))}
@media(max-width:1100px){.agent-layout{grid-template-columns:1fr}.agent-picker{grid-template-columns:repeat(auto-fit,minmax(175px,1fr))}.picker-title{grid-column:1/-1}.agent-choice{height:76px}}
@media(max-width:600px){.agent-picker{grid-template-columns:1fr}.agent-choice{height:auto}.agent-contract{grid-template-columns:1fr}.agent-heading,.agent-content,.agent-unavailable{padding:16px}.context-count{margin-left:0}}
</style>
