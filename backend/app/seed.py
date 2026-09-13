from . import models, security


def seed_users(db):
    rows = [("李锐铭", "李锐铭", "admin", "green", 60), ("高思晗", "高思晗", "owner", "orange", 48),
            ("孙秋实", "孙秋实", "member", "blue", 60), ("罗子涵", "罗子涵", "member", "pink", 54),
            ("成员5", "只读查看者", "viewer", "gray", 60)]
    existing = db.query(models.User).order_by(models.User.id).all()
    if existing:
        changed = False
        for user, (_, display, role, color, capacity) in zip(existing, rows):
            if user.display_name != display or user.role != role or user.color != color or user.capacity_hours != capacity:
                user.display_name, user.role, user.color, user.capacity_hours = display, role, color, capacity
                changed = True
        seeded_usernames = {user.username for user in existing}
        missing_rows = rows[len(existing):]
        if not any(user.role == "viewer" for user in existing):
            viewer_row = rows[-1]
            if viewer_row[0] not in seeded_usernames and viewer_row not in missing_rows:
                missing_rows = [*missing_rows, viewer_row]
        for username, display, role, color, capacity in missing_rows:
            db.add(models.User(username=username, display_name=display, role=role, color=color,
                               capacity_hours=capacity,
                               password_hash=security.hash_password("123456")))
            changed = True
        if changed:
            db.commit()
        return
    for username, display, role, color, capacity in rows:
        db.add(models.User(username=username, display_name=display, role=role, color=color,
                           capacity_hours=capacity,
                           password_hash=security.hash_password("123456")))
    db.commit()


# (id, sprint, activity, owner_idx, priority, status, title, description, acceptance)
STORIES = [
("US01",1,1,0,"Must",2,"项目与成员范围","作为管理员，我希望创建项目并管理成员，以便限定协作范围","成员可进入所属项目；非成员访问被拒绝"),
("US02",1,1,1,"Should",2,"角色与权限边界","作为管理员，我希望配置角色和权限，以便控制操作边界","只读角色不能修改项目数据"),
("US03",1,2,1,"Must",1,"史诗、故事与验收条件","作为负责人，我希望维护史诗、故事和验收条件，以便形成需求基线","故事包含角色、目标、价值、优先级和稳定 ID"),
("US04",1,2,1,"Must",0,"用户故事地图","作为成员，我希望按活动和 Sprint 查看故事地图，以便理解版本目标","地图至少包含三个发布切片并与故事数据一致"),
("US05",1,3,2,"Must",0,"任务拆分与指派","作为负责人，我希望拆分任务、分配负责人并设定日期，以便成员开展工作","任务关联故事且负责人和日期可校验"),
("US06",1,4,2,"Must",1,"任务状态协同","作为成员，我希望更新任务状态，以便团队知道进展","状态、负责人、优先级可见并记录变更"),
("US07",1,5,3,"Should",0,"基础报表","作为查看者，我希望查看项目进度，以便掌握当前状态","报表与任务清单一致"),
("US08",2,1,0,"Should",0,"四视图权限一致","作为管理员，我希望不同视图遵守统一权限，以便协作可控","看板、地图、甘特和 UML 按角色校验"),
("US09",4,3,2,"Could",0,"拖拽与 WIP 控制","作为成员，我希望拖拽卡片并限制 WIP，以便控制流程","后续路线，不纳入本学期六周承诺"),
("US10",1,3,2,"Must",0,"甘特图","作为负责人，我希望编辑任务排期和里程碑，以便掌握交付节奏","任务依赖、关键路径和里程碑清晰"),
("US11",1,3,2,"Must","0","成员任务图","作为负责人，我希望查看成员任务和负载，以便合理分配工作","成员、任务、周负载和容量数据一致"),
("US12",2,4,1,"Must","0","四视图数据联动","作为成员，我希望状态和指派变更同步到各视图，以便避免重复维护","同一数据源驱动四视图"),
("US13",2,4,3,"Should","0","UML 自动生成与编辑","作为设计成员，我希望生成并编辑 UML 图，以便支持设计协作","图来源可追溯并支持修改预览"),
("US14",3,2,2,"Should","0","AI PRD 拆解","作为负责人，我希望 AI 拆解需求并估算工作量，以便辅助需求细化","输出事实、依据和不确定性"),
("US15",4,5,1,"Could","0","筛选与趋势分析","作为负责人，我希望筛选并对比趋势，以便识别变化","后续路线，不纳入本学期六周承诺"),
("US16",3,1,0,"Should","0","AI 数据访问权限","作为管理员，我希望控制 AI 可访问的数据范围，以便保护项目数据","越权 AI 读取和写入都被阻止"),
("US17",2,2,2,"Must","0","AI 建议修订与来源追溯","作为负责人，我希望修订 AI 建议并追溯来源，以便保持可审计","保留前后差异、证据和审核记录"),
("US18",3,3,2,"Must","0","智能排期","作为负责人，我希望 AI 综合依赖、优先级和容量给出排期，以便获得可执行安排","说明依据并经人工确认"),
("US19",3,4,1,"Should","0","进度预测","作为负责人，我希望根据历史和当前范围预测交付，以便提前调整","说明历史范围和预测假设"),
("US20",3,4,0,"Must","0","风险预警","作为负责人，我希望收到延期、阻塞和过载风险，以便及时采取行动","风险包含证据、影响和建议"),
("US21",4,5,3,"Could","0","图与任务影响传播","作为设计成员，我希望查看图变更对任务的影响，以便保持一致","后续路线，不纳入本学期六周承诺"),
("US22",3,5,3,"Should","0","代码文档测试质量分析","作为团队成员，我希望分析代码、文档和测试质量，以便发现缺口","结论引用真实证据，不虚构测试"),
("US23",3,5,0,"Should","0","效率优化","作为负责人，我希望识别瓶颈并转成改进任务，以便优化流程","改进建议可追踪结果"),
("US24",3,5,0,"Must","0","AI 闭环","作为负责人，我希望建议经审核后执行并可回滚，以便形成可追溯闭环","记录建议、审核、执行和回滚"),
("US25",1,5,0,"Must","0","实时项目进度","作为负责人，我希望查看实时或准实时进度，以便判断 Sprint 是否偏离目标","刷新后与底层数据一致"),
("US26",1,2,1,"Must","0","需求池","作为负责人，我希望保存未确认需求，以便不丢失来源并控制承诺","需求池记录来源、优先级和状态"),
("US27",1,3,2,"Must","0","成员 Bandwidth","作为负责人，我希望维护成员容量和已分配工时，以便避免超载","容量口径统一且可计算"),
("US28",2,4,1,"Should","0","成员开发活动图","作为负责人，我希望查看成员活动分布，以便了解阶段性工作活跃度","明确标记活动统计口径"),
("US29",2,4,1,"Must","0","会议录音与转写","作为主持人，我希望在知情同意后录音并生成可编辑转写，以便提取决策","保留时间和说话人信息"),
("US30",2,4,1,"Must","0","会议总结与行动项","作为 DRI，我希望会议智能体生成摘要、决议和行动项，以便形成记录","不明确责任人或日期时标记待确认"),
("US31",2,2,1,"Must","0","会议到需求修改建议","作为负责人，我希望会议决议生成故事修改建议，以便更新需求基线","建议包含证据和前后差异"),
("US32",2,3,0,"Must","0","会议到任务调整建议","作为负责人，我希望会议识别过载和依赖冲突并提出调整建议，以便降低协调成本","建议必须人工批准"),
("US33",2,4,0,"Must","0","AI 建议审核中心","作为负责人，我希望采纳、修改或拒绝 AI 建议，以便保持人在回路","记录审核人、结果、理由和影响范围"),
("US34",3,4,0,"Must","0","GitHub 仓库与成员映射","作为管理员，我希望绑定仓库并映射成员，以便分析真实开发活动","同步 commit、push、PR、review、issue"),
("US35",3,4,3,"Must","0","任务提交智能体工作状态分析","作为负责人，我希望分析提交、任务关联和协作行为，以便发现阻塞和无进展","输出事实、时间范围、证据和不确定性"),
("US36",3,4,3,"Must","0","成员工作状态汇总","作为负责人，我希望查看成员完成内容、负载和工作摘要，以便进行有依据的协调","区分事实与推断并支持下钻"),
("US37",3,5,0,"Should","0","项目层面协调建议","作为负责人，我希望在项目层面获得可解释的协调建议，以便处理异常模式","建议说明范围、依据和影响"),
]

# (id, name, owner_idx, hours, week_start, week_end, story_ref, depends_on)
TASKS = [
("T01","启动会、基线、六项材料初稿",0,12,1,1,"US01,US03,US04", "",0,0,0),
("T02","共享数据、接口、AI 接入约定；UML 及排期小样",2,12,1,1,"US10,US13,US18", "T01",0,0,0),
("T03","项目、成员、自定义权限 / US01、US02",0,16,1,2,"US01,US02", "T02",1,50,0),
("T04","故事、地图、任务、基础看板 / US03-US06",1,16,1,2,"US03,US04,US05,US06", "T02,T03",1,50,0),
("T05","基础报表、历史记录、Sprint 1 验证 / US07",3,12,2,2,"US07", "T03,T04",0,0,0),
("T06","实时进度、需求池、成员 Bandwidth / US25-US27",1,12,2,2,"US25,US26,US27", "T03,T04",0,0,0),
("T07","甘特图与成员任务图 / US10、US11",2,12,2,2,"US10,US11,US27", "T02,T04,T06",0,0,0),
("T08","UML 自动生成与交互编辑、AI 项目规划画布 MVP / US13",3,20,3,4,"US13,US10,US11,US04", "T04,T06,T07",0,0,0),
("T09","权限扩展、四视图联动、成员贡献活动图 / US08、US12、US28",0,8,3,4,"US08,US12,US28", "T06,T07,T08",0,0,0),
("T10","会议智能体 MVP：录音、知情同意、可编辑转写、会议总结",1,16,3,4,"US29,US30", "T04,T06,T09",0,0,0),
("T11","会议分析、需求/人员/任务调整建议与 AI 建议审核中心",0,16,4,4,"US31,US32,US33", "T09,T10",0,0,0),
("T12","AI 需求拆解、故事修订与来源追溯、进度预测、智能排期",2,12,4,5,"US14,US17,US18,US19", "T05,T07",0,0,0),
("T13","AI 代码/文档/测试质量分析、风险预警、效率优化、AI 闭环",0,12,5,6,"US16,US20,US22,US23,US24", "T09,T10,T11,T12",0,0,0),
("T14","GitHub 绑定、任务提交智能体、成员状态汇总、项目协调建议",3,12,5,6,"US34,US35,US36,US37", "T03,T04,T09,T13",0,0,0),
("T15","完整回归、联调修正、部署与演示准备",2,12,6,6,"US08,US12,US13,US24", "T11,T12,T13,T14",0,0,0),
("T16","Scrum 评审 / 复盘 / 轮值交接 / 记录归档",0,8,1,6,"US07,US24,US37", "",0,0,0),
]

TASK_CARD_LINKS = {
    "T03": "US01", "T04": "US03", "T05": "US07", "T06": "US25",
    "T07": "US10", "T08": "US13", "T09": "US08", "T10": "US29",
    "T11": "US31", "T12": "US14", "T13": "US16", "T14": "US34",
}
MANAGEMENT_TASKS = {"T01", "T02", "T15", "T16"}

LEGACY_TASKS = {
    "T01": ("启动规划与基线", 0, 12, 1, 1, "范围基线"),
    "T02": ("共享数据与接口小样", 2, 12, 1, 1, "技术约定"),
    "T03": ("项目/成员/自定义权限", 0, 16, 1, 2, "US01,02"),
    "T04": ("故事/地图/基础看板", 1, 16, 1, 2, "US03-06"),
    "T05": ("基础报表与 S1 验证", 3, 12, 2, 2, "US07"),
    "T06": ("看板增强与趋势", 1, 12, 3, 3, "US09,15"),
    "T07": ("甘特图与成员任务图", 2, 12, 3, 4, "US10,11"),
    "T08": ("UML 自动生成与编辑", 3, 20, 2, 4, "US13"),
    "T09": ("权限扩展与联动", 0, 8, 4, 4, "US08,12"),
    "T10": ("AI 拆解与估算", 1, 12, 2, 4, "US14,17"),
    "T11": ("AI 进度预测与排期", 2, 12, 4, 5, "US18,19"),
    "T12": ("AI 质量分析", 3, 12, 4, 5, "US22"),
    "T13": ("AI 风险/效率/闭环", 0, 12, 5, 6, "US16,20,23,24"),
    "T14": ("类图关联与影响传播", 3, 12, 4, 5, "US21"),
    "T15": ("完整回归与部署演示", 2, 12, 6, 6, "全量回归"),
    "T16": ("Scrum 管理与证据", 0, 8, 1, 6, "持续管理"),
}


def _apply_task_values(task, row):
    _, name, owner_idx, hours, week_start, week_end, story_ref, depends_on, status, progress, blocked = row
    task.name = name
    task.owner_id = owner_idx + 1
    task.hours = hours
    task.week_start = week_start
    task.week_end = week_end
    task.story_ref = story_ref
    task.kanban_card_id = TASK_CARD_LINKS.get(task.id)
    task.estimated_hours = hours
    task.task_type = "management" if task.id in MANAGEMENT_TASKS else "feature"
    task.depends_on = depends_on
    task.status = status
    task.progress = progress
    task.blocked = int(bool(blocked))


def _is_legacy_story_seed(db):
    stories = db.query(models.Story).all()
    if len(stories) < 20 or len(stories) > 23:
        return False
    try:
        return all(story.id.startswith("M") and 1 <= int(story.id[1:]) <= 23 for story in stories)
    except ValueError:
        return False


def _migrate_legacy_story_seed(db):
    """Replace only the original M01-M20 demo baseline with the current US baseline."""
    if not _is_legacy_story_seed(db):
        return False
    legacy_ids = {story.id for story in db.query(models.Story).all()}
    for task in db.query(models.Task).all():
        if task.kanban_card_id in legacy_ids:
            task.kanban_card_id = None
        if task.story_ref:
            refs = [part.strip() for part in task.story_ref.split(",") if part.strip()]
            if any(ref in legacy_ids for ref in refs):
                task.story_ref = ""
    db.query(models.Story).filter(models.Story.id.in_(legacy_ids)).delete(synchronize_session=False)
    for (sid, sprint, activity, owner_idx, priority, status, title, desc, acc) in STORIES:
        db.add(models.Story(id=sid, title=title, description=desc, acceptance=acc,
                            priority=priority, sprint=sprint, activity=activity,
                            status=int(status), owner_id=owner_idx + 1))
    db.commit()
    return True


def seed_demo(db):
    seed_users(db)
    migrated = _migrate_legacy_story_seed(db)
    if db.query(models.Story).count() == 0:
        for (sid, sprint, activity, owner_idx, priority, status, title, desc, acc) in STORIES:
            db.add(models.Story(id=sid, title=title, description=desc, acceptance=acc,
                                priority=priority, sprint=sprint, activity=activity,
                                status=int(status), owner_id=owner_idx + 1))
        db.commit()
    existing_tasks = {task.id: task for task in db.query(models.Task).all()}
    if not existing_tasks:
        for row in TASKS:
            task = models.Task(id=row[0])
            _apply_task_values(task, row)
            db.add(task)
        db.commit()
    else:
        upgraded = False
        for row in TASKS:
            task = existing_tasks.get(row[0])
            legacy = LEGACY_TASKS.get(row[0])
            if task is None:
                task = models.Task(id=row[0])
                _apply_task_values(task, row)
                db.add(task)
                upgraded = True
                continue
            if legacy is None:
                continue
            current = (task.name, task.owner_id - 1, task.hours, task.week_start, task.week_end, task.story_ref)
            if migrated or current == legacy:
                _apply_task_values(task, row)
                upgraded = True
        if upgraded:
            db.commit()


def seed_all(db):
    seed_demo(db)
