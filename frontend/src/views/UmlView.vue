<script setup>
import { computed, ref } from 'vue'
import { useProjectStore } from '@/stores/project'

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

/* ==================== 用例图 ==================== */
const RX = 91 // 用例椭圆半轴(与 ellipsePoint 用同一组值)
const RY = 31

const ACTORS = [
  { id: 'admin', name: '管理员', x: 72, y: 125, side: 'left' },
  { id: 'dri', name: '项目负责人 / DRI', x: 72, y: 405, side: 'left' },
  { id: 'member', name: '团队成员', x: 1105, y: 210, side: 'right' },
  { id: 'viewer', name: '普通用户 / 数据查看者', x: 1105, y: 525, side: 'right' }
]

/* ref:图上标注的故事编号;storyId:取用例标题的故事;stories:点击后展开明细的故事范围 */
const USECASE_LAYOUT = [
  { id: 'login', ref: 'US01', storyId: 'US01', stories: ['US01'], fallback: '登录 / 退出', x: 470, y: 82, type: 'core' },
  { id: 'project', ref: 'US01', storyId: 'US01', stories: ['US01'], fallback: '管理项目与成员', x: 285, y: 125, type: 'core' },
  { id: 'permission', ref: 'US02 / US08 / US16', storyId: 'US02', stories: ['US02', 'US08', 'US16'], fallback: '配置角色与数据权限', x: 285, y: 210, type: 'core' },
  { id: 'stories', ref: 'US03 / US26', storyId: 'US03', stories: ['US03', 'US26'], fallback: '维护需求故事与需求池', x: 345, y: 315, type: 'core' },
  { id: 'storymap', ref: 'US04', storyId: 'US04', stories: ['US04'], fallback: '查看用户故事地图', x: 345, y: 405, type: 'core' },
  { id: 'planning', ref: 'US05 / US10', storyId: 'US05', stories: ['US05', 'US10'], fallback: '安排任务与排期', x: 345, y: 495, type: 'core' },
  { id: 'gantt', ref: 'US10 / US11 / US27', storyId: 'US10', stories: ['US10', 'US11', 'US27'], fallback: '查看甘特图与成员负载', x: 590, y: 560, type: 'observe' },
  { id: 'update', ref: 'US06 / US12', storyId: 'US06', stories: ['US06', 'US12'], fallback: '更新任务与看板状态', x: 865, y: 125, type: 'core' },
  { id: 'membermap', ref: 'US11 / US12 / US28', storyId: 'US11', stories: ['US11', 'US12', 'US28'], fallback: '查看成员任务与协作状态', x: 865, y: 215, type: 'observe' },
  { id: 'progress', ref: 'US07 / US25', storyId: 'US07', stories: ['US07', 'US25'], fallback: '查看实时进度与基础报表', x: 865, y: 505, type: 'observe' },
  { id: 'meeting', ref: 'US29-US32', storyId: 'US30', stories: ['US29', 'US30', 'US31', 'US32'], fallback: 'AI 会议分析', x: 590, y: 205, type: 'ai' },
  { id: 'review', ref: 'US17 / US33', storyId: 'US33', stories: ['US17', 'US33'], fallback: '审核 AI 建议', x: 590, y: 295, type: 'ai' },
  { id: 'aiPlan', ref: 'US14 / US18', storyId: 'US18', stories: ['US14', 'US18'], fallback: 'AI 需求拆解与智能排期', x: 590, y: 385, type: 'ai' },
  { id: 'risk', ref: 'US19 / US20 / US24', storyId: 'US20', stories: ['US19', 'US20', 'US24'], fallback: 'AI 风险预警', x: 590, y: 475, type: 'ai' },
  { id: 'github', ref: 'US22 / US34-US36', storyId: 'US35', stories: ['US22', 'US34', 'US35', 'US36'], fallback: 'GitHub / 任务提交分析', x: 865, y: 335, type: 'github' }
]

/* 角色 → 用例 的关联(用例图连线) */
const RELATIONS = {
  admin: ['login', 'project', 'permission', 'github'],
  dri: ['login', 'stories', 'storymap', 'planning', 'gantt', 'progress', 'meeting', 'review', 'aiPlan', 'risk', 'github'],
  member: ['login', 'update', 'membermap', 'progress', 'meeting', 'github'],
  viewer: ['login', 'progress']
}

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

/** 故事编号区段压缩:连续 >=3 个压成 `US29-US32`,零散编号用 ' / ' 连接。
 *  该函数能**逐字复现**本视图移植时手写的全部 14 条 ref 标注(已单独验算 14/14),
 *  所以改成派生不改变任何现有显示,却消除了手写标注的漂移:故事被删除后,
 *  幽灵编号会自动从节点标注上消失,而不是继续挂在椭圆上。 */
function compactStoryRefs(ids) {
  const nums = ids
    .map(id => ({ id, n: Number(String(id).replace(/\D/g, '')) }))
    .filter(x => Number.isInteger(x.n))
    .sort((a, b) => a.n - b.n)
  const out = []
  for (let i = 0; i < nums.length;) {
    let j = i
    while (j + 1 < nums.length && nums[j + 1].n === nums[j].n + 1) j++
    const run = j - i + 1
    if (run >= 3) out.push(`${nums[i].id}-${nums[j].id}`)
    else for (let k = i; k <= j; k++) out.push(nums[k].id)
    i = j + 1
  }
  return out.join(' / ')
}

const usecases = computed(() => USECASE_LAYOUT.map(u => ({
  ...u,
  name: storyTitle(u.storyId, u.fallback),
  /* ref 由 stories 派生;全部故事都不在基线里时才退回手写兜底 */
  ref: compactStoryRefs(u.stories.filter(id => project.stories.some(s => s.id === id))) || u.ref
})))

/* 未被任何用例引用的故事:让「新增故事」在 UML 视图上可见。
   用例的故事归属是需求设计决策、不能从数据推导,但「有故事没进用例图」这件事必须显式暴露,
   否则新增 US38+ 时 UML 毫无反应(此前手写布局就是这个问题)。 */
const mappedStoryIds = computed(() => new Set(USECASE_LAYOUT.flatMap(u => u.stories)))
const unmappedStories = computed(() => project.stories.filter(s => !mappedStoryIds.value.has(s.id)))
const unmappedText = computed(() => {
  const ids = unmappedStories.value.map(s => s.id)
  return ids.length > 15 ? ids.slice(0, 15).join('、') + ` 等 ${ids.length} 条` : ids.join('、')
})
const usecaseById = computed(() => Object.fromEntries(usecases.value.map(u => [u.id, u])))
const actorById = Object.fromEntries(ACTORS.map(a => [a.id, a]))

/* 角色→用例连线的终点落在椭圆边界上(与 legacy ellipsePoint 同算法) */
function ellipsePoint(a, u) {
  const dx = a.x - u.x, dy = a.y - u.y
  const scale = 1 / Math.sqrt((dx * dx) / (RX * RX) + (dy * dy) / (RY * RY))
  return { x: u.x + dx * scale, y: u.y + dy * scale }
}

const associations = computed(() => Object.entries(RELATIONS).flatMap(([actorId, ids]) => ids.map((id, index) => {
  const a = actorById[actorId], u = usecaseById.value[id]
  const end = ellipsePoint(a, u)
  return {
    key: `${actorId}-${id}`,
    actorId, usecaseId: id,
    x1: a.x + (a.side === 'left' ? 26 : -26),
    y1: a.y - 14 + index * 4,
    x2: Number(end.x.toFixed(1)),
    y2: Number(end.y.toFixed(1))
  }
})))

/* ---------- 交互:hover 高亮 + 点击选中(键盘可达) ---------- */
const hover = ref(null)        // { kind:'usecase' | 'actor', id } | null
const selectedId = ref('')     // 选中的用例 id

function setHover(kind, id) { hover.value = { kind, id } }
function clearHover() { hover.value = null }

const hoverActorId = computed(() => (hover.value?.kind === 'actor' ? hover.value.id : ''))
const hoverUsecaseId = computed(() => (hover.value?.kind === 'usecase' ? hover.value.id : ''))

/* 悬停角色 → 它的全部用例点亮;悬停用例 → 它自己点亮 */
const ucOn = u => hoverUsecaseId.value === u.id || (RELATIONS[hoverActorId.value] || []).includes(u.id)
const actorOn = a => hoverActorId.value === a.id || (RELATIONS[a.id] || []).includes(hoverUsecaseId.value)
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

const participantById = Object.fromEntries(PARTICIPANTS.map(p => [p.id, p]))

function activationEdge(participantId, messageIndex, direction) {
  const p = participantById[participantId]
  const active = ACTIVATIONS.find(a => a.id === participantId && messageIndex >= a.from && messageIndex <= a.to)
  if (!active) return p.x
  return p.x + (direction === 'right' ? 5 : -5)
}

const activationBars = ACTIVATIONS.map(a => {
  const p = participantById[a.id]
  return { key: `${a.id}-${a.from}`, x: p.x - 5, y: ROW_START + a.from * ROW_GAP - 8, height: (a.to - a.from) * ROW_GAP + 18 }
})

/* 文案+明细整体贴着箭头向上排布,最多 2 行明细,不越过上一条消息 */
const messages = MSG.map(([from, to, label, details, type], i) => {
  const a = participantById[from], b = participantById[to]
  const y = ROW_START + i * ROW_GAP
  const direction = b.x > a.x ? 'right' : 'left'
  const startX = activationEdge(from, i, direction)
  const endX = activationEdge(to, i, direction === 'right' ? 'left' : 'right')
  return {
    i, from, to, label, details, type, y, startX, endX,
    labelX: (startX + endX) / 2,
    labelY: y - 10 - 12 * details.length
  }
})
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
      演示版：用例图按需求基线（{{ project.stories.length }} 条故事）渲染 —— 用例名取自故事标题、US 标注由故事编号派生，
      两者都随故事数据自动更新（删掉故事，编号即从标注上消失，不留幽灵编号）。
      但 15 个用例的布局与角色关联由 <code>USECASE_LAYOUT</code> / <code>RELATIONS</code> 静态定义，增删故事不会增减用例节点。
      <span id="uml-unmapped">未被任何用例引用的故事 {{ unmappedStories.length }} 条<template v-if="unmappedText">（{{ unmappedText }}）</template></span>。
      时序图不由数据生成，按后端真实路由绘制「登录并加载项目工作台」主流程（JWT 拦截器 + MySQL），属协议说明图。
    </p>

    <div class="h-sec">
      用例图 · Use Case
      <span class="hint">悬停高亮关联 · 点击用例查看故事明细</span>
    </div>
    <div class="uml-canvas" @click="clearSelection">
      <svg class="uml-svg usecase-svg" viewBox="0 0 1200 680" role="img"
           :aria-label="`爱管理系统用例图：${ACTORS.length} 个角色、${usecases.length} 个用例`">
        <rect class="system-boundary" x="175" y="28" width="850" height="594" />
        <rect class="system-title-bg" x="195" y="17" width="114" height="22" />
        <text class="system-title" x="203" y="33">爱管理系统</text>

        <line v-for="l in associations" :key="l.key" class="association"
              :class="[l.actorId, { 'is-on': assocOn(l), 'is-sel': !hover && l.usecaseId === selectedId, 'is-muted': !!hover && !assocOn(l) }]"
              :x1="l.x1" :y1="l.y1" :x2="l.x2" :y2="l.y2" />

        <g v-for="u in usecases" :key="u.id" class="uc-hit"
           :class="{ 'is-on': ucOn(u), 'is-sel': !hover && u.id === selectedId, 'is-muted': !!hover && !ucOn(u) }"
           role="button" tabindex="0" :aria-pressed="u.id === selectedId"
           :aria-label="`用例：${u.name}（${u.ref}）`"
           @mouseenter="setHover('usecase', u.id)" @mouseleave="clearHover"
           @focus="setHover('usecase', u.id)" @blur="clearHover"
           @click.stop="toggleUc(u.id)"
           @keydown.enter.prevent="toggleUc(u.id)" @keydown.space.prevent="toggleUc(u.id)">
          <ellipse class="usecase" :class="u.type" :cx="u.x" :cy="u.y" :rx="RX" :ry="RY" />
          <text class="usecase-label" :x="u.x" :y="u.y - 3">
            <tspan :x="u.x">{{ u.name }}</tspan>
            <tspan class="story-ref" :x="u.x" dy="15">{{ u.ref }}</tspan>
          </text>
        </g>

        <g v-for="a in ACTORS" :key="a.id" class="actor-hit"
           :class="{ 'is-on': actorOn(a), 'is-muted': !!hover && !actorOn(a) }"
           :transform="`translate(${a.x} ${a.y - 44})`"
           @mouseenter="setHover('actor', a.id)" @mouseleave="clearHover">
          <circle class="actor-head" cx="0" cy="0" r="10" />
          <path class="actor-stroke" d="M0 10 V42 M-20 22 H20 M0 42 L-17 67 M0 42 L17 67" />
          <circle class="actor-key" :class="a.id" cx="-30" cy="80" r="5" />
          <text class="actor-name" x="0" y="86">{{ a.name }}</text>
        </g>

        <rect class="legend-bg" x="165" y="636" width="870" height="34" />
        <text class="legend-title" x="180" y="659">连线图例</text>
        <line v-for="(item, index) in LEGEND" :key="`ll-${item[0]}`" class="legend-line association" :class="item[0]"
              :x1="236 + index * 230" y1="655" :x2="266 + index * 230" y2="655" />
        <text v-for="(item, index) in LEGEND" :key="`lt-${item[0]}`" class="legend-label"
              :x="275 + index * 230" y="659">{{ item[1] }}</text>
      </svg>
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
    <div class="uml-canvas">
      <svg class="uml-svg sequence-svg" viewBox="0 0 1290 1050" role="img"
           aria-label="登录并加载项目工作台时序图">
        <defs>
          <marker id="seq-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 Z" fill="var(--ink)" />
          </marker>
          <marker id="seq-open-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M1 1 L9 5 L1 9" fill="none" stroke="var(--ink)" stroke-width="1.5" />
          </marker>
        </defs>

        <rect class="sequence-frame" x="20" y="10" width="1250" height="1025" />
        <text class="sequence-caption" x="38" y="34">sd 登录并加载项目工作台</text>

        <g v-for="p in PARTICIPANTS" :key="p.id">
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
            <tspan v-for="(d, j) in m.details" :key="j" class="message-detail" :x="m.labelX" dy="12">{{ d }}</tspan>
          </text>
          <line class="message" :class="{ return: m.type === 'return' }"
                :x1="m.startX" :y1="m.y" :x2="m.endX" :y2="m.y" />
        </g>
      </svg>
    </div>
  </section>
</template>

<style scoped>
/* ============ UML SVG(逐条移植 legacy/index.html 的 .uml-svg 系列) ============ */
.uml-svg{display:block;width:100%;height:auto;min-width:1100px;font-family:var(--mono);letter-spacing:0}
.uml-svg .system-boundary{fill:rgba(255,255,255,.3);stroke:var(--ink);stroke-width:2}
.uml-svg .system-title-bg{fill:var(--paper)}
.uml-svg .system-title{fill:var(--ink);font-size:14px;font-weight:700}
.uml-svg .association{fill:none;stroke-width:2;stroke-linecap:square;opacity:.9}
.uml-svg .association.admin{stroke:#2f6b4f}
.uml-svg .association.dri{stroke:#3f6f8f}
.uml-svg .association.member{stroke:#b85f2d}
.uml-svg .association.viewer{stroke:#6b7f8f}
.uml-svg .actor-stroke{fill:none;stroke:var(--ink);stroke-width:2.2;stroke-linecap:square}
.uml-svg .actor-head{fill:var(--blue);stroke:var(--ink);stroke-width:2.2}
.uml-svg .actor-name{fill:var(--ink);font-size:12px;font-weight:700;text-anchor:middle}
.uml-svg .actor-key{stroke:var(--paper);stroke-width:2}
.uml-svg .actor-key.admin{fill:#2f6b4f}.uml-svg .actor-key.dri{fill:#3f6f8f}.uml-svg .actor-key.member{fill:#b85f2d}.uml-svg .actor-key.viewer{fill:#6b7f8f}
.uml-svg .legend-bg{fill:var(--paper-2);stroke:var(--line);stroke-width:1.2}
.uml-svg .legend-title{fill:var(--muted);font-size:10px;font-weight:700}
.uml-svg .legend-label{fill:var(--ink);font-size:10.5px}
.uml-svg .legend-line{stroke-width:3}
.uml-svg .usecase{stroke:var(--ink);stroke-width:2}
.uml-svg .usecase.core{fill:var(--green)}
.uml-svg .usecase.observe{fill:var(--paper)}
.uml-svg .usecase.ai{fill:var(--lilac)}
.uml-svg .usecase.github{fill:var(--orange)}
.uml-svg .usecase-label{fill:var(--ink);font-size:12px;font-weight:700;text-anchor:middle}
.uml-svg .story-ref{fill:var(--muted);font-size:9px;font-weight:400;text-anchor:middle}
.uml-svg .participant{fill:var(--paper);stroke:var(--ink);stroke-width:2}
.uml-svg .participant-label{fill:var(--ink);font-size:12px;font-weight:700;text-anchor:middle}
.uml-svg .participant-sub{fill:var(--muted);font-size:9.5px;font-weight:400}
.uml-svg .lifeline{stroke:var(--line);stroke-width:1.7;stroke-dasharray:7 5}
.uml-svg .activation{fill:var(--green);stroke:var(--ink);stroke-width:1.4}
.uml-svg .message{fill:none;stroke:var(--ink);stroke-width:1.7;marker-end:url(#seq-arrow)}
.uml-svg .message.return{stroke-dasharray:7 5;marker-end:url(#seq-open-arrow)}
.uml-svg .message-label-bg{display:none}
.uml-svg .message-label{fill:var(--ink);font-size:11px;text-anchor:middle}
.uml-svg .message-detail{fill:var(--muted);font-size:9.5px;text-anchor:middle}
.uml-svg .sequence-frame{fill:none;stroke:var(--line);stroke-width:1.2}
.uml-svg .sequence-caption{fill:var(--muted);font-size:11px;font-weight:700}

/* ============ 交互:悬停高亮 / 选中 / 键盘焦点(纯 class 绑定,不操作 DOM) ============ */
.uml-svg .uc-hit,.uml-svg .actor-hit{cursor:pointer;transition:opacity .12s}
.uml-svg .uc-hit:focus-visible{outline:3px dashed var(--ink);outline-offset:3px}
.uml-svg .usecase,.uml-svg .association,.uml-svg .actor-head,.uml-svg .actor-stroke{transition:stroke-width .12s,opacity .12s}
.uml-svg .uc-hit.is-sel .usecase{stroke-width:3.5}
.uml-svg .uc-hit.is-on .usecase,.uml-svg .uc-hit:focus-visible .usecase{stroke-width:4}
.uml-svg .uc-hit.is-muted,.uml-svg .actor-hit.is-muted{opacity:.42}
.uml-svg .actor-hit.is-on .actor-head,.uml-svg .actor-hit.is-on .actor-stroke{stroke-width:3.6}
.uml-svg .association.is-sel{stroke-width:3;opacity:1}
.uml-svg .association.is-on{stroke-width:4;opacity:1}
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
