/* 种子数据:逐字迁移自 index.html L750-832 / L1126-1149 / L1189,勿改内容 */
export const SEED = [
  { id: 'M01', activity: 1, sprint: 1, title: '登录与退出系统', description: '作为用户，我希望登录/退出系统，以便安全使用平台', priority: 'Must', acceptance: '未登录不可访问项目页；会话过期处理', owner: 0, status: 2 },
  { id: 'M02', activity: 1, sprint: 1, title: '创建项目并添加成员', description: '作为管理员，我希望创建项目并添加成员，以便限定协作范围', priority: 'Must', acceptance: '成员可进入所属项目，非成员页面与接口拒绝；关联 US01', owner: 1, status: 2 },
  { id: 'M03', activity: 1, sprint: 1, title: '创建角色与分配权限', description: '作为管理员，我希望创建角色并分配权限，以便控制操作边界', priority: 'Must', acceptance: '新建只读角色确实改不了东西；关联 US02', owner: 2, status: 1 },
  { id: 'M04', activity: 2, sprint: 1, title: '维护史诗、故事与验收条件', description: '作为项目负责人，我希望维护史诗、故事和验收条件，以便形成需求基线', priority: 'Must', acceptance: '故事含角色/目标/价值/优先级/稳定ID；关联 US03', owner: 3, status: 1 },
  { id: 'M05', activity: 2, sprint: 1, title: '按活动与迭代浏览故事地图', description: '作为团队成员，我希望按活动和 Sprint 查看故事地图，以便理解版本目标', priority: 'Must', acceptance: '至少 3 个发布切片、与需求条目数据一致；关联 US04', owner: 0, status: 0 },
  { id: 'M06', activity: 3, sprint: 1, title: '拆分任务并指派负责人', description: '作为项目负责人，我希望把故事拆成任务并指派负责人、设定日期，以便成员开展工作', priority: 'Must', acceptance: '任务关联故事，负责人/日期合法可再读；关联 US05', owner: 1, status: 0 },
  { id: 'M07', activity: 3, sprint: 1, title: '在看板上更新任务状态', description: '作为团队成员，我希望在看板上把任务从待办移到进行中/完成，以便团队知道进展', priority: 'Must', acceptance: '状态/负责人/优先级可见，刷新保留，记录变更历史；关联 US06', owner: 2, status: 1 },
  { id: 'M08', activity: 4, sprint: 1, title: '查看项目基础报表', description: '作为普通用户，我希望查看基础报表，以便掌握当前进度', priority: 'Must', acceptance: '状态数/完成比例与任务清单一致；空项目正确；关联 US07', owner: 3, status: 0 },
  { id: 'M09', activity: 5, sprint: 1, title: '记录 Sprint 评审与复盘', description: '作为项目负责人，我希望记录第一次 Sprint 评审结论与未完成项，以便复盘', priority: 'Should', acceptance: 'Sprint1 末评审产出可见（M2 里程碑）', owner: 0, status: 0 },
  { id: 'M10', activity: 1, sprint: 2, title: '停用成员并回收权限', description: '作为管理员，我希望停用/移除成员并回收其权限，以便控制人员变更', priority: 'Must', acceptance: '停用后无法登录/无项目数据入口；关联 US01 扩展', owner: 1, status: 0 },
  { id: 'M11', activity: 2, sprint: 2, title: '细化故事与记录变更', description: '作为项目负责人，我希望细化故事并记录变更说明，以便需求可控', priority: 'Should', acceptance: '变更前后与理由可查', owner: 2, status: 0 },
  { id: 'M12', activity: 3, sprint: 2, title: '任务评论与 @ 提及', description: '作为成员，我希望在任务下评论/@提及，以便协同沟通', priority: 'Could', acceptance: '有则必存有记录；不影响任何 Must 验收', owner: 3, status: 0 },
  { id: 'M13', activity: 3, sprint: 2, title: '按负责人和状态筛选任务', description: '作为项目负责人，我希望按负责人/状态筛选任务，以便快速定位', priority: 'Could', acceptance: '筛选结果与任务清单一致', owner: 0, status: 0 },
  { id: 'M14', activity: 4, sprint: 2, title: '组合筛选项目统计', description: '作为项目负责人，我希望按人/状态/史诗组合筛选统计，以便识别变化', priority: 'Should', acceptance: '组合筛选口径一致；关联 US15', owner: 1, status: 0 },
  { id: 'M15', activity: 4, sprint: 2, title: '查看完成趋势与燃尽图', description: '作为项目负责人，我希望查看完成趋势/燃尽图，以便提前发现偏差', priority: 'Should', acceptance: '使用真实历史或标注样例；不编数据；关联 US07/US15', owner: 2, status: 0 },
  { id: 'M16', activity: 5, sprint: 2, title: '归档复盘与变更历史', description: '作为项目负责人，我希望把复盘结论与变更历史入库，以便改进可追溯', priority: 'Should', acceptance: '复盘记录可按 Sprint 回溯', owner: 3, status: 0 },
  { id: 'M17', activity: 2, sprint: 3, title: '登记需求变更与影响', description: '作为项目负责人，我希望登记需求变更并标记影响，以便控制范围蔓延', priority: 'Should', acceptance: '变更记录、影响说明、版本保留', owner: 0, status: 0 },
  { id: 'M18', activity: 3, sprint: 3, title: '设置并关联里程碑', description: '作为项目负责人，我希望设置里程碑并关联任务/故事，以便掌握交付节奏', priority: 'Must', acceptance: '里程碑与任务关联可查、可展示', owner: 1, status: 0 },
  { id: 'M19', activity: 4, sprint: 3, title: '查看里程碑进度总览', description: '作为团队，我希望查看里程碑进度与项目总览视图，以便向干系人汇报', priority: 'Should', acceptance: '视图数字与底层数据一致', owner: 2, status: 0 },
  { id: 'M20', activity: 5, sprint: 3, title: '将改进项转为任务', description: '作为项目负责人，我希望把复盘中的改进项转成任务并复查，以便持续改进', priority: 'Could', acceptance: '改进项可转任务、可追踪状态', owner: 3, status: 0 },
  { id: 'M21', activity: 2, sprint: 3, title: '智能生成:UML与类图联动', description: '作为技术负责人，我希望UML自动生成与类图影响传播联动，以便设计变更可视化追踪', priority: 'Should', acceptance: 'UML可生成编辑；类图关联可查；影响传播可展示；关联US13/US21', owner: 2, status: 0 },
  { id: 'M22', activity: 3, sprint: 3, title: 'AI辅助开发:拆解与排期', description: '作为项目负责人，我希望AI辅助需求拆解与进度排期，以便降低估算偏差', priority: 'Should', acceptance: 'AI拆解可估算工时；进度预测可排期；关联US14/US17/US18/US19', owner: 1, status: 0 },
  { id: 'M23', activity: 4, sprint: 3, title: 'AI质量保障:分析与风险闭环', description: '作为质量负责人，我希望AI分析质量与风险并闭环改进，以便持续交付', priority: 'Should', acceptance: '质量分析可定位缺口；风险预警可应对；闭环改进可追踪；关联US22/US23/US24', owner: 3, status: 0 }
]

/* 看板与甘特「强制血缘对应」:开发任务(card)必须挂看板卡;管理任务(type=management)不挂卡
   status:0待办 1进行中 2完成 3已取消;eh=预估工时(加权进度分母) */
export const TASKS = [
  { id: 'T01', name: '启动规划与基线', owner: 0, h: 12, w: [1, 1], story: '范围基线', card: null, type: 'management', status: 2 },
  { id: 'T02', name: '共享数据与接口小样', owner: 2, h: 12, w: [1, 1], story: '技术约定', card: null, type: 'management', status: 2 },
  { id: 'T03', name: '项目/成员/自定义权限', owner: 0, h: 16, w: [1, 2], story: 'US01,02', card: 'M02', type: 'feature', status: 2 },
  { id: 'T04', name: '故事/地图/基础看板', owner: 1, h: 16, w: [1, 2], story: 'US03-06', card: 'M04', type: 'feature', status: 1 },
  { id: 'T05', name: '基础报表与 S1 验证', owner: 3, h: 12, w: [2, 2], story: 'US07', card: 'M08', type: 'feature', status: 1 },
  { id: 'T06', name: '看板增强与趋势', owner: 1, h: 12, w: [3, 3], story: 'US09,15', card: 'M15', type: 'feature', status: 0 },
  { id: 'T07', name: '甘特图与成员任务图', owner: 2, h: 12, w: [3, 4], story: 'US10,11', card: 'M11', type: 'feature', status: 0 },
  { id: 'T08', name: 'UML 自动生成与编辑', owner: 3, h: 20, w: [2, 4], story: 'US13', card: 'M21', type: 'feature', status: 1 },
  { id: 'T09', name: '权限扩展与联动', owner: 0, h: 8, w: [4, 4], story: 'US08,12', card: 'M03', type: 'feature', status: 0 },
  { id: 'T10', name: 'AI 拆解与估算', owner: 1, h: 12, w: [2, 4], story: 'US14,17', card: 'M22', type: 'feature', status: 0 },
  { id: 'T11', name: 'AI 进度预测与排期', owner: 2, h: 12, w: [4, 5], story: 'US18,19', card: 'M22', type: 'feature', status: 0 },
  { id: 'T12', name: 'AI 质量分析', owner: 3, h: 12, w: [4, 5], story: 'US22', card: 'M23', type: 'feature', status: 0 },
  { id: 'T13', name: 'AI 风险/效率/闭环', owner: 0, h: 12, w: [5, 6], story: 'US16,20,23,24', card: 'M23', type: 'feature', status: 0 },
  { id: 'T14', name: '类图关联与影响传播', owner: 3, h: 12, w: [4, 5], story: 'US21', card: 'M21', type: 'feature', status: 0 },
  { id: 'T15', name: '完整回归与部署演示', owner: 2, h: 12, w: [6, 6], story: '全量回归', card: null, type: 'management', status: 0 },
  { id: 'T16', name: 'Scrum 管理与证据', owner: 0, h: 8, w: [1, 6], story: '持续管理', card: null, type: 'management', status: 1 }
]

export const MEMBERS = [
  { id: 0, name: '成员 1', role: 'DRI · 产品与范围', tag: '权限 / 公共服务 / 风险闭环', accent: 'o0' },
  { id: 1, name: '成员 2', role: '产品故事 · AI 协调', tag: '故事看板 / 趋势 / AI 拆解', accent: 'o1' },
  { id: 2, name: '成员 3', role: '技术与架构', tag: '数据接口 / 甘特 / AI 排期', accent: 'o2' },
  { id: 3, name: '成员 4', role: '质量与风险', tag: 'UML / 质量分析 / 验收', accent: 'o3' }
]

export const MILESTONES = [
  { id: 'M1', week: 1, name: '需求与规划基线', desc: '六项实验材料完整；≥3 切片；≥2 AI 角色记录' },
  { id: 'M2', week: 2, name: 'Sprint 1 评审', desc: 'US01–07 通过：可创建项目/配角色/组织需求' },
  { id: 'M3', week: 4, name: '中期评审', desc: '四视图联动，用例/时序自动生成可演示' },
  { id: 'M4', week: 6, name: '成果展演', desc: 'US01–US24 需求全量回归，六项 AI + 跨视图同步演示' }
]

export const SPRINTS = [
  { name: 'Sprint 1', tag: '行走骨架', cls: 's1' },
  { name: 'Sprint 2', tag: '深化协作', cls: 's2' },
  { name: 'Sprint 3', tag: '交付收口', cls: 's3' }
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
  { kw: '负载', reply: '【负载评估】本周 P4 成员 4 负载最高（UML 20h + 质量分析），建议把 T08 的编辑交互后置到 W4，或由成员 3 支援，避免单点过载。' },
  { kw: '风险', reply: '【风险预警】当前三大风险：① UML 自动生成不确定性高 ② AI 估算可能乐观 ③ 视图口径易不一致。建议 W2 前各留一个验证小样，不要拖到 W6。' },
  { kw: '排期', reply: '【排期建议】Sprint 2 建议：T06 看板趋势 → T07 甘特与成员图 → T10 AI 拆解（W2–W4 并行）。注意 T07 依赖 T04 数据接口，需先联调。' },
  { kw: '故事', reply: '【故事补全】建议按「作为…，我希望…，以便…」补全价值表述，并确认骨干活动与 MoSCoW 优先级，保持与看板/地图数据一致。' }
]

export const POOL_SEED = [
  { id: 'R01', title: '支持第三方账号登录（微信 / 企业微信）', desc: '会议中提出，尚未确认是否纳入 6 周范围。', source: '会议 2026-09-06 · 成员2', created: '09-06', priority: 'Could' },
  { id: 'R02', title: '报表导出为 PDF', desc: '老师反馈建议，待确认是否替代 CSV 导出。', source: '课堂反馈 · 成员1', created: '09-06', priority: 'Should' },
  { id: 'R03', title: '多语言界面（i18n）', desc: '候选 Won\'t，蓝图外扩展，暂缓。', source: '范围讨论 · 成员3', created: '09-06', priority: 'Could' },
  { id: 'R04', title: '移动端原生客户端', desc: '明确 Won\'t，本学期不做。', source: '范围讨论 · 成员3', created: '09-06', priority: 'Could' }
]

export const SUGGESTION_SEED = [
  { id: 'SG01', agent: '会议智能体', kind: 'meeting', time: '09-06 14:22', evidence: '会议决议 #3：确认 Sprint 2 聚焦会议闭环', affected: 'US14（AI PRD 拆解）', change: [{ t: '优先级 Must', d: true }, { t: '优先级 Should', d: false }], note: '拆解能力保留，但不高于两个核心智能体。', status: 'pending' },
  { id: 'SG02', agent: '会议智能体', kind: 'meeting', time: '09-06 14:25', evidence: '转写片段：\'成员任务图暂时先做负载热力图\'', affected: '新需求 → 需求池', change: [{ t: '新增需求：成员任务图绿格子后置', d: false }], note: '新需求默认进入需求池，不提前承诺。', status: 'pending' },
  { id: 'SG03', agent: '任务提交智能体', kind: 'submit', time: '09-06 18:05', evidence: '成员3 近 3 天无 commit，且 T07 甘特与成员图未关闭', affected: 'T07 · 成员3', change: [{ t: '状态：进行中', d: true }, { t: '状态：需检查阻塞', d: false }], note: '建议确认是否被接口联调阻塞，或重新分配。', status: 'pending' },
  { id: 'SG04', agent: '任务提交智能体', kind: 'submit', time: '09-06 18:08', evidence: '成员4 昨日 6 次提交集中在 T08 UML，变更规模大', affected: 'T08 · 成员4', change: [{ t: '负载：正常', d: true }, { t: '负载：偏忙', d: false }], note: '建议把 T08 编辑交互后置 W4，或由成员3 支援。', status: 'approved' }
]

export const BANDWIDTH = [[10, 10, 10, 10, 10, 10], [8, 8, 8, 8, 8, 8], [10, 10, 10, 10, 10, 10], [9, 9, 9, 9, 9, 9]]

export const ACCOUNTS = ['成员1', '成员2', '成员3', '成员4', '成员5']
