<script setup>
/* UML 图:结构对齐旧版 L580-593,逻辑对齐 renderUML(L1058-1088)
   用例图坐标静态布局 + 连线旋转定位,时序图泳道消息 */
const actors = [
  { n: '管理员', y: 30 }, { n: '项目负责人', y: 130 }, { n: '团队成员', y: 230 }, { n: '普通用户', y: 330 }
]
const ucs = [
  { t: '登录 / 退出', x: 250, y: 40, c: '' },
  { t: '管理项目与成员', x: 250, y: 105, c: '' },
  { t: '配置角色与权限', x: 250, y: 170, c: '' },
  { t: '维护需求故事', x: 470, y: 80, c: '' },
  { t: '安排任务与排期', x: 470, y: 150, c: '' },
  { t: '更新看板状态', x: 470, y: 220, c: '' },
  { t: '查看报表', x: 690, y: 120, c: 'dim' },
  { t: '生成 UML 图', x: 250, y: 300, c: 'warn' },
  { t: 'AI 辅助分析', x: 470, y: 310, c: 'ai' }
]
const links = [
  { a: 0, u: 0 }, { a: 0, u: 1 }, { a: 0, u: 2 }, { a: 1, u: 3 }, { a: 1, u: 4 },
  { a: 2, u: 5 }, { a: 3, u: 6 }, { a: 1, u: 7 }, { a: 2, u: 8 }
]
const lines = links.map(l => {
  const a = actors[l.a], u = ucs[l.u]
  const x1 = 130, y1 = a.y + 20, x2 = u.x, y2 = u.y + 20
  const len = Math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2), ang = Math.atan2(y2 - y1, x2 - x1)
  return { left: x1, top: y1, width: len, transform: `rotate(${ang}rad)` }
})
const seqLanes = ['用户', '爱管理', '权限服务', '项目库']
const seqMsgs = [
  ['登录请求', '0,1'], ['校验会话与权限', '1,2'], ['创建项目 + 添加成员', '1,3'],
  ['返回成员列表', '3,1'], ['显示项目空间', '1,0']
].map((m, i) => ({ label: `${i + 1}. ${m[0]}`, r: m[1].split(',')[0] < m[1].split(',')[1] ? false : true }))
</script>

<template>
  <section class="view" id="view-uml">
    <div class="hero">
      <div>
        <div class="eyebrow">UML DIAGRAMS / 设计视图</div>
        <h1>UML 图</h1>
        <p>用例图 · 时序图（从看板与甘特数据自动生成）。</p>
      </div>
    </div>
    <p class="uml-note">演示版：用例图由需求数据汇总生成；时序图示例展示“创建项目”主流程的消息交互。完整版支持交互编辑。</p>
    <div class="h-sec">用例图 · Use Case</div>
    <div class="uml-canvas">
      <div class="uc-wrap" id="uc">
        <div class="sysbound"><span class="sysname">爱管理系统</span></div>
        <div v-for="a in actors" :key="a.n" class="actor" :style="{ top: a.y + 'px' }"><div class="fig"></div><span>{{ a.n }}</span></div>
        <div v-for="u in ucs" :key="u.t" class="uc" :class="u.c" :style="{ left: u.x + 'px', top: u.y + 'px' }">{{ u.t }}</div>
        <div v-for="(l, i) in lines" :key="i" class="line" :style="{ left: l.left + 'px', top: l.top + 'px', width: l.width + 'px', transform: l.transform }"></div>
      </div>
    </div>
    <div class="h-sec">时序图 · Sequence（创建项目）</div>
    <div class="uml-canvas">
      <div class="seq" id="seq">
        <div class="seq-lifelines">
          <div v-for="l in seqLanes" :key="l" class="ll"><div class="box">{{ l }}</div><div class="dash"></div></div>
        </div>
        <div class="seq-msgs">
          <div v-for="m in seqMsgs" :key="m.label" class="seq-msg"><span class="lbl">{{ m.label }}</span><span class="arrow" :class="{ r: m.r }"></span></div>
        </div>
      </div>
    </div>
  </section>
</template>
