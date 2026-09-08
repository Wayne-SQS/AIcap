<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { MEMBERS, BANDWIDTH } from '@/data/seed'

/* 成员任务图:结构对齐旧版 L562-577,逻辑对齐 renderMembers(L1042-1055)/
   renderBandwidth(L1210-1217)/renderContrib(L1219-1226) */
const project = useProjectStore()

const load = computed(() => project.loadByOwner)
const total = computed(() => project.tasks.reduce((a, t) => a + t.h, 0))

const memberCards = computed(() => MEMBERS.map(m => {
  const mine = project.tasks.filter(t => t.owner === m.id)
  const myH = mine.reduce((a, t) => a + t.h, 0)
  const pct = Math.round(myH / total.value * 100)
  const myStories = project.stories.filter(s => s.owner === m.id)
  const lv = myH >= 56 ? 'hot' : (myH >= 40 ? 'mid' : 'ok')
  const lvTxt = myH >= 56 ? '过载' : (myH >= 40 ? '偏忙' : '正常')
  return { m, mine: mine.length, myH, pct, myStories: myStories.length, lv, lvTxt }
}))

const bandwidthCards = computed(() => MEMBERS.map((m, i) => {
  const alloc = load.value[i].reduce((a, b) => a + b, 0)
  const cap = BANDWIDTH[i].reduce((a, b) => a + b, 0)
  return { m, alloc, cap, pct: Math.round(alloc / cap * 100), over: alloc > cap }
}))

/* 贡献绿格子:确定性伪随机(对齐旧版 seeded/makeContrib L1191-1208) */
function seeded(s) {
  let x = s >>> 0
  return () => { x = (x * 1664525 + 1013904223) >>> 0; return x / 4294967296 }
}
function makeContrib() {
  const out = []
  for (let m = 0; m < 4; m++) {
    const rnd = seeded(1000 + m * 77), weeks = []
    for (let w = 0; w < 12; w++) {
      const days = [], idle = 0.5 - (w / 11) * 0.32
      for (let d = 0; d < 7; d++) {
        const v = rnd(); let lv = 0
        if (v > idle) { lv = v > idle + (1 - idle) * 0.75 ? 4 : v > idle + (1 - idle) * 0.5 ? 3 : v > idle + (1 - idle) * 0.25 ? 2 : 1 }
        days.push(lv)
      }
      weeks.push(days)
    }
    out.push(weeks)
  }
  return out
}
const CONTRIB = makeContrib()
const contribs = MEMBERS.map((m, i) => {
  const flat = CONTRIB[i].flat()
  return { m, cells: flat.map((v, d) => ({ v, d })), total: flat.reduce((a, b) => a + b, 0) }
})
function heatLv(h) { return h >= 16 ? 3 : h >= 10 ? 2 : h >= 4 ? 1 : 0 }
</script>

<template>
  <section class="view" id="view-members">
    <div class="hero">
      <div>
        <div class="eyebrow">TEAM LOAD / 成员负载</div>
        <h1>成员任务图</h1>
        <p>4 人分工 · 6 周负载热力图 · 闲置与过载预警。</p>
      </div>
    </div>
    <div class="member-grid" id="member-grid">
      <div v-for="c in memberCards" :key="c.m.id" class="mcard">
        <div class="head">
          <span class="avatar" :class="'a' + c.m.id">P{{ c.m.id + 1 }}</span>
          <div><h3>{{ c.m.name }}</h3><div class="role">{{ c.m.role }}</div></div>
          <span class="loadflag" :class="c.lv" style="margin-left:auto">{{ c.lvTxt }}</span>
        </div>
        <div class="tagline" style="font-size:12px;color:var(--muted);margin-bottom:12px">{{ c.m.tag }}</div>
        <div class="statline"><span>任务 <b>{{ c.mine }}</b></span><span>工时 <b>{{ c.myH }}h</b></span><span>占比 <b>{{ c.pct }}%</b></span><span>故事 <b>{{ c.myStories }}</b></span></div>
        <div class="track"><i :style="{ width: c.pct + '%', background: `var(--${['green', 'orange', 'blue', 'pink'][c.m.id]})` }"></i></div>
      </div>
    </div>
    <div class="h-sec">周负载热力图</div>
    <div class="heat-wrap">
      <div class="heat" id="heat">
        <div class="heat-head"><div>成员 \ 周</div><div v-for="w in 6" :key="w">W{{ w }}</div></div>
        <div v-for="(m, i) in MEMBERS" :key="m.id" class="heat-row">
          <div class="heat-name"><span class="avatar" :class="'a' + m.id">P{{ m.id + 1 }}</span>{{ m.name }}</div>
          <div v-for="(h, w) in load[i]" :key="w" class="heat-cell"><span class="hrs" :class="'hl' + heatLv(h)">{{ h || '·' }}</span></div>
        </div>
      </div>
    </div>
    <div class="h-sec">容量与分配 · Bandwidth</div>
    <div id="bandwidth">
      <div v-for="b in bandwidthCards" :key="b.m.id" class="mcard">
        <div class="head">
          <span class="avatar" :class="'a' + b.m.id">P{{ b.m.id + 1 }}</span>
          <div><h3>{{ b.m.name }}</h3><div class="role">可用容量 {{ b.cap }}h / 6周 · 已分配 {{ b.alloc }}h</div></div>
          <span class="loadflag" :class="b.over ? 'hot' : 'ok'" style="margin-left:auto">{{ b.over ? '超载' : '充足' }}</span>
        </div>
        <div class="bw-row"><span class="small">已分配</span><div class="bw-track"><i class="used" :style="{ width: Math.min(b.pct, 100) + '%' }"></i><i class="cap" style="left:100%"></i></div><span class="mono small">{{ b.pct }}%</span></div>
      </div>
    </div>
    <div class="h-sec">成员贡献绿格子 · 样例数据</div>
    <div class="contrib-wrap" id="contrib">
      <div v-for="c in contribs" :key="c.m.id" class="contrib">
        <div class="chead"><span class="avatar" :class="'a' + c.m.id">P{{ c.m.id + 1 }}</span>{{ c.m.name }}<span class="small mono" style="margin-left:auto">近 12 周 · 活动强度 {{ c.total }}</span></div>
        <div class="contrib-grid">
          <div v-for="cell in c.cells" :key="cell.d" class="c-cell" :class="'c' + cell.v" :title="`成员${c.m.id + 1} · 第${Math.floor(cell.d / 7) + 1}周 周${cell.d % 7 + 1} · 活跃度 ${cell.v}`"></div>
        </div>
      </div>
      <div class="c-legend"><span>少</span><span class="sw c0"></span><span class="sw c1"></span><span class="sw c2"></span><span class="sw c3"></span><span class="sw c4"></span><span>多</span><span style="margin-left:10px">样例数据（非真实 GitHub 事件）</span></div>
    </div>
  </section>
</template>
