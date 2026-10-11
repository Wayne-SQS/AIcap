<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useCanvasZoom } from '@/composables/useCanvasZoom'
import CanvasZoomControls from '@/components/CanvasZoomControls.vue'

/* UML 图(真实 SVG,移植自 legacy/index.html renderUML())
   用例图:4 角色 / 15 用例,坐标与连线切点算法逐字沿用 legacy;
   时序图:「登录并加载项目工作台」14 条消息,参与者泳道与激活条沿用 legacy 几何。

   与 legacy 原版的差异(有意为之):
   1) 用例名与点击明细来自 Pinia project.stories(响应式 computed),不再是静态常量;
   2) 后端已由 FastAPI 迁移为 Spring Boot 3 + Java 23,参与者与消息按 java-backend 真实路由重写
      (POST /api/auth/login · GET /api/auth/me · GET /api/auth/users ·
       GET /api/stories[|/logs] · GET /api/tasks · GET /api/pool),不虚构路由;
   3) 交互改为 Vue 声明式(hover/selected ref + :class 绑定 + 键盘可达),不直接操作 DOM、不弹窗。

   注:legacy 用 storyTitle(id,fallback) 从 stories 取用例名,当故事基线里没有该编号时回退到 fallback,
   这里保持同一口径,只是数据源换成了响应式 store。 */

const project = useProjectStore()
const UML_LAYOUT_KEY = 'aiguanli-uml-layout-v2'

/* ==================== 用例图 ==================== */
const MIN_USECASE_WIDTH = 140
const MAX_USECASE_WIDTH = 220
const USECASE_GAP_X = 72
const USECASE_GAP_Y = 34
const USECASE_TOP = 112
const SYSTEM_X = 175
const SYSTEM_WIDTH = 850
const SYSTEM_TOP = 28
const SYSTEM_SIDE_PADDING = 56
const SYSTEM_BOTTOM_PADDING = 48
const LEGEND_HEIGHT = 44
const LEGEND_GAP = 16

const ACTORS = [
  { id: 'admin', name: '管理员', x: 72, y: 125, side: 'left' },
  { id: 'dri', name: '项目负责人 / DRI', x: 72, y: 405, side: 'left' },
  { id: 'member', name: '团队成员', x: 1105, y: 210, side: 'right' },
  { id: 'viewer', name: '普通用户 / 数据查看者', x: 1105, y: 525, side: 'right' }
]
const actors = reactive(ACTORS.map(a => ({ ...a })))

const usecaseLayout = reactive([])

function usecaseLines(title) {
  const text = String(title || '未命名故事')
  const maxChars = text.length > 10 ? 9 : 12
  const lines = []
  for (let i = 0; i < text.length; i += maxChars) lines.push(text.slice(i, i + maxChars))
  return lines.length ? lines : ['未命名故事']
}

function usecaseMetrics(title) {
  const lines = usecaseLines(title)
  const width = Math.min(MAX_USECASE_WIDTH, Math.max(MIN_USECASE_WIDTH, 54 + Math.max(...lines.map(line => line.length)) * 12))
  const height = lines.length > 1 ? 82 : 66
  return { lines, width, height, rx: width / 2, ry: height / 2 }
}

function autoUsecasePositions(stories) {
  const count = stories.length
  const columns = count <= 4 ? 1 : 2
  const rows = Math.max(1, Math.ceil(count / columns))
  const items = stories.map(story => ({ story, metrics: usecaseMetrics(story.title) }))
  const columnWidths = Array.from({ length: columns }, (_, column) => Math.max(
    ...items.filter((_, index) => index % columns === column).map(item => item.metrics.width),
    MIN_USECASE_WIDTH
  ))
  const totalWidth = columnWidths.reduce((sum, width) => sum + width, 0) + USECASE_GAP_X * (columns - 1)
  const startX = SYSTEM_X + (SYSTEM_WIDTH - totalWidth) / 2
  const xByColumn = []
  let cursor = startX
  for (const width of columnWidths) {
    xByColumn.push(cursor + width / 2)
    cursor += width + USECASE_GAP_X
  }
  const rowHeights = Array.from({ length: rows }, (_, row) => Math.max(
    ...items.filter((_, index) => Math.floor(index / columns) === row).map(item => item.metrics.height),
    66
  ))
  const yByRow = []
  let y = USECASE_TOP
  for (const height of rowHeights) {
    yByRow.push(y + height / 2)
    y += height + USECASE_GAP_Y
  }
  return stories.map((story, index) => {
    const column = index % columns
    const row = Math.floor(index / columns)
    const metrics = items[index].metrics
    return { x: xByColumn[column], y: yByRow[row], ...metrics }
  })
}

function syncUsecaseLayout(stories) {
  const current = new Map(usecaseLayout.map(item => [item.id, item]))
  const positions = autoUsecasePositions(stories)
  const next = stories.map((story, index) => {
    const saved = current.get(story.id)
    const position = positions[index]
    return saved
      ? Object.assign(saved, { ref: story.id, storyId: story.id, stories: [story.id], fallback: story.title, type: 'core', ...position })
      : { id: story.id, ref: story.id, storyId: story.id, stories: [story.id], fallback: story.title, type: 'core', ...position }
  })
  usecaseLayout.splice(0, usecaseLayout.length, ...next)
}

const usecaseBounds = computed(() => {
  const nodes = usecases.value
  const maxBottom = nodes.length ? Math.max(...nodes.map(node => node.y + node.ry)) : USECASE_TOP
  const boundaryBottom = maxBottom + SYSTEM_BOTTOM_PADDING
  const boundaryHeight = boundaryBottom - SYSTEM_TOP
  const legendY = boundaryBottom + LEGEND_GAP
  return {
    boundary: { x: SYSTEM_X, y: SYSTEM_TOP, width: SYSTEM_WIDTH, height: boundaryHeight },
    legendY,
    viewBox: `0 0 1200 ${legendY + LEGEND_HEIGHT + 12}`
  }
})

/* 角色 → 用例 的关联(用例图连线) */
const relationIds = computed(() => {
  const ids = usecaseLayout.map(item => item.id)
  return {
    admin: ids.slice(0, 2),
    dri: ids,
    member: ids.slice(0, Math.min(ids.length, 3)),
    viewer: ids.slice(0, Math.min(ids.length, 1))
  }
})

const LEGEND = [
  ['admin', '管理员'],
  ['dri', '项目负责人 / DRI'],
  ['member', '团队成员'],
  ['viewer', '普通用户 / 数据查看者']
]

const STATUS_TXT = { 0: '待办', 1: '进行中', 2: '已完成' }

/* 用例名取自故事标题(响应式):故事数据变化时用例名与明细同步更新 */
function storyTitle(id, fallback) {
  return project.stories.find(s => s.id === id)?.title || fallback
}

const usecases = computed(() => usecaseLayout.map(u => ({ ...u, name: storyTitle(u.storyId, u.fallback), ref: u.storyId })))
const usecaseById = computed(() => Object.fromEntries(usecases.value.map(u => [u.id, u])))
const actorById = computed(() => Object.fromEntries(actors.map(a => [a.id, a])))

/* 用例图连线：统一锚点，但每条 Actor → Use Case 关系独立绘制。 */
const ACTOR_ANCHOR_OFFSET = 22

function actorAnchor(actor) {
  return {
    x: Math.round(actor.x + (actor.side === 'left' ? ACTOR_ANCHOR_OFFSET : -ACTOR_ANCHOR_OFFSET)),
    y: Math.round(actor.y - 22)
  }
}

/* 求 Actor 锚点到椭圆边界的交点，保证端点落在用例轮廓上。 */
function ellipsePoint(anchor, usecase) {
  const dx = anchor.x - usecase.x
  const dy = anchor.y - usecase.y
  const scale = 1 / Math.sqrt((dx * dx) / (usecase.rx * usecase.rx) + (dy * dy) / (usecase.ry * usecase.ry))
  return { x: usecase.x + dx * scale, y: usecase.y + dy * scale }
}

const associations = computed(() => Object.entries(relationIds.value).flatMap(([actorId, ids]) => ids.map(id => {
  const actor = actorById.value[actorId]
  const usecase = usecaseById.value[id]
  if (!actor || !usecase) return null
  const anchor = actorAnchor(actor)
  const edge = ellipsePoint(anchor, usecase)
  return {
    key: `${actorId}-${id}`,
    actorId,
    usecaseId: id,
    d: `M ${anchor.x} ${anchor.y} L ${Math.round(edge.x)} ${Math.round(edge.y)}`
  }
})).filter(Boolean))

/* ---------- 交互:hover 高亮 + 点击选中(键盘可达) ---------- */
const hover = ref(null)        // { kind:'usecase' | 'actor', id } | null
const selectedId = ref('')     // 选中的用例 id

function setHover(kind, id) { hover.value = { kind, id } }
function clearHover() { hover.value = null }

const hoverActorId = computed(() => (hover.value?.kind === 'actor' ? hover.value.id : ''))
const hoverUsecaseId = computed(() => (hover.value?.kind === 'usecase' ? hover.value.id : ''))

/* 悬停角色 → 它的全部用例点亮;悬停用例 → 它自己点亮 */
const ucOn = u => hoverUsecaseId.value === u.id || (relationIds.value[hoverActorId.value] || []).includes(u.id)
const actorOn = a => hoverActorId.value === a.id || (relationIds.value[a.id] || []).includes(hoverUsecaseId.value)
const assocOn = l => (hoverActorId.value && l.actorId === hoverActorId.value) ||
  (hoverUsecaseId.value && l.usecaseId === hoverUsecaseId.value)

function toggleUc(id) { selectedId.value = selectedId.value === id ? '' : id }
function clearSelection() { selectedId.value = '' }

const selectedUc = computed(() => usecases.value.find(u => u.id === selectedId.value) || null)
const selectedStories = computed(() => {
  const uc = selectedUc.value
  if (!uc) return []
  return uc.stories.map(id => ({ id, story: project.stories.find(s => s.id === id) || null }))
})

/* ==================== 时序图 ==================== */
const TOP = 116
const BOTTOM = 1025
const ROW_START = 210
const ROW_GAP = 58

const PARTICIPANTS = [
  { id: 'user', name: '用户', x: 130, actor: true },
  { id: 'frontend', name: '爱管理前端', x: 370 },
  { id: 'backend', name: 'Spring Boot 后端', x: 625 },
  { id: 'auth', name: '认证 / 权限模块', sub: 'JWT 拦截器', x: 895 },
  { id: 'db', name: 'MySQL 数据库', x: 1160 }
]
const participants = reactive(PARTICIPANTS.map(p => ({ ...p })))

/* 消息:[发出方, 接收方, 文案, 明细行(数组), 类型];路由均取自 java-backend 控制器 */
const MSG = [
  ['user', 'frontend', '提交登录信息', [], 'request'],
  ['frontend', 'backend', '登录请求', ['POST /api/auth/login'], 'request'],
  ['backend', 'db', '按用户名查询用户', ['users 表 · username'], 'request'],
  ['db', 'backend', '返回用户记录', ['User 实体 · password_hash'], 'return'],
  ['backend', 'auth', '校验密码并签发 JWT', ['PasswordUtil.verify → JwtUtil.createToken'], 'request'],
  ['auth', 'backend', '返回 Token 与用户信息', ['TokenOut：access_token · token_type · user'], 'return'],
  ['backend', 'frontend', '返回 Bearer Token', ['200 JSON（snake_case 字段契约）'], 'return'],
  ['frontend', 'backend', '携带 Bearer 加载工作台', ['GET /api/stories · /api/tasks · /api/pool', 'GET /api/stories/logs · /api/auth/users'], 'request'],
  ['backend', 'auth', '校验 Bearer Token', ['AuthInterceptor.preHandle（JWT 拦截器）'], 'request'],
  ['auth', 'backend', '注入当前用户 AuthContext', ['GET /api/auth/me'], 'return'],
  ['backend', 'db', '查询故事 / 任务 / 需求池 / 成员', ['MyBatis-Plus 查询'], 'request'],
  ['db', 'backend', '返回项目数据', ['US01-US37 共 37 条故事基线'], 'return'],
  ['backend', 'frontend', '返回各接口 JSON', ['snake_case 字段 · 200 OK'], 'return'],
  ['frontend', 'user', '渲染看板 / 故事地图 / 甘特 / 成员视图', [], 'return']
]

/* 激活条(时间轴上的执行区间):与 MSG 下标一一对应 */
const ACTIVATIONS = [
  { id: 'frontend', from: 0, to: 13 }, { id: 'backend', from: 1, to: 6 },
  { id: 'db', from: 2, to: 3 }, { id: 'auth', from: 4, to: 5 },
  { id: 'backend', from: 7, to: 12 }, { id: 'auth', from: 8, to: 9 }, { id: 'db', from: 10, to: 11 }
]

const participantById = computed(() => Object.fromEntries(participants.map(p => [p.id, p])))

function activationEdge(participantId, messageIndex, direction) {
  const p = participantById.value[participantId]
  const active = ACTIVATIONS.find(a => a.id === participantId && messageIndex >= a.from && messageIndex <= a.to)
  if (!active) return p.x
  return p.x + (direction === 'right' ? 5 : -5)
}

const activationBars = computed(() => ACTIVATIONS.map(a => {
  const p = participantById.value[a.id]
  return { key: `${a.id}-${a.from}`, x: p.x - 5, y: ROW_START + a.from * ROW_GAP - 8, height: (a.to - a.from) * ROW_GAP + 18 }
}))

/* 文案+明细整体贴着箭头向上排布,最多 2 行明细,不越过上一条消息 */
const messages = computed(() => MSG.map(([from, to, label, details, type], i) => {
  const a = participantById.value[from], b = participantById.value[to]
  const y = ROW_START + i * ROW_GAP
  const direction = b.x > a.x ? 'right' : 'left'
  const startX = activationEdge(from, i, direction)
  const endX = activationEdge(to, i, direction === 'right' ? 'left' : 'right')
  return {
    i, from, to, label, details, type, y, startX, endX,
    labelX: (startX + endX) / 2,
    labelY: y - 10 - 14 * details.length
  }
}))

/* UML 拖拽只保存视觉坐标，不修改 Story / Task / Member 业务数据。 */
const usecaseSvg = ref(null)
const sequenceSvg = ref(null)
const sequenceDrawing = ref(null)
const usecaseViewport = ref(null)
const usecaseShell = ref(null)
const sequenceViewport = ref(null)
const sequenceShell = ref(null)
const { zoom: usecaseZoom, zoomMode: usecaseZoomMode, minZoom: usecaseMinZoom, maxZoom: usecaseMaxZoom, canvasStyle: usecaseCanvasStyle, shellStyle: usecaseShellStyle, zoomIn: usecaseZoomIn, zoomOut: usecaseZoomOut, setZoomPercent: setUsecaseZoomPercent, resetZoom: usecaseResetZoom, fitToCanvas: usecaseFitToCanvas } =
  useCanvasZoom({ viewport: usecaseViewport, canvas: usecaseSvg, shell: usecaseShell, renderMode: 'dimensions' })
const { zoom: sequenceZoom, zoomMode: sequenceZoomMode, minZoom: sequenceMinZoom, maxZoom: sequenceMaxZoom, canvasStyle: sequenceCanvasStyle, shellStyle: sequenceShellStyle, zoomIn: rawSequenceZoomIn, zoomOut: rawSequenceZoomOut, setZoomPercent: rawSetSequenceZoomPercent, resetZoom: rawSequenceResetZoom, fitToCanvas: rawSequenceFitToCanvas } =
  useCanvasZoom({ viewport: sequenceViewport, canvas: sequenceSvg, shell: sequenceShell, fitPadding: 0 })
const SEQUENCE_VIEWBOX_DEFAULT = '0 0 1290 1050'
const SEQUENCE_FRAME_DEFAULT = { x: 20, y: 10, width: 1250, height: 1025 }
const sequenceViewBox = ref(SEQUENCE_VIEWBOX_DEFAULT)
const sequenceFrame = ref({ ...SEQUENCE_FRAME_DEFAULT })
const sequenceContentBounds = ref(null)
const layoutDrag = ref(null)
const suppressClick = ref(false)

function restoreSequenceCanvas() {
  sequenceViewBox.value = SEQUENCE_VIEWBOX_DEFAULT
  sequenceFrame.value = { ...SEQUENCE_FRAME_DEFAULT }
}
function sequenceZoomIn() {
  restoreSequenceCanvas()
  rawSequenceZoomIn()
}
function sequenceZoomOut() {
  restoreSequenceCanvas()
  rawSequenceZoomOut()
}
function setSequenceZoomPercent(percent) {
  restoreSequenceCanvas()
  return rawSetSequenceZoomPercent(percent)
}
async function sequenceResetZoom() {
  restoreSequenceCanvas()
  await nextTick()
  rawSequenceResetZoom()
}
async function sequenceFitToCanvas() {
  await nextTick()
  const drawing = sequenceDrawing.value
  if (!drawing) return
  const bbox = drawing.getBBox()
  if (!bbox.width || !bbox.height) return
  const padding = 4
  const framePadding = 4
  sequenceContentBounds.value = { x: bbox.x, y: bbox.y, width: bbox.width, height: bbox.height }
  sequenceViewBox.value = `${bbox.x - padding} ${bbox.y - padding} ${bbox.width + padding * 2} ${bbox.height + padding * 2}`
  sequenceFrame.value = {
    x: bbox.x - framePadding,
    y: bbox.y - framePadding,
    width: bbox.width + framePadding * 2,
    height: bbox.height + framePadding * 2
  }
  await nextTick()
  requestAnimationFrame(() => rawSequenceFitToCanvas())
}

onMounted(() => {
  if (sequenceZoomMode.value === 'fit') sequenceFitToCanvas()
})

function loadUmlLayout() {
  try {
    const saved = JSON.parse(localStorage.getItem(UML_LAYOUT_KEY))
    saved?.actors?.forEach(pos => { const item = actors.find(a => a.id === pos.id); if (item) Object.assign(item, pos) })
    saved?.usecases?.forEach(pos => { const item = usecaseLayout.find(u => u.id === pos.id); if (item) Object.assign(item, pos) })
    saved?.participants?.forEach(pos => { const item = participants.find(p => p.id === pos.id); if (item) item.x = pos.x })
  } catch { /* invalid layout falls back to defaults */ }
}
function saveUmlLayout() {
  localStorage.setItem(UML_LAYOUT_KEY, JSON.stringify({
    actors: actors.map(({ id, x, y }) => ({ id, x, y })),
    usecases: usecaseLayout.map(({ id, x, y }) => ({ id, x, y })),
    participants: participants.map(({ id, x }) => ({ id, x }))
  }))
}
watch(() => project.stories.map(story => story.id), ids => {
  syncUsecaseLayout(ids.map(id => project.stories.find(story => story.id === id)).filter(Boolean))
}, { immediate: true })
loadUmlLayout()

function svgPoint(event, svg) {
  const point = svg.createSVGPoint()
  point.x = event.clientX
  point.y = event.clientY
  return point.matrixTransform(svg.getScreenCTM().inverse())
}
function updateUmlLayout(diagram, id, position) {
  if (diagram === 'actor') Object.assign(actors.find(a => a.id === id), position)
  if (diagram === 'usecase') Object.assign(usecaseLayout.find(u => u.id === id), position)
  if (diagram === 'participant') Object.assign(participants.find(p => p.id === id), position)
}
function beginLayoutDrag(event, diagram, item) {
  const svg = diagram === 'participant' ? sequenceSvg.value : usecaseSvg.value
  const point = svgPoint(event, svg)
  layoutDrag.value = { diagram, id: item.id, svg, dx: point.x - item.x, dy: point.y - (item.y || 0), moved: false }
  document.addEventListener('pointermove', moveLayout)
  document.addEventListener('pointerup', endLayoutDrag, { once: true })
  event.preventDefault()
}
function moveLayout(event) {
  const drag = layoutDrag.value
  if (!drag) return
  const point = svgPoint(event, drag.svg)
  drag.moved = true
  if (drag.diagram === 'usecase') {
    updateUmlLayout('usecase', drag.id, { x: Math.max(271, Math.min(929, point.x - drag.dx)), y: Math.max(64, Math.min(586, point.y - drag.dy)) })
  } else if (drag.diagram === 'actor') {
    const actor = actors.find(a => a.id === drag.id)
    const minX = actor.side === 'left' ? 42 : 1055, maxX = actor.side === 'left' ? 145 : 1158
    updateUmlLayout('actor', drag.id, { x: Math.max(minX, Math.min(maxX, point.x - drag.dx)), y: Math.max(82, Math.min(574, point.y - drag.dy)) })
  } else {
    const index = participants.findIndex(p => p.id === drag.id)
    const min = index ? participants[index - 1].x + 180 : 96
    const max = index < participants.length - 1 ? participants[index + 1].x - 180 : 1194
    updateUmlLayout('participant', drag.id, { x: Math.max(min, Math.min(max, point.x - drag.dx)) })
  }
}
function endLayoutDrag() {
  document.removeEventListener('pointermove', moveLayout)
  if (layoutDrag.value?.moved) {
    suppressClick.value = true
    saveUmlLayout()
    setTimeout(() => { suppressClick.value = false }, 0)
  }
  layoutDrag.value = null
}
function onUsecaseClick(id) {
  if (!suppressClick.value) toggleUc(id)
}
onBeforeUnmount(() => document.removeEventListener('pointermove', moveLayout))
</script>

<template>
  <section class="view" id="view-uml">
    <div class="hero">
      <div>
        <div class="eyebrow">UML DIAGRAMS / 设计视图</div>
        <h1>UML 图</h1>
        <p>用例图（用例名跟随故事标题）· 时序图（按后端真实路由绘制）。</p>
      </div>
    </div>

    <p class="uml-note">
      用例图按当前正式需求（{{ project.stories.length }} 条故事）动态生成；新增、删除或重命名 Story 时，用例节点与标题同步更新。
      角色关联按当前故事顺序自动派生，节点可拖动并保存布局。
      时序图按后端真实路由绘制「登录并加载项目工作台」主流程（JWT 拦截器 + MySQL），属协议说明图。
    </p>

    <div class="h-sec">
      用例图 · Use Case
      <span class="hint">悬停高亮关联 · 点击用例查看故事明细</span>
    </div>
    <CanvasZoomControls :zoom="usecaseZoom" :min-zoom="usecaseMinZoom" :max-zoom="usecaseMaxZoom" :zoom-mode="usecaseZoomMode"
      :reset-zoom="usecaseResetZoom" :fit-to-canvas="usecaseFitToCanvas" :zoom-in="usecaseZoomIn" :zoom-out="usecaseZoomOut"
      :set-zoom-percent="setUsecaseZoomPercent" />
    <div ref="usecaseViewport" class="uml-canvas usecase-canvas canvas-zoom-viewport" @click="clearSelection">
      <div ref="usecaseShell" class="canvas-zoom-shell" :style="usecaseShellStyle">
      <svg ref="usecaseSvg" class="uml-svg usecase-svg notranslate" translate="no" :style="usecaseCanvasStyle" :viewBox="usecaseBounds.viewBox" role="img"
           :aria-label="`爱管理系统用例图：${actors.length} 个角色、${usecases.length} 个用例`">
        <rect class="system-boundary" :x="usecaseBounds.boundary.x" :y="usecaseBounds.boundary.y" :width="usecaseBounds.boundary.width" :height="usecaseBounds.boundary.height" />
        <rect class="system-title-bg" x="195" y="17" width="114" height="22" />
        <text class="system-title" x="203" y="33">爱管理系统</text>

        <path v-for="l in associations" :key="l.key" class="association"
              :class="[l.actorId, { 'is-on': assocOn(l), 'is-sel': !hover && l.usecaseId === selectedId, 'is-muted': !!hover && !assocOn(l) }]"
              :d="l.d" />

        <g v-for="u in usecases" :key="u.id" class="uc-hit draggable-node"
           :class="{ 'is-on': ucOn(u), 'is-sel': !hover && u.id === selectedId, 'is-muted': !!hover && !ucOn(u) }"
           role="button" tabindex="0" :aria-pressed="u.id === selectedId"
           :aria-label="`用例：${u.name}（${u.ref}）`"
           @mouseenter="setHover('usecase', u.id)" @mouseleave="clearHover"
           @focus="setHover('usecase', u.id)" @blur="clearHover"
           @pointerdown.stop="beginLayoutDrag($event, 'usecase', u)"
           @click.stop="onUsecaseClick(u.id)"
           @keydown.enter.prevent="toggleUc(u.id)" @keydown.space.prevent="toggleUc(u.id)">
          <ellipse class="usecase" :class="u.type" :cx="u.x" :cy="u.y" :rx="u.rx" :ry="u.ry" />
          <text class="usecase-label" :x="u.x" :y="u.y - 3">
            <tspan :x="u.x">{{ u.name }}</tspan>
            <tspan class="story-ref" :x="u.x" dy="15">{{ u.ref }}</tspan>
          </text>
        </g>

        <g v-for="a in actors" :key="a.id" class="actor-hit draggable-node"
           :class="{ 'is-on': actorOn(a), 'is-muted': !!hover && !actorOn(a) }"
           :transform="`translate(${a.x} ${a.y - 44})`"
           @mouseenter="setHover('actor', a.id)" @mouseleave="clearHover"
           @pointerdown.stop="beginLayoutDrag($event, 'actor', a)">
          <circle class="actor-head" cx="0" cy="0" r="10" />
          <path class="actor-stroke" d="M0 10 V42 M-20 22 H20 M0 42 L-17 67 M0 42 L17 67" />
          <g class="actor-label-object" transform="translate(0 75)">
            <circle class="actor-key" :class="a.id" cx="-35" cy="4" r="4" />
            <text class="actor-name" x="0" y="8">{{ a.name }}</text>
          </g>
        </g>

        <g class="legend-object" transform="translate(175 636)">
          <rect class="legend-bg" width="850" height="44" />
          <text class="legend-title" x="18" y="27">连线图例</text>
          <g v-for="(item, index) in LEGEND" :key="item[0]" class="legend-item" :transform="`translate(${128 + index * 176} 0)`">
            <line class="legend-line" :class="item[0]" x1="0" y1="22" x2="32" y2="22" />
            <text class="legend-label" x="42" y="27">{{ item[1] }}</text>
          </g>
        </g>
      </svg>
      </div>
    </div>

    <div v-if="selectedUc" class="uc-detail">
      <div class="uc-detail-head">
        <b>{{ selectedUc.name }}</b>
        <span class="mono">{{ selectedUc.ref }}</span>
        <span class="small">关联 {{ selectedStories.length }} 条故事（数据来自故事看板）</span>
        <button class="uc-detail-close" type="button" @click="clearSelection">关闭 ✕</button>
      </div>
      <div class="uc-detail-row">
        <template v-for="item in selectedStories" :key="item.id">
          <span v-if="item.story" class="uc-story">
            <b class="mono">{{ item.story.id }}</b>
            <span>{{ item.story.title }}</span>
            <span class="tag" :class="item.story.priority">{{ item.story.priority }}</span>
            <span class="small">Sprint {{ item.story.sprint }}</span>
            <span class="small">{{ STATUS_TXT[item.story.status] || '—' }}</span>
          </span>
          <span v-else class="uc-story missing">
            <b class="mono">{{ item.id }}</b>
            <span>当前基线中未找到该故事</span>
          </span>
        </template>
      </div>
    </div>

    <div class="h-sec">时序图 · Sequence（登录并加载项目工作台）</div>
    <CanvasZoomControls :zoom="sequenceZoom" :min-zoom="sequenceMinZoom" :max-zoom="sequenceMaxZoom" :zoom-mode="sequenceZoomMode"
      :reset-zoom="sequenceResetZoom" :fit-to-canvas="sequenceFitToCanvas" :zoom-in="sequenceZoomIn" :zoom-out="sequenceZoomOut"
      :set-zoom-percent="setSequenceZoomPercent" />
    <div ref="sequenceViewport" class="uml-canvas sequence-canvas canvas-zoom-viewport">
      <div ref="sequenceShell" class="canvas-zoom-shell" :style="sequenceShellStyle">
      <svg ref="sequenceSvg" class="uml-svg sequence-svg notranslate" translate="no" :style="sequenceCanvasStyle" :viewBox="sequenceViewBox" role="img"
           aria-label="登录并加载项目工作台时序图">
        <defs>
          <marker id="seq-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 Z" fill="var(--ink)" />
          </marker>
          <marker id="seq-open-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M1 1 L9 5 L1 9" fill="none" stroke="var(--ink)" stroke-width="1.5" />
          </marker>
        </defs>

        <rect class="sequence-frame" :x="sequenceFrame.x" :y="sequenceFrame.y" :width="sequenceFrame.width" :height="sequenceFrame.height" />
        <g ref="sequenceDrawing" class="sequence-drawing">
        <text class="sequence-caption" x="38" y="34">sd 登录并加载项目工作台</text>

        <g v-for="p in participants" :key="p.id" class="participant-hit" @pointerdown="beginLayoutDrag($event, 'participant', p)">
          <circle v-if="p.actor" class="actor-head" :cx="p.x" cy="55" r="8" />
          <path v-if="p.actor" class="actor-stroke"
                :d="`M${p.x} 63 V83 M${p.x - 14} 71 H${p.x + 14} M${p.x} 83 L${p.x - 11} 100 M${p.x} 83 L${p.x + 11} 100`" />
          <rect class="participant" :x="p.x - 76" :y="TOP" width="152" height="42" />
          <text class="participant-label" :x="p.x" :y="TOP + (p.sub ? 20 : 26)">
            <tspan :x="p.x">{{ p.name }}</tspan>
            <tspan v-if="p.sub" class="participant-sub" :x="p.x" dy="12">{{ p.sub }}</tspan>
          </text>
          <line class="lifeline" :x1="p.x" :y1="TOP + 42" :x2="p.x" :y2="BOTTOM" />
        </g>

        <rect v-for="bar in activationBars" :key="bar.key" class="activation"
              :x="bar.x" :y="bar.y" width="10" :height="bar.height" />

        <g v-for="m in messages" :key="m.i" :data-seq-message="m.i + 1">
          <text class="message-label" :x="m.labelX" :y="m.labelY">
            <tspan :x="m.labelX">{{ m.i + 1 }}. {{ m.label }}</tspan>
            <tspan v-for="(d, j) in m.details" :key="j" class="message-detail" :x="m.labelX" dy="14">{{ d }}</tspan>
          </text>
          <line class="message" :class="{ return: m.type === 'return' }"
                :x1="m.startX" :y1="m.y" :x2="m.endX" :y2="m.y" />
        </g>
        </g>
      </svg>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ============ UML SVG(逐条移植 legacy/index.html 的 .uml-svg 系列) ============ */
.uml-svg{display:block;width:100%;height:auto;min-width:1100px;font-family:Arial,"Microsoft YaHei",sans-serif;letter-spacing:0;animation:none!important;filter:none!important}
.uml-svg,.uml-svg *{animation:none!important;filter:none!important}
.sequence-canvas.canvas-zoom-viewport{height:clamp(680px,70vw,820px);overscroll-behavior-y:auto}
.uml-canvas.canvas-zoom-viewport{padding:0}
.usecase-canvas.canvas-zoom-viewport{contain:layout paint;overscroll-behavior-y:auto}
.usecase-svg{min-width:0}
.uml-svg .system-boundary{fill:rgba(255,255,255,.3);stroke:var(--ink);stroke-width:2}
.uml-svg .system-title-bg{fill:var(--paper)}
.uml-svg .system-title{fill:var(--ink);font-size:14px;font-weight:700}
.uml-svg .association{fill:none;stroke-width:2;stroke-linecap:square;stroke-linejoin:miter;opacity:.9}
.uml-svg .association{pointer-events:none}
.uml-svg .association.admin{stroke:#2f6b4f}
.uml-svg .association.dri{stroke:#3f6f8f}
.uml-svg .association.member{stroke:#b85f2d}
.uml-svg .association.viewer{stroke:#6b7f8f}
.uml-svg .actor-stroke{fill:none;stroke:var(--ink);stroke-width:2.2;stroke-linecap:square}
.uml-svg .actor-head{fill:var(--blue);stroke:var(--ink);stroke-width:2.2}
.uml-svg .actor-label-object,.uml-svg .legend-object{overflow:visible;pointer-events:none}
.uml-svg .actor-name{fill:var(--ink);font:700 11px/1.25 Arial,"Microsoft YaHei",sans-serif;text-anchor:middle}
.uml-svg .actor-key{stroke:var(--paper);stroke-width:2}
.uml-svg .actor-key.admin{fill:#2f6b4f}.uml-svg .actor-key.dri{fill:#3f6f8f}.uml-svg .actor-key.member{fill:#b85f2d}.uml-svg .actor-key.viewer{fill:#6b7f8f}
.uml-svg .legend-bg{fill:var(--paper-2);stroke:var(--line);stroke-width:1.2}
.uml-svg .legend-title{fill:var(--muted);font:700 10px Arial,"Microsoft YaHei",sans-serif}
.uml-svg .legend-label{fill:var(--ink);font:10.5px Arial,"Microsoft YaHei",sans-serif}
.uml-svg .legend-line{fill:none;stroke-width:3}
.uml-svg .legend-line.admin{stroke:#2f6b4f}.uml-svg .legend-line.dri{stroke:#3f6f8f}.uml-svg .legend-line.member{stroke:#b85f2d}.uml-svg .legend-line.viewer{stroke:#6b7f8f}
.uml-svg .usecase{stroke:var(--ink);stroke-width:2}
.uml-svg .usecase.core{fill:var(--green)}
.uml-svg .usecase.observe{fill:var(--paper)}
.uml-svg .usecase.ai{fill:var(--lilac)}
.uml-svg .usecase.github{fill:var(--orange)}
.uml-svg .usecase-label{fill:var(--ink);font-size:12px;font-weight:700;text-anchor:middle}
.uml-svg .story-ref{fill:var(--muted);font-size:9px;font-weight:400;text-anchor:middle}
.uml-svg .participant{fill:var(--paper);stroke:var(--ink);stroke-width:2}
.uml-svg .participant-label{fill:var(--ink);font-size:14px;font-weight:700;text-anchor:middle}
.uml-svg .participant-sub{fill:var(--ink-soft);font-size:11.5px;font-weight:600}
.uml-svg .lifeline{stroke:var(--line);stroke-width:1.7;stroke-dasharray:7 5}
.uml-svg .activation{fill:var(--green);stroke:var(--ink);stroke-width:1.4}
.uml-svg .message{fill:none;stroke:var(--ink);stroke-width:1.7;marker-end:url(#seq-arrow)}
.uml-svg .message.return{stroke-dasharray:7 5;marker-end:url(#seq-open-arrow)}
.uml-svg .message-label-bg{display:none}
.uml-svg .message-label{fill:var(--ink);font-size:14px;font-weight:700;text-anchor:middle}
.uml-svg .message-detail{fill:var(--ink-soft);font-size:11.5px;font-weight:600;text-anchor:middle}
.uml-svg .sequence-frame{fill:none;stroke:var(--line);stroke-width:1.2}
.uml-svg .sequence-caption{fill:var(--ink-soft);font-size:12.5px;font-weight:700}

/* ============ 交互:悬停高亮 / 选中 / 键盘焦点(纯 class 绑定,不操作 DOM) ============ */
.uml-svg .uc-hit,.uml-svg .actor-hit{cursor:pointer;pointer-events:bounding-box;transition:none!important}
.uml-svg .draggable-node,.uml-svg .participant-hit{cursor:grab;touch-action:none}
.uml-svg .draggable-node:active,.uml-svg .participant-hit:active{cursor:grabbing}
.uml-svg .participant-hit .lifeline{pointer-events:none}
.uml-svg .uc-hit:focus-visible{outline:3px dashed var(--ink);outline-offset:3px}
.uml-svg .usecase,.uml-svg .association,.uml-svg .actor-head,.uml-svg .actor-stroke{transition:none!important}
.uml-svg .uc-hit.is-sel .usecase{stroke:#b85f2d}
.uml-svg .uc-hit.is-on .usecase,.uml-svg .uc-hit:focus-visible .usecase{stroke:#b85f2d}
.uml-svg .uc-hit.is-on .usecase.core{fill:#b7df83}
.uml-svg .uc-hit.is-muted,.uml-svg .actor-hit.is-muted{opacity:.42}
.uml-svg .actor-hit.is-on .actor-head,.uml-svg .actor-hit.is-on .actor-stroke{stroke:#b85f2d}
.uml-svg .association.is-sel,.uml-svg .association.is-on{opacity:1}
.uml-svg .association.is-muted{opacity:.2}

/* ============ 用例明细(点击用例后在下方展开一行) ============ */
.h-sec .hint{text-transform:none;letter-spacing:0;font-size:12px;font-weight:400;color:var(--muted)}
.uc-detail{margin-top:14px;border:2px solid var(--ink);background:var(--paper);box-shadow:var(--shadow);padding:12px 14px}
.uc-detail-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;font-size:14px}
.uc-detail-head .mono{background:var(--yellow);border:1px solid var(--ink);padding:0 6px;font-size:12px}
.uc-detail-close{margin-left:auto;padding:4px 10px;font-size:12px;box-shadow:2px 2px 0 var(--ink)}
.uc-detail-row{display:flex;flex-wrap:wrap;gap:8px}
.uc-story{display:inline-flex;align-items:center;gap:8px;border:2px solid var(--ink);background:var(--paper-2);padding:5px 9px;font-size:12.5px;box-shadow:2px 2px 0 var(--ink)}
.uc-story .mono{font-family:var(--mono);font-weight:700;font-size:12px}
.uc-story.missing{background:var(--paper);border-style:dashed;color:var(--muted)}
</style>
