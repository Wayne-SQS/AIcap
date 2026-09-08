<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { MEMBERS, MILESTONES } from '@/data/seed'
import GanttRow from '@/components/gantt/GanttRow.vue'

/* 甘特图视图:结构对齐旧版 L548-559,逻辑对齐 renderGantt()(L1000-1033)
   父行=看板卡(起止=子任务周并集,工时加权进度) · 子行缩进 · 管理虚线 · 独立任务区 · 里程碑 */
const project = useProjectStore()
const sprintLabel = ['', 'S1', 'S1', 'S2', 'S2', 'S3', 'S3']
const sprintOf = w => Math.ceil(w / 2)
const taskStatuses = ['待办', '进行中', '已完成', '已取消']

/* 任务条几何与提示:跨 Sprint 正常排期现象,仅工具提示说明 */
function barOf(t) {
  const span = t.w[1] - t.w[0] + 1
  const cross = sprintOf(t.w[0]) !== sprintOf(t.w[1])
  const st = t.status === 2 ? ' task-done' : (t.status === 3 ? ' mgmt' : '')
  return {
    left: ((t.w[0] - 1) / 6 * 100).toFixed(2),
    width: (span / 6 * 100).toFixed(2),
    title: `${t.id} ${t.name} · ${t.eh || t.h}h · 成员${t.owner + 1} · ${taskStatuses[t.status || 0]}${cross ? ' · 跨迭代实施(横跨两个 Sprint)' : ''}`,
    cls: `o${t.owner}${st}`,
    label: t.id,
    pct: null
  }
}

/* 开发任务按看板卡分组:父行(卡+加权进度) + 子行;管理任务独立虚线;独立任务解绑可见 */
const groups = computed(() => {
  const g = {}
  project.tasks.filter(t => t.type !== 'management' && t.card).forEach(t => { (g[t.card] ??= []).push(t) })
  return g
})
const mgmts = computed(() => project.tasks.filter(t => t.type === 'management'))
const orphans = computed(() => project.tasks.filter(t => t.type !== 'management' && !t.card))

const groupRows = computed(() => Object.entries(groups.value).map(([cardId, subs]) => {
  const s = project.stories.find(x => x.id === cardId)
  const w0 = Math.min(...subs.map(t => t.w[0])), w1 = Math.max(...subs.map(t => t.w[1]))
  const pct = Math.round(project.cardPct(s || { status: 0, id: cardId }) * 100)
  const eh = subs.reduce((a, t) => a + (t.eh || t.h), 0)
  return {
    parent: {
      title: cardId + ' ' + (s ? s.title : ''),
      sub: `父卡 · ${subs.length} 子任务 · ${eh}h · 加权 ${pct}%`,
      bar: {
        left: ((w0 - 1) / 6 * 100).toFixed(2),
        width: ((w1 - w0 + 1) / 6 * 100).toFixed(2),
        title: `看板卡 ${cardId} · ${s ? s.title : ''} · ${subs.length} 条子任务 · ${eh}h · 工时加权进度 ${pct}%`,
        cls: 'parent',
        label: `${cardId} · ${pct}%`,
        pct
      }
    },
    children: subs.map(t => ({
      title: t.name,
      sub: `${t.id} · 成员${t.owner + 1} · ${t.eh || t.h}h${t.story ? ' · ' + t.story : ''}${t.status === 2 ? ' · ✓完成' : (t.status === 1 ? ' · ▶进行中' : '')}`,
      bar: barOf(t)
    }))
  }
}))
const orphanChildren = computed(() => orphans.value.map(t => ({
  title: t.name,
  sub: `${t.id} · 成员${t.owner + 1} · ${t.eh || t.h}h · ${taskStatuses[t.status || 0]}`,
  bar: barOf(t)
})))
</script>

<template>
  <section class="view" id="view-gantt">
    <div class="hero">
      <div>
        <div class="eyebrow">GANTT CHART / 6 周排期 · 强制血缘</div>
        <h1>甘特图</h1>
        <p>开发任务强制挂载看板卡(父条=子任务并集,工时加权进度) · 管理任务独立虚线 · 4 个里程碑。</p>
      </div>
      <span class="demo">Sprint 1 = W1–W2 · Sprint 2 = W3–W4 · Sprint 3 = W5–W6</span>
    </div>
    <div class="gantt-wrap">
      <div class="gantt" id="gantt">
        <div class="gantt-head">
          <div class="taskh">任务 / 责任人</div>
          <div v-for="w in 6" :key="w" class="wk">W{{ w }}<small>{{ sprintLabel[w] }}</small></div>
        </div>
        <template v-for="g in groupRows" :key="g.parent.title">
          <GanttRow variant="parent" :title="g.parent.title" :sub="g.parent.sub" :bar="g.parent.bar" />
          <GanttRow v-for="(c, i) in g.children" :key="g.parent.title + '-' + i" variant="child" :title="c.title" :sub="c.sub" :bar="c.bar" />
        </template>
        <GanttRow v-for="(t, i) in mgmts" :key="'mgmt-' + i" variant="mgmt" :title="t.name" :sub="`${t.id} · 管理 · ${t.eh || t.h}h`" :bar="barOf(t)" />
        <template v-if="orphans.length">
          <GanttRow variant="mgmt" title="独立任务" :sub="`已解绑/未挂卡的开发任务 · ${orphans.length} 条`" :bar="null" />
          <GanttRow v-for="(c, i) in orphanChildren" :key="'orphan-' + i" variant="child" :title="c.title" :sub="c.sub" :bar="c.bar" />
        </template>
        <div class="mile-row">
          <div class="tname" style="border-right:2px solid var(--line);padding:8px 10px;font-weight:bold;display:flex;align-items:center">◆ 里程碑</div>
          <div class="mile-track">
            <span v-for="m in MILESTONES" :key="m.id" class="mlabel" :style="{ left: ((m.week - 0.5) / 6 * 100).toFixed(2) + '%' }">{{ m.id }} {{ m.name }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="legend" id="gantt-legend">
      <span v-for="m in MEMBERS" :key="m.id" class="chip">
        <span class="sw" :style="{ background: `var(--${['green', 'orange', 'blue', 'pink'][m.id]})` }"></span>{{ m.name }} · {{ m.role }}
      </span>
      <span class="chip"><span class="sw" style="background:var(--paper-2);border:1px dashed var(--muted)"></span>◇ 管理任务</span>
      <span class="chip" style="margin-left:auto"><span class="sw" style="background:var(--yellow)"></span>◆ 里程碑</span>
    </div>
  </section>
</template>
