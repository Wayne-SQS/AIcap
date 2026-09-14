<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { membersApi } from '@/api/members'
import { useToast } from '@/composables/useToast'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import MemberProfileDialog from '@/components/members/MemberProfileDialog.vue'
import MemberTaskDrawer from '@/components/members/MemberTaskDrawer.vue'
import { ROLE_TXT } from '@/data/seed'
import { memberLabel } from '@/data/memberIdentity'

/* 成员任务图
   - 成员卡片 / 热力图 / Bandwidth / 开发活动图:5 名后端真实成员,容量取 users.capacity_hours
   - 点成员卡片 → 右侧任务明细抽屉(状态筛选)
   - 成员画像板块(技术栈 / 工作能力 / 开发流程领域)为真实后端数据,置于页面顶部 */
const project = useProjectStore()
const session = useSessionStore()
const { notify } = useToast()

const profiles = ref([])
const profileError = ref('')
const editing = ref(null)
const drawerMember = ref(null)
const online = computed(() => session.apiMode && !!session.currentUser)
const DIMS_OF_PROFILE = [
  { k: 'tech_stack', t: '技术栈' },
  { k: 'capabilities', t: '工作能力' },
  { k: 'process_domains', t: '熟悉的开发流程领域' }
]

async function loadProfiles() {
  if (!online.value) return
  try {
    profiles.value = await membersApi.profiles()
    profileError.value = ''
  } catch (e) {
    profileError.value = e.message
  }
}
/* 权限:admin/owner 可改任何人;member 只能改本人;viewer 只读 */
function canEdit(p) {
  const u = session.currentUser
  if (!u) return false
  if (u.role === 'admin' || u.role === 'owner') return true
  return u.role === 'member' && u.id === p.user_id
}
function onSaved(saved) {
  profiles.value = profiles.value.map(p => (p.user_id === saved.user_id ? saved : p))
  editing.value = null
  notify('画像已更新')
}
watch(online, v => { if (v) loadProfiles() }, { immediate: true })
onMounted(loadProfiles)

/* ==================== 成员卡片 ==================== */
const totalAllocated = computed(() => project.tasks.reduce((a, t) => a + Math.max(0, Number(t.h) || 0), 0))
const memberCards = computed(() => project.members.map(m => {
  const mine = project.memberTasks(m.id)
  const myH = project.allocatedHours(m.id)
  const state = project.capacityStatus(project.utilization(m.id))
  return {
    m, mine: mine.length, myH,
    pct: totalAllocated.value ? Math.round(myH / totalAllocated.value * 100) : 0,
    myStories: project.stories.filter(s => s.owner === m.id).length,
    state
  }
}))
function openDrawer(id) {
  drawerMember.value = id
}

/* ==================== 周负载热力图 ==================== */
const load = computed(() => project.loadByOwner)
const LOAD_LABEL = ['低负载', '正常', '偏忙', '超载']
function heatCell(m, w) {
  const hours = load.value[m.id] ? load.value[m.id][w] : 0
  const weeklyCapacity = project.capacityHours(m.id) / 6
  const ratio = weeklyCapacity ? hours / weeklyCapacity : 0
  const lv = ratio > 1 ? 3 : (ratio >= .8 ? 2 : (ratio >= .6 ? 1 : 0))
  const related = project.tasks.filter(t => t.owner === m.id && t.w[0] <= w + 1 && t.w[1] >= w + 1).map(t => t.id).join('、') || '无'
  return {
    hours, lv,
    title: `${m.name} · W${w + 1}\n计划工时：${hours}h\n周容量：${weeklyCapacity.toFixed(1)}h\n负载等级：${LOAD_LABEL[lv]}\n关联任务：${related}`
  }
}

/* ==================== 容量与分配 ==================== */
const bandwidthCards = computed(() => project.members.map(m => {
  const alloc = project.allocatedHours(m.id)
  const cap = project.capacityHours(m.id)
  return { m, alloc, cap, pct: cap ? Math.round(alloc / cap * 100) : 0, state: project.capacityStatus(cap ? alloc / cap : 0) }
}))

/* ==================== 成员开发活动图(样例数据,6 周 × 7 天) ==================== */
function seeded(s) {
  let x = s >>> 0
  return () => { x = (x * 1664525 + 1013904223) >>> 0; return x / 4294967296 }
}
function makeContrib(count) {
  const out = []
  for (let m = 0; m < count; m++) {
    const rnd = seeded(1000 + m * 77)
    const days = Array.from({ length: 7 }, () => Array(6).fill(0))
    for (let d = 0; d < 7; d++) {
      for (let w = 0; w < 6; w++) {
        const v = rnd(), threshold = .42 - (w / 5) * .12
        days[d][w] = v > threshold ? (v > threshold + .42 ? 4 : v > threshold + .28 ? 3 : v > threshold + .14 ? 2 : 1) : 0
      }
    }
    out.push(days)
  }
  return out
}
const DAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']
const contribs = computed(() => {
  const matrix = makeContrib(project.members.length)
  return project.members.map((m, i) => {
    const rows = matrix[i]
    const flat = rows.flat()
    return {
      m, rows,
      total: flat.reduce((a, b) => a + b, 0),
      activeDays: flat.filter(v => v > 0).length
    }
  })
})
</script>

<template>
  <section class="view" id="view-members">
    <div class="hero">
      <div>
        <div class="eyebrow">TEAM LOAD / 成员负载</div>
        <h1>成员任务图</h1>
        <p>{{ project.members.length }} 名成员（含只读查看者） · 6 周负载热力图 · 容量与分配 · 点击成员卡片查看任务明细。</p>
      </div>
    </div>

    <div class="h-sec">成员画像 · 技术栈 / 工作能力 / 熟悉的开发流程领域</div>
    <p v-if="!online" class="small">离线演示模式：画像需连接后端读取（在线登录后自动加载）。</p>
    <p v-else-if="profileError" class="small" role="alert">画像加载失败：{{ profileError }}</p>
    <div v-else class="profile-grid" id="member-profiles">
      <div v-for="p in profiles" :key="p.user_id" class="pcard">
        <div class="phead">
          <b>{{ p.display_name }}</b>
          <span class="badge">{{ ROLE_TXT[p.role] || p.role }}</span>
          <span class="small" v-if="p.years_experience">{{ p.years_experience }} 年经验</span>
          <button
            v-if="canEdit(p) || session.isViewer" class="edit"
            :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''"
            @click="editing = p"
          >编辑画像</button>
        </div>
        <div class="ptitle" v-if="p.title">{{ p.title }}</div>
        <p class="small psum" v-if="p.summary">{{ p.summary }}</p>
        <div v-for="dim in DIMS_OF_PROFILE" :key="dim.k" class="pdim">
          <div class="pdim-t">{{ dim.t }}</div>
          <div class="chips">
            <span v-for="s in p[dim.k]" :key="s.name" class="chip" :title="`熟练度 ${s.level}/5`">
              {{ s.name }}<i class="bar"><b :style="{ width: (s.level / 5 * 100) + '%' }"></b></i>
            </span>
            <span v-if="!p[dim.k] || !p[dim.k].length" class="small">—</span>
          </div>
        </div>
      </div>
    </div>
    <MemberProfileDialog v-if="editing" :profile="editing" @close="editing = null" @saved="onSaved" />

    <div class="member-grid" id="member-grid">
      <div
        v-for="c in memberCards" :key="c.m.id"
        class="mcard clickable" :class="{ selected: drawerMember === c.m.id }"
        role="button" tabindex="0" :data-member="c.m.id"
        :aria-label="`查看 ${c.m.name} 的任务明细`"
        @click="openDrawer(c.m.id)" @keydown.enter.prevent="openDrawer(c.m.id)" @keydown.space.prevent="openDrawer(c.m.id)"
      >
        <div class="head">
          <span class="avatar" :class="'a' + c.m.id">{{ memberLabel(c.m.id) }}</span>
          <div><h3>{{ c.m.name }}</h3><div class="role">{{ project.memberRoleText(c.m.id) }}</div></div>
          <span class="loadflag" :class="c.state.key" style="margin-left:auto">{{ c.state.label }}</span>
        </div>
        <div class="tagline" style="font-size:12px;color:var(--muted);margin-bottom:12px">{{ c.m.tag }}</div>
        <div class="statline"><span>任务 <b>{{ c.mine }}</b></span><span>工时 <b>{{ c.myH }}h</b></span><span>占比 <b>{{ c.pct }}%</b></span><span>故事 <b>{{ c.myStories }}</b></span></div>
        <div class="track"><i :style="{ width: Math.min(c.pct, 100) + '%', background: `var(--${c.m.accent || 'gray'})` }"></i></div>
      </div>
    </div>
    <MemberTaskDrawer v-if="drawerMember !== null" :member-id="drawerMember" @close="drawerMember = null" />

    <div class="h-sec">周负载热力图</div>
    <p class="load-note">数字表示该成员在该周的计划工时；颜色表示该周负载等级（按周容量占比）。0 表示该周暂无计划工时，悬停可看关联任务。</p>
    <div class="load-legend">
      <span><i class="low"></i>低负载</span><span><i class="normal"></i>正常</span><span><i class="busy"></i>偏忙</span><span><i class="over"></i>超载</span>
    </div>
    <div class="heat-wrap">
      <div class="heat" id="heat">
        <div class="heat-head"><div>成员 \ 周</div><div v-for="w in 6" :key="w">W{{ w }}</div></div>
        <div v-for="m in project.members" :key="m.id" class="heat-row">
          <div class="heat-name"><span class="avatar" :class="'a' + m.id">{{ memberLabel(m.id) }}</span>{{ m.name }}</div>
          <div v-for="w in 6" :key="w" class="heat-cell" :title="heatCell(m, w - 1).title"><span class="hrs" :class="'hl' + heatCell(m, w - 1).lv">{{ heatCell(m, w - 1).hours }}</span></div>
        </div>
      </div>
    </div>

    <div class="h-sec">容量与分配 · Bandwidth</div>
    <div id="bandwidth">
      <div v-for="b in bandwidthCards" :key="b.m.id" class="mcard">
        <div class="head">
          <span class="avatar" :class="'a' + b.m.id">{{ memberLabel(b.m.id) }}</span>
          <div><h3>{{ b.m.name }}</h3><div class="role">容量 {{ b.cap }}h · 已分配 {{ b.alloc }}h · 剩余 {{ b.cap - b.alloc }}h</div></div>
          <span class="loadflag" :class="b.state.key" style="margin-left:auto">{{ b.state.label }}</span>
        </div>
        <div class="bw-row"><span class="small">已分配</span><div class="bw-track"><i class="used" :class="b.state.key" :style="{ width: Math.min(b.pct, 100) + '%' }"></i><i class="cap" style="left:100%"></i></div><span class="mono small">{{ b.pct }}%</span></div>
      </div>
    </div>

    <div class="h-sec">成员开发活动图 · 样例数据</div>
    <div class="contrib-wrap" id="contrib">
      <div v-for="c in contribs" :key="c.m.id" class="contrib">
        <div class="chead">
          <span class="avatar" :class="'a' + c.m.id">{{ memberLabel(c.m.id) }}</span>{{ c.m.name }}
          <span class="small mono" style="margin-left:auto">样例活动 {{ c.total }} · 活跃天数 {{ c.activeDays }}</span>
        </div>
        <div class="small" style="margin-bottom:8px;color:var(--muted)">{{ project.memberRoleText(c.m.id) }}</div>
        <div class="contrib-grid">
          <span class="grid-corner"></span>
          <span v-for="w in 6" :key="'h' + w" class="week-label" :style="{ gridColumn: w + 1, gridRow: 1 }">W{{ w }}</span>
          <template v-for="(row, d) in c.rows" :key="'d' + d">
            <span class="day-label" :style="{ gridColumn: 1, gridRow: d + 2 }">{{ DAY_LABELS[d] }}</span>
            <span
              v-for="(v, w) in row" :key="'c' + d + '-' + w"
              class="c-cell" :class="'c' + v"
              :style="{ gridColumn: w + 2, gridRow: d + 2 }"
              :title="`${c.m.name} · W${w + 1} · 周${DAY_LABELS[d]}\n样例活动：${v}\n非真实 GitHub 数据`"
            ></span>
          </template>
        </div>
      </div>
      <div class="contrib-note">
        <span>每格 = 该成员某一天的开发活动</span>
        <span>无活动</span><span class="sw c0"></span>
        <span>少</span><span class="sw c1"></span>
        <span>一般</span><span class="sw c2"></span>
        <span>较多</span><span class="sw c3"></span>
        <span>活跃</span><span class="sw c4"></span>
        <span>样例数据 · 非真实 GitHub 事件</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.profile-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.pcard { border: 1px solid var(--line, #dcdcdc); border-radius: 10px; padding: 12px; background: var(--panel, rgba(255,255,255,.5)); }
.phead { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.phead b { font-size: 15px; }
.badge { font-size: 12px; padding: 1px 8px; border-radius: 999px; background: rgba(0,0,0,.08); }
.phead .edit { margin-left: auto; font-size: 12px; padding: 3px 10px; }
.ptitle { margin-top: 4px; font-size: 13px; font-weight: 600; }
.psum { margin: 4px 0 8px; }
.pdim { margin-top: 8px; }
.pdim-t { font-size: 12px; opacity: .75; margin-bottom: 4px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; padding: 2px 8px; border-radius: 999px; background: rgba(0,0,0,.06); }
.chip .bar { display: inline-block; width: 34px; height: 5px; border-radius: 3px; background: rgba(0,0,0,.15); overflow: hidden; }
.chip .bar b { display: block; height: 100%; background: var(--green, #2e7d32); }
</style>
