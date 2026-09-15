<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { statuses, activities } from '@/constants'
import { memberLabel } from '@/data/memberIdentity'

/* 看板卡片:.card[data-id][draggable] + 子任务工时加权进度徽章 .subprog(血缘口径保留)
   map 模式:加 .mapcard 固定卡高 + 状态旁显示 Sprint(对齐 legacy 新版卡片);
     可拖到别的格子里改「骨干活动 / 发布切片」(旧版原型是 draggable=false,即地图只读)。
   compact 总览模式:降级为单行小矩形 .chip(马甲标记 + 编号 + 标题),标题换行显示不截断。
   skin:马甲呈现({tone,glyph,label,detail}),由 StoryMapGrid 计算后传入 —— 卡片自身不认识"马甲"这个概念,
     也保证状态看板(不传 skin)保持原样。
   只读账号:卡片(编辑入口)禁用且不可拖拽,保持可见并说明原因 */
const props = defineProps({
  story: { type: Object, required: true },
  map: { type: Boolean, default: false },
  compact: { type: Boolean, default: false },
  skin: { type: Object, default: null }
})
const emit = defineEmits(['open'])
const project = useProjectStore()
const session = useSessionStore()

const isViewer = computed(() => session.isViewer)
/* 马甲标签进 title,让"颜色"之外还有文字通道(色盲友好:不得只靠色相传达语义) */
const skinText = computed(() => (props.skin ? [props.skin.label, props.skin.detail].filter(Boolean).join(' · ') : ''))
const cardTitle = computed(() => {
  const base = isViewer.value ? READONLY_TITLE : '验收：' + props.story.acceptance
  return skinText.value ? base + ' · ' + skinText.value : base
})

const subs = computed(() => project.subTasksOf(props.story.id))
const sp = computed(() => subs.value.length ? Math.round(project.cardPct(props.story) * 100) : null)
const badgeTitle = computed(() => `子任务工时加权进度 ${sp.value}%${subs.value.length ? ` · ${subs.value.filter(t => t.status === 2).length}/${subs.value.length} 条完成` : ''}`)
const ownerName = computed(() => (props.story.owner == null ? '未分配' : project.memberName(props.story.owner)))

/* 拖拽载荷:状态看板用它换列改状态,故事地图用它换格改「骨干活动 / 发布切片」——
   同一个 dataTransfer 载荷,落点自己解释语义 */
function onDragStart(e) {
  e.dataTransfer.setData('text/plain', props.story.id)
  e.dataTransfer.effectAllowed = 'move'
}
</script>

<template>
  <!-- 总览:单行小矩形 -->
  <button
    v-if="compact"
    type="button"
    class="card chip" :class="skin && skin.tone"
    :draggable="!isViewer"
    :disabled="isViewer"
    :data-id="story.id"
    :data-skin-key="skin && skin.glyph"
    :title="cardTitle"
    :aria-label="'编辑 ' + story.id + ' ' + story.title"
    @click="emit('open', story.id)"
    @dragstart="onDragStart"
  >
    <i class="chipmark" aria-hidden="true">{{ skin ? skin.glyph : '·' }}</i>
    <b class="chipid">{{ story.id }}</b>
    <span class="chiptitle">{{ story.title }}</span>
  </button>

  <!-- 详细:原有卡片,马甲只增一条左侧色条 + 一个字形标记 -->
  <button
    v-else
    type="button"
    class="card" :class="[skin && skin.tone, { mapcard: map }]"
    :draggable="!isViewer"
    :disabled="isViewer"
    :data-id="story.id"
    :data-skin-key="skin && skin.glyph"
    :title="cardTitle"
    :aria-label="'编辑 ' + story.id + ' ' + story.title"
    @click="emit('open', story.id)"
    @dragstart="onDragStart"
  >
    <div class="cardtop">
      <span v-if="skin" class="sking" :title="skinText">{{ skin.glyph }}</span>
      <span class="id">{{ story.id }}</span><span class="tag" :class="story.priority">{{ story.priority }}</span>
    </div>
    <h3>{{ story.title }}</h3>
    <p class="carddesc">{{ story.description }}</p>
    <p v-if="map" class="mapstatus">{{ statuses[story.status] }} · Sprint {{ story.sprint }}</p>
    <div class="cardfoot">
      <span>A{{ story.activity }} · {{ activities[story.activity - 1] }}</span>
      <span class="owner">
        <span v-if="sp !== null" class="subprog" :class="{ full: sp >= 100 }" :title="badgeTitle">▣ {{ subs.filter(t => t.status === 2).length }}/{{ subs.length }} · {{ sp }}%</span>
        <span class="avatar" :class="'a' + (story.owner ?? 4)">{{ story.owner == null ? '—' : memberLabel(story.owner) }}</span>
        <span>{{ ownerName }}</span>
      </span>
    </div>
  </button>
</template>
