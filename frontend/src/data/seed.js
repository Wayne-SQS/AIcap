/* 种子数据(离线演示模式用)
 * 基线:US01–US37 需求 + T01–T16 执行任务 + 5 名真实成员(含只读查看者)
 * 与 Java 后端 DataSeeder 逐字段一致(见 java-backend/src/main/java/com/aicap/config/DataSeeder.java),
 * 保证离线演示与在线后端同源;成员容量取自后端 users.capacity_hours。
 * 迁移来源:旧版 index.html(M01–M23 基线)→ legacy/index.html(mcc 提交 9b14ec5 的 US01–US37 基线)
 */

export const SEED = [
  { id: 'US01', activity: 1, sprint: 1, title: '项目与成员范围', description: '作为管理员，我希望创建项目并管理成员，以便限定协作范围', priority: 'Must', acceptance: '成员可进入所属项目；非成员访问被拒绝', owner: 0, status: 2 },
  { id: 'US02', activity: 1, sprint: 1, title: '角色与权限边界', description: '作为管理员，我希望配置角色和权限，以便控制操作边界', priority: 'Should', acceptance: '只读角色不能修改项目数据', owner: 1, status: 2 },
  { id: 'US03', activity: 2, sprint: 1, title: '史诗、故事与验收条件', description: '作为负责人，我希望维护史诗、故事和验收条件，以便形成需求基线', priority: 'Must', acceptance: '故事包含角色、目标、价值、优先级和稳定 ID', owner: 1, status: 1 },
  { id: 'US04', activity: 2, sprint: 1, title: '用户故事地图', description: '作为成员，我希望按活动和 Sprint 查看故事地图，以便理解版本目标', priority: 'Must', acceptance: '地图至少包含三个发布切片并与故事数据一致', owner: 1, status: 0 },
  { id: 'US05', activity: 3, sprint: 1, title: '任务拆分与指派', description: '作为负责人，我希望拆分任务、分配负责人并设定日期，以便成员开展工作', priority: 'Must', acceptance: '任务关联故事且负责人和日期可校验', owner: 2, status: 0 },
  { id: 'US06', activity: 4, sprint: 1, title: '任务状态协同', description: '作为成员，我希望更新任务状态，以便团队知道进展', priority: 'Must', acceptance: '状态、负责人、优先级可见并记录变更', owner: 2, status: 1 },
  { id: 'US07', activity: 5, sprint: 1, title: '基础报表', description: '作为查看者，我希望查看项目进度，以便掌握当前状态', priority: 'Should', acceptance: '报表与任务清单一致', owner: 3, status: 0 },
  { id: 'US08', activity: 1, sprint: 2, title: '四视图权限一致', description: '作为管理员，我希望不同视图遵守统一权限，以便协作可控', priority: 'Should', acceptance: '看板、地图、甘特和 UML 按角色校验', owner: 0, status: 0 },
  { id: 'US09', activity: 3, sprint: 4, title: '拖拽与 WIP 控制', description: '作为成员，我希望拖拽卡片并限制 WIP，以便控制流程', priority: 'Could', acceptance: '后续路线，不纳入本学期六周承诺', owner: 2, status: 0 },
  { id: 'US10', activity: 3, sprint: 1, title: '甘特图', description: '作为负责人，我希望编辑任务排期和里程碑，以便掌握交付节奏', priority: 'Must', acceptance: '任务依赖、关键路径和里程碑清晰', owner: 2, status: 0 },
  { id: 'US11', activity: 3, sprint: 1, title: '成员任务图', description: '作为负责人，我希望查看成员任务和负载，以便合理分配工作', priority: 'Must', acceptance: '成员、任务、周负载和容量数据一致', owner: 2, status: 0 },
  { id: 'US12', activity: 4, sprint: 2, title: '四视图数据联动', description: '作为成员，我希望状态和指派变更同步到各视图，以便避免重复维护', priority: 'Must', acceptance: '同一数据源驱动四视图', owner: 1, status: 0 },
  { id: 'US13', activity: 4, sprint: 2, title: 'UML 自动生成与编辑', description: '作为设计成员，我希望生成并编辑 UML 图，以便支持设计协作', priority: 'Should', acceptance: '图来源可追溯并支持修改预览', owner: 3, status: 0 },
  { id: 'US14', activity: 2, sprint: 3, title: 'AI PRD 拆解', description: '作为负责人，我希望 AI 拆解需求并估算工作量，以便辅助需求细化', priority: 'Should', acceptance: '输出事实、依据和不确定性', owner: 2, status: 0 },
  { id: 'US15', activity: 5, sprint: 4, title: '筛选与趋势分析', description: '作为负责人，我希望筛选并对比趋势，以便识别变化', priority: 'Could', acceptance: '后续路线，不纳入本学期六周承诺', owner: 1, status: 0 },
  { id: 'US16', activity: 1, sprint: 3, title: 'AI 数据访问权限', description: '作为管理员，我希望控制 AI 可访问的数据范围，以便保护项目数据', priority: 'Should', acceptance: '越权 AI 读取和写入都被阻止', owner: 0, status: 0 },
  { id: 'US17', activity: 2, sprint: 2, title: 'AI 建议修订与来源追溯', description: '作为负责人，我希望修订 AI 建议并追溯来源，以便保持可审计', priority: 'Must', acceptance: '保留前后差异、证据和审核记录', owner: 2, status: 0 },
  { id: 'US18', activity: 3, sprint: 3, title: '智能排期', description: '作为负责人，我希望 AI 综合依赖、优先级和容量给出排期，以便获得可执行安排', priority: 'Must', acceptance: '说明依据并经人工确认', owner: 2, status: 0 },
  { id: 'US19', activity: 4, sprint: 3, title: '进度预测', description: '作为负责人，我希望根据历史和当前范围预测交付，以便提前调整', priority: 'Should', acceptance: '说明历史范围和预测假设', owner: 1, status: 0 },
  { id: 'US20', activity: 4, sprint: 3, title: '风险预警', description: '作为负责人，我希望收到延期、阻塞和过载风险，以便及时采取行动', priority: 'Must', acceptance: '风险包含证据、影响和建议', owner: 0, status: 0 },
  { id: 'US21', activity: 5, sprint: 4, title: '图与任务影响传播', description: '作为设计成员，我希望查看图变更对任务的影响，以便保持一致', priority: 'Could', acceptance: '后续路线，不纳入本学期六周承诺', owner: 3, status: 0 },
  { id: 'US22', activity: 5, sprint: 3, title: '代码文档测试质量分析', description: '作为团队成员，我希望分析代码、文档和测试质量，以便发现缺口', priority: 'Should', acceptance: '结论引用真实证据，不虚构测试', owner: 3, status: 0 },
  { id: 'US23', activity: 5, sprint: 3, title: '效率优化', description: '作为负责人，我希望识别瓶颈并转成改进任务，以便优化流程', priority: 'Should', acceptance: '改进建议可追踪结果', owner: 0, status: 0 },
  { id: 'US24', activity: 5, sprint: 3, title: 'AI 闭环', description: '作为负责人，我希望建议经审核后执行并可回滚，以便形成可追溯闭环', priority: 'Must', acceptance: '记录建议、审核、执行和回滚', owner: 0, status: 0 },
  { id: 'US25', activity: 5, sprint: 1, title: '实时项目进度', description: '作为负责人，我希望查看实时或准实时进度，以便判断 Sprint 是否偏离目标', priority: 'Must', acceptance: '刷新后与底层数据一致', owner: 0, status: 0 },
  { id: 'US26', activity: 2, sprint: 1, title: '需求池', description: '作为负责人，我希望保存未确认需求，以便不丢失来源并控制承诺', priority: 'Must', acceptance: '需求池记录来源、优先级和状态', owner: 1, status: 0 },
  { id: 'US27', activity: 3, sprint: 1, title: '成员 Bandwidth', description: '作为负责人，我希望维护成员容量和已分配工时，以便避免超载', priority: 'Must', acceptance: '容量口径统一且可计算', owner: 2, status: 0 },
  { id: 'US28', activity: 4, sprint: 2, title: '成员开发活动图', description: '作为负责人，我希望查看成员活动分布，以便了解阶段性工作活跃度', priority: 'Should', acceptance: '明确标记活动统计口径', owner: 1, status: 0 },
  { id: 'US29', activity: 4, sprint: 2, title: '会议录音与转写', description: '作为主持人，我希望在知情同意后录音并生成可编辑转写，以便提取决策', priority: 'Must', acceptance: '保留时间和说话人信息', owner: 1, status: 0 },
  { id: 'US30', activity: 4, sprint: 2, title: '会议总结与行动项', description: '作为 DRI，我希望会议智能体生成摘要、决议和行动项，以便形成记录', priority: 'Must', acceptance: '不明确责任人或日期时标记待确认', owner: 1, status: 0 },
  { id: 'US31', activity: 2, sprint: 2, title: '会议到需求修改建议', description: '作为负责人，我希望会议决议生成故事修改建议，以便更新需求基线', priority: 'Must', acceptance: '建议包含证据和前后差异', owner: 1, status: 0 },
  { id: 'US32', activity: 3, sprint: 2, title: '会议到任务调整建议', description: '作为负责人，我希望会议识别过载和依赖冲突并提出调整建议，以便降低协调成本', priority: 'Must', acceptance: '建议必须人工批准', owner: 0, status: 0 },
  { id: 'US33', activity: 4, sprint: 2, title: 'AI 建议审核中心', description: '作为负责人，我希望采纳、修改或拒绝 AI 建议，以便保持人在回路', priority: 'Must', acceptance: '记录审核人、结果、理由和影响范围', owner: 0, status: 0 },
  { id: 'US34', activity: 4, sprint: 3, title: 'GitHub 仓库与成员映射', description: '作为管理员，我希望绑定仓库并映射成员，以便分析真实开发活动', priority: 'Must', acceptance: '同步 commit、push、PR、review、issue', owner: 0, status: 0 },
  { id: 'US35', activity: 4, sprint: 3, title: '任务提交智能体工作状态分析', description: '作为负责人，我希望分析提交、任务关联和协作行为，以便发现阻塞和无进展', priority: 'Must', acceptance: '输出事实、时间范围、证据和不确定性', owner: 3, status: 0 },
  { id: 'US36', activity: 4, sprint: 3, title: '成员工作状态汇总', description: '作为负责人，我希望查看成员完成内容、负载和工作摘要，以便进行有依据的协调', priority: 'Must', acceptance: '区分事实与推断并支持下钻', owner: 3, status: 0 },
  { id: 'US37', activity: 5, sprint: 3, title: '项目层面协调建议', description: '作为负责人，我希望在项目层面获得可解释的协调建议，以便处理异常模式', priority: 'Should', acceptance: '建议说明范围、依据和影响', owner: 0, status: 0 }
]

/* 看板与甘特「强制血缘对应」:开发任务(card)必须挂看板卡;管理任务(type=management)不挂卡
   status:0待办 1进行中 2完成 3已取消;progress=完成百分比;blocked=阻塞标记;
   dependsOn=前置任务(T 编号,逗号分隔);eh=预估工时(加权进度分母) */
export const TASK_CARD_LINKS = {
  T03: 'US01', T04: 'US03', T05: 'US07', T06: 'US25', T07: 'US10', T08: 'US13',
  T09: 'US08', T10: 'US29', T11: 'US31', T12: 'US14', T13: 'US16', T14: 'US34'
}

export const MANAGEMENT_TASK_IDS = ['T01', 'T02', 'T15', 'T16']

const TASK_ROWS = [
  { id: 'T01', name: '启动会、基线、六项材料初稿', owner: 0, h: 12, w: [1, 1], story: 'US01,US03,US04', dependsOn: '', status: 0, progress: 0, blocked: false },
  { id: 'T02', name: '共享数据、接口、AI 接入约定；UML 及排期小样', owner: 2, h: 12, w: [1, 1], story: 'US10,US13,US18', dependsOn: 'T01', status: 0, progress: 0, blocked: false },
  { id: 'T03', name: '项目、成员、自定义权限 / US01、US02', owner: 0, h: 16, w: [1, 2], story: 'US01,US02', dependsOn: 'T02', status: 1, progress: 50, blocked: false },
  { id: 'T04', name: '故事、地图、任务、基础看板 / US03-US06', owner: 1, h: 16, w: [1, 2], story: 'US03,US04,US05,US06', dependsOn: 'T02,T03', status: 1, progress: 50, blocked: false },
  { id: 'T05', name: '基础报表、历史记录、Sprint 1 验证 / US07', owner: 3, h: 12, w: [2, 2], story: 'US07', dependsOn: 'T03,T04', status: 0, progress: 0, blocked: false },
  { id: 'T06', name: '实时进度、需求池、成员 Bandwidth / US25-US27', owner: 1, h: 12, w: [2, 2], story: 'US25,US26,US27', dependsOn: 'T03,T04', status: 0, progress: 0, blocked: false },
  { id: 'T07', name: '甘特图与成员任务图 / US10、US11', owner: 2, h: 12, w: [2, 2], story: 'US10,US11,US27', dependsOn: 'T02,T04,T06', status: 0, progress: 0, blocked: false },
  { id: 'T08', name: 'UML 自动生成与交互编辑、AI 项目规划画布 MVP / US13', owner: 3, h: 20, w: [3, 4], story: 'US13,US10,US11,US04', dependsOn: 'T04,T06,T07', status: 0, progress: 0, blocked: false },
  { id: 'T09', name: '权限扩展、四视图联动、成员贡献活动图 / US08、US12、US28', owner: 0, h: 8, w: [3, 4], story: 'US08,US12,US28', dependsOn: 'T06,T07,T08', status: 0, progress: 0, blocked: false },
  { id: 'T10', name: '会议智能体 MVP：录音、知情同意、可编辑转写、会议总结', owner: 1, h: 16, w: [3, 4], story: 'US29,US30', dependsOn: 'T04,T06,T09', status: 0, progress: 0, blocked: false },
  { id: 'T11', name: '会议分析、需求/人员/任务调整建议与 AI 建议审核中心', owner: 0, h: 16, w: [4, 4], story: 'US31,US32,US33', dependsOn: 'T09,T10', status: 0, progress: 0, blocked: false },
  { id: 'T12', name: 'AI 需求拆解、故事修订与来源追溯、进度预测、智能排期', owner: 2, h: 12, w: [4, 5], story: 'US14,US17,US18,US19', dependsOn: 'T05,T07', status: 0, progress: 0, blocked: false },
  { id: 'T13', name: 'AI 代码/文档/测试质量分析、风险预警、效率优化、AI 闭环', owner: 0, h: 12, w: [5, 6], story: 'US16,US20,US22,US23,US24', dependsOn: 'T09,T10,T11,T12', status: 0, progress: 0, blocked: false },
  { id: 'T14', name: 'GitHub 绑定、任务提交智能体、成员状态汇总、项目协调建议', owner: 3, h: 12, w: [5, 6], story: 'US34,US35,US36,US37', dependsOn: 'T03,T04,T09,T13', status: 0, progress: 0, blocked: false },
  { id: 'T15', name: '完整回归、联调修正、部署与演示准备', owner: 2, h: 12, w: [6, 6], story: 'US08,US12,US13,US24', dependsOn: 'T11,T12,T13,T14', status: 0, progress: 0, blocked: false },
  { id: 'T16', name: 'Scrum 评审 / 复盘 / 轮值交接 / 记录归档', owner: 0, h: 8, w: [1, 6], story: 'US07,US24,US37', dependsOn: '', status: 0, progress: 0, blocked: false }
]

/** 排期(w1..w2)→ Sprint 列表:每 2 周一个 Sprint(对齐后端 TaskDtos.sprintsOf) */
export function derivedTaskSprints(weekStart, weekEnd) {
  const out = []
  for (let w = Math.max(1, weekStart); w <= Math.min(6, weekEnd); w++) {
    const s = Math.floor((w - 1) / 2) + 1
    if (!out.includes(s)) out.push(s)
  }
  return out
}

export const TASKS = TASK_ROWS.map(t => ({
  ...t,
  eh: t.h,
  card: TASK_CARD_LINKS[t.id] || null,
  type: MANAGEMENT_TASK_IDS.includes(t.id) ? 'management' : 'feature',
  sprints: derivedTaskSprints(t.w[0], t.w[1])
}))

/* 5 名真实成员(与后端 users 表一致):4 名项目成员 + 1 名只读查看者
   role=展示用职责描述;roleKey=后端角色(admin/owner/member/viewer);capacity=6 周可用容量(h) */
export const MEMBERS = [
  { id: 0, name: '李锐铭', role: '产品与范围 · 总协调', roleKey: 'admin', tag: '权限 / 公共服务 / 风险闭环', capacity: 60, accent: 'green' },
  { id: 1, name: '高思晗', role: '需求与 AI 协调', roleKey: 'owner', tag: '故事管理 / 会议智能体 / AI 审核', capacity: 48, accent: 'orange' },
  { id: 2, name: '孙秋实', role: '技术与进度管理', roleKey: 'member', tag: '数据接口 / WBS / 甘特 / 智能排期', capacity: 60, accent: 'blue' },
  { id: 3, name: '罗子涵', role: '质量与风险', roleKey: 'member', tag: 'UML / 质量分析 / 方案评审', capacity: 54, accent: 'pink' },
  { id: 4, name: '成员5', role: '只读查看者', roleKey: 'viewer', tag: '基础报表 / 只读视图', capacity: 60, accent: 'gray' }
]

export const ROLE_TXT = { admin: '管理员', owner: '负责人', member: '成员', viewer: '查看者' }

/* 成员着色:索引 → CSS 变量名(第 5 位只读查看者用中性灰) */
export const MEMBER_COLORS = ['green', 'orange', 'blue', 'pink', 'gray']

export const MILESTONES = [
  { id: 'M1', week: 1, name: '需求与规划基线', desc: '六项实验材料完整；≥3 切片；≥2 AI 角色记录' },
  { id: 'M2', week: 2, name: '第一次 Sprint 评审', desc: 'Sprint 1 基础数据与管理闭环验收' },
  { id: 'M3', week: 4, name: '中期评审', desc: '会议智能体、审核中心与四视图协同演示' },
  { id: 'M4', week: 6, name: '成果展演', desc: 'GitHub 智能体、能力画像与 AI 闭环验收' }
]

export const SPRINTS = [
  { name: 'Sprint 1', tag: '行走骨架', cls: 's1' },
  { name: 'Sprint 2', tag: '会议闭环与视图协同', cls: 's2' },
  { name: 'Sprint 3', tag: 'GitHub 分析与 AI 闭环', cls: 's3' }
]

/** 故事地图的发布切片:第 4 条收纳 Sprint ≥4 的后续路线(对齐 legacy 新版 map) */
export const MAP_SPRINTS = [
  { number: 1, name: 'Sprint 1', tag: '基础数据与管理闭环', cls: 's1', matches: s => s.sprint === 1 },
  { number: 2, name: 'Sprint 2', tag: '四视图协同与会议智能体', cls: 's2', matches: s => s.sprint === 2 },
  { number: 3, name: 'Sprint 3', tag: 'GitHub 智能体与 AI 闭环', cls: 's3', matches: s => s.sprint === 3 },
  { number: 4, name: 'Sprint 4+', tag: '后续路线', cls: 's4', matches: s => s.sprint >= 4 }
]

export const AIS = [
  { ic: '✂', name: '智能需求拆解', desc: '解析 PRD，提取实体/关系/优先级，形成故事并估算工作量。' },
  { ic: '◷', name: '进度预测', desc: '结合历史与当前范围，预测交付日期并说明假设。' },
  { ic: '▦', name: '智能排期', desc: '综合人员能力、任务依赖、优先级和容量生成排期建议。' },
  { ic: '⚠', name: '风险预警', desc: '多维识别延期及质量风险，给出可落实的应对方案。' },
  { ic: '✔', name: '质量分析', desc: '代码、文档、测试质量自动评估，定位缺口。' },
  { ic: '↻', name: '效率优化', desc: '识别瓶颈、提出流程改进、跟踪结果并回流任务。' }
]

export const CHAT_KB = [
  { kw: '拆解', reply: '【需求拆解】以「成员任务图」为例可拆为：① 任务查看与分发 ② 负载热力图 ③ 均衡分配 ④ 闲置预警。每项建议补充验收条件与负责人，并标注不确定项。' },
  { kw: '负载', reply: '【负载评估】当前累计工时最高的是李锐铭（T01/T03/T09/T11/T13/T16 合计 72h，容量 60h，已超载），其次是孙秋实 48h/60h。建议把 T11 会议分析建议后置或由高思晗分担，避免单点过载。' },
  { kw: '风险', reply: '【风险预警】当前三大风险：① UML 自动生成不确定性高 ② AI 估算可能乐观 ③ 视图口径易不一致。建议 W2 前各留一个验证小样，不要拖到 W6。' },
  { kw: '排期', reply: '【排期建议】Sprint 2 建议：T06 报表与需求池 → T07 甘特与成员图 → T10 会议智能体（W3–W4）。注意 T07 依赖 T02/T04/T06，需先完成共享数据与接口约定。' },
  { kw: '故事', reply: '【故事补全】建议按「作为…，我希望…，以便…」补全价值表述，并确认骨干活动与 MoSCoW 优先级，保持与看板/地图数据一致。' }
]

export const POOL_SEED = [
  { id: 'R01', title: '支持第三方账号登录（微信 / 企业微信）', desc: '会议中提出，尚未确认是否纳入 6 周范围。', source: '会议 2026-09-06 · 高思晗', created: '09-06', priority: 'Could' },
  { id: 'R02', title: '报表导出为 PDF', desc: '老师反馈建议，待确认是否替代 CSV 导出。', source: '课堂反馈 · 李锐铭', created: '09-06', priority: 'Should' },
  { id: 'R03', title: '多语言界面（i18n）', desc: '候选 Won\'t，蓝图外扩展，暂缓。', source: '范围讨论 · 孙秋实', created: '09-06', priority: 'Could' },
  { id: 'R04', title: '移动端原生客户端', desc: '明确 Won\'t，本学期不做。', source: '范围讨论 · 孙秋实', created: '09-06', priority: 'Could' }
]

export const SUGGESTION_SEED = [
  { id: 'SG01', agent: '会议智能体', kind: 'meeting', time: '09-06 14:22', evidence: '会议决议 #3：确认 Sprint 2 聚焦会议闭环', affected: 'US14（AI PRD 拆解）', change: [{ t: '优先级 Must', d: true }, { t: '优先级 Should', d: false }], note: '拆解能力保留，但不高于两个核心智能体。', status: 'pending' },
  { id: 'SG02', agent: '会议智能体', kind: 'meeting', time: '09-06 14:25', evidence: '转写片段：\'成员任务图暂时先做负载热力图\'', affected: '新需求 → 需求池', change: [{ t: '新增需求：成员开发活动图后置', d: false }], note: '新需求默认进入需求池，不提前承诺。', status: 'pending' },
  { id: 'SG03', agent: '任务提交智能体', kind: 'submit', time: '09-06 18:05', evidence: '罗子涵 近 3 天无 commit，且 T08 UML 自动生成未关闭', affected: 'T08 · 罗子涵', change: [{ t: '状态：进行中', d: true }, { t: '状态：需检查阻塞', d: false }], note: '建议确认是否被接口联调阻塞，或重新分配。', status: 'pending' },
  { id: 'SG04', agent: '任务提交智能体', kind: 'submit', time: '09-06 18:08', evidence: '罗子涵 昨日 6 次提交集中在 T08 UML，变更规模大', affected: 'T08 · 罗子涵', change: [{ t: '负载：正常', d: true }, { t: '负载：偏忙', d: false }], note: '建议把 T08 编辑交互后置 W4，或由孙秋实支援。', status: 'approved' }
]

/** 登录快捷账号:后端演示用户(密码 123456) */
export const ACCOUNTS = ['李锐铭', '高思晗', '孙秋实', '罗子涵', '成员5']
