from . import models, security


def seed_users(db):
    if db.query(models.User).count():
        return
    rows = [("成员1", "成员1", "admin", "green"), ("成员2", "成员2", "owner", "orange"),
            ("成员3", "成员3", "member", "blue"), ("成员4", "成员4", "member", "pink"),
            ("成员5", "成员5", "viewer", "gray")]
    for username, display, role, color in rows:
        db.add(models.User(username=username, display_name=display, role=role, color=color,
                           password_hash=security.hash_password("123456")))
    db.commit()


# (id, sprint, activity, owner_idx, priority, status, title, description, acceptance)
STORIES = [
("M01",1,1,0,"Must",2,"登录与退出系统","作为用户，我希望登录/退出系统，以便安全使用平台","未登录不可访问项目页；会话过期处理"),
("M02",1,1,1,"Must",2,"创建项目并添加成员","作为管理员，我希望创建项目并添加成员，以便限定协作范围","成员可进入所属项目，非成员页面与接口拒绝；关联 US01"),
("M03",1,1,2,"Must",1,"创建角色与分配权限","作为管理员，我希望创建角色并分配权限，以便控制操作边界","新建只读角色确实改不了东西；关联 US02"),
("M04",1,2,3,"Must",1,"维护史诗、故事与验收条件","作为项目负责人，我希望维护史诗、故事和验收条件，以便形成需求基线","故事含角色/目标/价值/优先级/稳定ID；关联 US03"),
("M05",1,2,0,"Must",0,"按活动与迭代浏览故事地图","作为团队成员，我希望按活动和 Sprint 查看故事地图，以便理解版本目标","至少 3 个发布切片、与需求条目数据一致；关联 US04"),
("M06",1,3,1,"Must",0,"拆分任务并指派负责人","作为项目负责人，我希望把故事拆成任务并指派负责人、设定日期，以便成员开展工作","任务关联故事，负责人/日期合法可再读；关联 US05"),
("M07",1,3,2,"Must",1,"在看板上更新任务状态","作为团队成员，我希望在看板上把任务从待办移到进行中/完成，以便团队知道进展","状态/负责人/优先级可见，刷新保留，记录变更历史；关联 US06"),
("M08",1,4,3,"Must",0,"查看项目基础报表","作为普通用户，我希望查看基础报表，以便掌握当前进度","状态数/完成比例与任务清单一致；空项目正确；关联 US07"),
("M09",1,5,0,"Should",0,"记录 Sprint 评审与复盘","作为项目负责人，我希望记录第一次 Sprint 评审结论与未完成项，以便复盘","Sprint1 末评审产出可见（M2 里程碑）"),
("M10",2,1,1,"Must",0,"停用成员并回收权限","作为管理员，我希望停用/移除成员并回收其权限，以便控制人员变更","停用后无法登录/无项目数据入口；关联 US01 扩展"),
("M11",2,2,2,"Should",0,"细化故事与记录变更","作为项目负责人，我希望细化故事并记录变更说明，以便需求可控","变更前后与理由可查"),
("M12",2,3,3,"Could",0,"任务评论与 @ 提及","作为成员，我希望在任务下评论/@提及，以便协同沟通","有则必存有记录；不影响任何 Must 验收"),
("M13",2,3,0,"Could",0,"按负责人和状态筛选任务","作为项目负责人，我希望按负责人/状态筛选任务，以便快速定位","筛选结果与任务清单一致"),
("M14",2,4,1,"Should",0,"组合筛选项目统计","作为项目负责人，我希望按人/状态/史诗组合筛选统计，以便识别变化","组合筛选口径一致；关联 US15"),
("M15",2,4,2,"Should",0,"查看完成趋势与燃尽图","作为项目负责人，我希望查看完成趋势/燃尽图，以便提前发现偏差","使用真实历史或标注样例；不编数据"),
("M16",2,5,3,"Should",0,"归档复盘与变更历史","作为项目负责人，我希望把复盘结论与变更历史入库，以便改进可追溯","复盘记录可按 Sprint 回溯"),
("M17",3,2,0,"Should",0,"登记需求变更与影响","作为项目负责人，我希望登记需求变更并标记影响，以便控制范围蔓延","变更记录、影响说明、版本保留"),
("M18",3,3,1,"Must",0,"设置并关联里程碑","作为项目负责人，我希望设置里程碑并关联任务/故事，以便掌握交付节奏","里程碑与任务关联可查、可展示"),
("M19",3,4,2,"Should",0,"查看里程碑进度总览","作为团队，我希望查看里程碑进度与项目总览视图，以便向干系人汇报","视图数字与底层数据一致"),
("M20",3,5,3,"Could",0,"将改进项转为任务","作为项目负责人，我希望把复盘中的改进项转成任务并复查，以便持续改进","改进项可转任务、可追踪状态"),
("M21",3,2,2,"Should",0,"智能生成:UML与类图联动","作为技术负责人，我希望UML自动生成与类图影响传播联动，以便设计变更可视化追踪","UML可生成编辑；类图关联可查；影响传播可展示；关联US13/US21"),
("M22",3,3,1,"Should",0,"AI辅助开发:拆解与排期","作为项目负责人，我希望AI辅助需求拆解与进度排期，以便降低估算偏差","AI拆解可估算工时；进度预测可排期；关联US14/US17/US18/US19"),
("M23",3,4,3,"Should",0,"AI质量保障:分析与风险闭环","作为质量负责人，我希望AI分析质量与风险并闭环改进，以便持续交付","质量分析可定位缺口；风险预警可应对；闭环改进可追踪；关联US22/US23/US24"),
]

# (id, name, owner_idx, hours, week_start, week_end, story_ref, kanban_card_id, task_type, status)
# 挂载映射:开发任务(feature)必须挂具体看板卡;管理任务(management)不挂卡
# status: 0待办 1进行中 2完成 3已取消
TASKS = [
("T01","启动规划与基线",0,12,1,1,"范围基线",None,"management",2),
("T02","共享数据与接口小样",2,12,1,1,"技术约定",None,"management",2),
("T03","项目/成员/自定义权限",0,16,1,2,"US01,02","M02","feature",2),
("T04","故事/地图/基础看板",1,16,1,2,"US03-06","M04","feature",1),
("T05","基础报表与 S1 验证",3,12,2,2,"US07","M08","feature",1),
("T06","看板增强与趋势",1,12,3,3,"US09,15","M15","feature",0),
("T07","甘特图与成员任务图",2,12,3,4,"US10,11","M11","feature",0),
("T08","UML 自动生成与编辑",3,20,2,4,"US13","M21","feature",1),
("T09","权限扩展与联动",0,8,4,4,"US08,12","M03","feature",0),
("T10","AI 拆解与估算",1,12,2,4,"US14,17","M22","feature",0),
("T11","AI 进度预测与排期",2,12,4,5,"US18,19","M22","feature",0),
("T12","AI 质量分析",3,12,4,5,"US22","M23","feature",0),
("T13","AI 风险/效率/闭环",0,12,5,6,"US16,20,23,24","M23","feature",0),
("T14","类图关联与影响传播",3,12,4,5,"US21","M21","feature",0),
("T15","完整回归与部署演示",2,12,6,6,"全量回归",None,"management",0),
("T16","Scrum 管理与证据",0,8,1,6,"持续管理",None,"management",1),
]


def seed_demo(db):
    seed_users(db)
    if db.query(models.Story).count() == 0:
        for (sid, sprint, activity, owner_idx, priority, status, title, desc, acc) in STORIES:
            db.add(models.Story(id=sid, title=title, description=desc, acceptance=acc,
                                priority=priority, sprint=sprint, activity=activity,
                                status=status, owner_id=owner_idx + 1))
        db.commit()
    if db.query(models.Task).count() == 0:
        for (tid, name, owner_idx, hours, ws, we, ref, card, ttype, status) in TASKS:
            db.add(models.Task(id=tid, name=name, owner_id=owner_idx + 1, hours=hours,
                               week_start=ws, week_end=we, story_ref=ref,
                               kanban_card_id=card, task_type=ttype,
                               estimated_hours=hours, status=status))
        db.commit()


def seed_all(db):
    seed_demo(db)
