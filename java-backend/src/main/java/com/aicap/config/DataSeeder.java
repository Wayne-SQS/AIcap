package com.aicap.config;

import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动播种(对齐 FastAPI app/seed.py 的 seed_all):
 * - 用户:按 id 顺序比对现有 5 行并补齐展示名/角色/颜色/容量;缺行则补插
 * - 故事:基线为 US01–US37;若检测到旧的 M01–M23 演示基线,整批替换(仅此一种情况)
 * - 任务:16 条;已存在的旧签名行升级为当前值(名称/负责人/工时/排期/关联/类型/依赖/状态/进度/阻塞)
 * 全新空库的建表由 resources/db/schema.sql 完成,存量库补列由 {@link SchemaUpgrader} 完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserMapper userMapper;
    private final StoryMapper storyMapper;
    private final TaskMapper taskMapper;
    private final PasswordUtil passwordUtil;

    @Value("${aicap.seed-on-start:true}")
    private boolean seedOnStart;

    private record UserRow(String username, String displayName, String role, String color, int capacityHours) {
    }

    /** 真名账号;存量库沿用"成员N"用户名,登录时由 AuthController 做双向别名映射 */
    private static final List<UserRow> USERS = List.of(
            new UserRow("李锐铭", "李锐铭", "admin", "green", 60),
            new UserRow("高思晗", "高思晗", "owner", "orange", 48),
            new UserRow("孙秋实", "孙秋实", "member", "blue", 60),
            new UserRow("罗子涵", "罗子涵", "member", "pink", 54),
            new UserRow("成员5", "只读查看者", "viewer", "gray", 60));

    /** (id, sprint, activity, ownerIdx, priority, status, title, description, acceptance) */
    private record StoryRow(String id, int sprint, int activity, int ownerIdx, String priority,
                            int status, String title, String description, String acceptance) {
    }

    private static final List<StoryRow> STORIES = List.of(
            new StoryRow("US01", 1, 1, 0, "Must", 2, "项目与成员范围", "作为管理员，我希望创建项目并管理成员，以便限定协作范围", "成员可进入所属项目；非成员访问被拒绝"),
            new StoryRow("US02", 1, 1, 1, "Should", 2, "角色与权限边界", "作为管理员，我希望配置角色和权限，以便控制操作边界", "只读角色不能修改项目数据"),
            new StoryRow("US03", 1, 2, 1, "Must", 1, "史诗、故事与验收条件", "作为负责人，我希望维护史诗、故事和验收条件，以便形成需求基线", "故事包含角色、目标、价值、优先级和稳定 ID"),
            new StoryRow("US04", 1, 2, 1, "Must", 0, "用户故事地图", "作为成员，我希望按活动和 Sprint 查看故事地图，以便理解版本目标", "地图至少包含三个发布切片并与故事数据一致"),
            new StoryRow("US05", 1, 3, 2, "Must", 0, "任务拆分与指派", "作为负责人，我希望拆分任务、分配负责人并设定日期，以便成员开展工作", "任务关联故事且负责人和日期可校验"),
            new StoryRow("US06", 1, 4, 2, "Must", 1, "任务状态协同", "作为成员，我希望更新任务状态，以便团队知道进展", "状态、负责人、优先级可见并记录变更"),
            new StoryRow("US07", 1, 5, 3, "Should", 0, "基础报表", "作为查看者，我希望查看项目进度，以便掌握当前状态", "报表与任务清单一致"),
            new StoryRow("US08", 2, 1, 0, "Should", 0, "四视图权限一致", "作为管理员，我希望不同视图遵守统一权限，以便协作可控", "看板、地图、甘特和 UML 按角色校验"),
            new StoryRow("US09", 4, 3, 2, "Could", 0, "拖拽与 WIP 控制", "作为成员，我希望拖拽卡片并限制 WIP，以便控制流程", "后续路线，不纳入本学期六周承诺"),
            new StoryRow("US10", 1, 3, 2, "Must", 0, "甘特图", "作为负责人，我希望编辑任务排期和里程碑，以便掌握交付节奏", "任务依赖、关键路径和里程碑清晰"),
            new StoryRow("US11", 1, 3, 2, "Must", 0, "成员任务图", "作为负责人，我希望查看成员任务和负载，以便合理分配工作", "成员、任务、周负载和容量数据一致"),
            new StoryRow("US12", 2, 4, 1, "Must", 0, "四视图数据联动", "作为成员，我希望状态和指派变更同步到各视图，以便避免重复维护", "同一数据源驱动四视图"),
            new StoryRow("US13", 2, 4, 3, "Should", 0, "UML 自动生成与编辑", "作为设计成员，我希望生成并编辑 UML 图，以便支持设计协作", "图来源可追溯并支持修改预览"),
            new StoryRow("US14", 3, 2, 2, "Should", 0, "AI PRD 拆解", "作为负责人，我希望 AI 拆解需求并估算工作量，以便辅助需求细化", "输出事实、依据和不确定性"),
            new StoryRow("US15", 4, 5, 1, "Could", 0, "筛选与趋势分析", "作为负责人，我希望筛选并对比趋势，以便识别变化", "后续路线，不纳入本学期六周承诺"),
            new StoryRow("US16", 3, 1, 0, "Should", 0, "AI 数据访问权限", "作为管理员，我希望控制 AI 可访问的数据范围，以便保护项目数据", "越权 AI 读取和写入都被阻止"),
            new StoryRow("US17", 2, 2, 2, "Must", 0, "AI 建议修订与来源追溯", "作为负责人，我希望修订 AI 建议并追溯来源，以便保持可审计", "保留前后差异、证据和审核记录"),
            new StoryRow("US18", 3, 3, 2, "Must", 0, "智能排期", "作为负责人，我希望 AI 综合依赖、优先级和容量给出排期，以便获得可执行安排", "说明依据并经人工确认"),
            new StoryRow("US19", 3, 4, 1, "Should", 0, "进度预测", "作为负责人，我希望根据历史和当前范围预测交付，以便提前调整", "说明历史范围和预测假设"),
            new StoryRow("US20", 3, 4, 0, "Must", 0, "风险预警", "作为负责人，我希望收到延期、阻塞和过载风险，以便及时采取行动", "风险包含证据、影响和建议"),
            new StoryRow("US21", 4, 5, 3, "Could", 0, "图与任务影响传播", "作为设计成员，我希望查看图变更对任务的影响，以便保持一致", "后续路线，不纳入本学期六周承诺"),
            new StoryRow("US22", 3, 5, 3, "Should", 0, "代码文档测试质量分析", "作为团队成员，我希望分析代码、文档和测试质量，以便发现缺口", "结论引用真实证据，不虚构测试"),
            new StoryRow("US23", 3, 5, 0, "Should", 0, "效率优化", "作为负责人，我希望识别瓶颈并转成改进任务，以便优化流程", "改进建议可追踪结果"),
            new StoryRow("US24", 3, 5, 0, "Must", 0, "AI 闭环", "作为负责人，我希望建议经审核后执行并可回滚，以便形成可追溯闭环", "记录建议、审核、执行和回滚"),
            new StoryRow("US25", 1, 5, 0, "Must", 0, "实时项目进度", "作为负责人，我希望查看实时或准实时进度，以便判断 Sprint 是否偏离目标", "刷新后与底层数据一致"),
            new StoryRow("US26", 1, 2, 1, "Must", 0, "需求池", "作为负责人，我希望保存未确认需求，以便不丢失来源并控制承诺", "需求池记录来源、优先级和状态"),
            new StoryRow("US27", 1, 3, 2, "Must", 0, "成员 Bandwidth", "作为负责人，我希望维护成员容量和已分配工时，以便避免超载", "容量口径统一且可计算"),
            new StoryRow("US28", 2, 4, 1, "Should", 0, "成员开发活动图", "作为负责人，我希望查看成员活动分布，以便了解阶段性工作活跃度", "明确标记活动统计口径"),
            new StoryRow("US29", 2, 4, 1, "Must", 0, "会议录音与转写", "作为主持人，我希望在知情同意后录音并生成可编辑转写，以便提取决策", "保留时间和说话人信息"),
            new StoryRow("US30", 2, 4, 1, "Must", 0, "会议总结与行动项", "作为 DRI，我希望会议智能体生成摘要、决议和行动项，以便形成记录", "不明确责任人或日期时标记待确认"),
            new StoryRow("US31", 2, 2, 1, "Must", 0, "会议到需求修改建议", "作为负责人，我希望会议决议生成故事修改建议，以便更新需求基线", "建议包含证据和前后差异"),
            new StoryRow("US32", 2, 3, 0, "Must", 0, "会议到任务调整建议", "作为负责人，我希望会议识别过载和依赖冲突并提出调整建议，以便降低协调成本", "建议必须人工批准"),
            new StoryRow("US33", 2, 4, 0, "Must", 0, "AI 建议审核中心", "作为负责人，我希望采纳、修改或拒绝 AI 建议，以便保持人在回路", "记录审核人、结果、理由和影响范围"),
            new StoryRow("US34", 3, 4, 0, "Must", 0, "GitHub 仓库与成员映射", "作为管理员，我希望绑定仓库并映射成员，以便分析真实开发活动", "同步 commit、push、PR、review、issue"),
            new StoryRow("US35", 3, 4, 3, "Must", 0, "任务提交智能体工作状态分析", "作为负责人，我希望分析提交、任务关联和协作行为，以便发现阻塞和无进展", "输出事实、时间范围、证据和不确定性"),
            new StoryRow("US36", 3, 4, 3, "Must", 0, "成员工作状态汇总", "作为负责人，我希望查看成员完成内容、负载和工作摘要，以便进行有依据的协调", "区分事实与推断并支持下钻"),
            new StoryRow("US37", 3, 5, 0, "Should", 0, "项目层面协调建议", "作为负责人，我希望在项目层面获得可解释的协调建议，以便处理异常模式", "建议说明范围、依据和影响"));

    /**
     * (id, name, ownerIdx, hours, weekStart, weekEnd, storyRef, dependsOn, status, progress, blocked)
     */
    private record TaskRow(String id, String name, int ownerIdx, int hours, int weekStart, int weekEnd,
                           String storyRef, String dependsOn, int status, int progress, int blocked) {
    }

    private static final List<TaskRow> TASKS = List.of(
            new TaskRow("T01", "启动会、基线、六项材料初稿", 0, 12, 1, 1, "US01,US03,US04", "", 0, 0, 0),
            new TaskRow("T02", "共享数据、接口、AI 接入约定；UML 及排期小样", 2, 12, 1, 1, "US10,US13,US18", "T01", 0, 0, 0),
            new TaskRow("T03", "项目、成员、自定义权限 / US01、US02", 0, 16, 1, 2, "US01,US02", "T02", 1, 50, 0),
            new TaskRow("T04", "故事、地图、任务、基础看板 / US03-US06", 1, 16, 1, 2, "US03,US04,US05,US06", "T02,T03", 1, 50, 0),
            new TaskRow("T05", "基础报表、历史记录、Sprint 1 验证 / US07", 3, 12, 2, 2, "US07", "T03,T04", 0, 0, 0),
            new TaskRow("T06", "实时进度、需求池、成员 Bandwidth / US25-US27", 1, 12, 2, 2, "US25,US26,US27", "T03,T04", 0, 0, 0),
            new TaskRow("T07", "甘特图与成员任务图 / US10、US11", 2, 12, 2, 2, "US10,US11,US27", "T02,T04,T06", 0, 0, 0),
            new TaskRow("T08", "UML 自动生成与交互编辑、AI 项目规划画布 MVP / US13", 3, 20, 3, 4, "US13,US10,US11,US04", "T04,T06,T07", 0, 0, 0),
            new TaskRow("T09", "权限扩展、四视图联动、成员贡献活动图 / US08、US12、US28", 0, 8, 3, 4, "US08,US12,US28", "T06,T07,T08", 0, 0, 0),
            new TaskRow("T10", "会议智能体 MVP：录音、知情同意、可编辑转写、会议总结", 1, 16, 3, 4, "US29,US30", "T04,T06,T09", 0, 0, 0),
            new TaskRow("T11", "会议分析、需求/人员/任务调整建议与 AI 建议审核中心", 0, 16, 4, 4, "US31,US32,US33", "T09,T10", 0, 0, 0),
            new TaskRow("T12", "AI 需求拆解、故事修订与来源追溯、进度预测、智能排期", 2, 12, 4, 5, "US14,US17,US18,US19", "T05,T07", 0, 0, 0),
            new TaskRow("T13", "AI 代码/文档/测试质量分析、风险预警、效率优化、AI 闭环", 0, 12, 5, 6, "US16,US20,US22,US23,US24", "T09,T10,T11,T12", 0, 0, 0),
            new TaskRow("T14", "GitHub 绑定、任务提交智能体、成员状态汇总、项目协调建议", 3, 12, 5, 6, "US34,US35,US36,US37", "T03,T04,T09,T13", 0, 0, 0),
            new TaskRow("T15", "完整回归、联调修正、部署与演示准备", 2, 12, 6, 6, "US08,US12,US13,US24", "T11,T12,T13,T14", 0, 0, 0),
            new TaskRow("T16", "Scrum 评审 / 复盘 / 轮值交接 / 记录归档", 0, 8, 1, 6, "US07,US24,US37", "", 0, 0, 0));

    /** 开发任务挂到看板卡(管理类任务不挂卡) */
    private static final Map<String, String> TASK_CARD_LINKS = Map.ofEntries(
            Map.entry("T03", "US01"), Map.entry("T04", "US03"), Map.entry("T05", "US07"),
            Map.entry("T06", "US25"), Map.entry("T07", "US10"), Map.entry("T08", "US13"),
            Map.entry("T09", "US08"), Map.entry("T10", "US29"), Map.entry("T11", "US31"),
            Map.entry("T12", "US14"), Map.entry("T13", "US16"), Map.entry("T14", "US34"));

    private static final Set<String> MANAGEMENT_TASKS = Set.of("T01", "T02", "T15", "T16");

    /** 历史任务签名(替换 US 基线前的值):用于判断某行是否仍是未改动的旧种子 */
    private record LegacyTask(String name, int ownerIdx, int hours, int weekStart, int weekEnd, String storyRef) {
    }

    private static final Map<String, LegacyTask> LEGACY_TASKS = Map.ofEntries(
            Map.entry("T01", new LegacyTask("启动规划与基线", 0, 12, 1, 1, "范围基线")),
            Map.entry("T02", new LegacyTask("共享数据与接口小样", 2, 12, 1, 1, "技术约定")),
            Map.entry("T03", new LegacyTask("项目/成员/自定义权限", 0, 16, 1, 2, "US01,02")),
            Map.entry("T04", new LegacyTask("故事/地图/基础看板", 1, 16, 1, 2, "US03-06")),
            Map.entry("T05", new LegacyTask("基础报表与 S1 验证", 3, 12, 2, 2, "US07")),
            Map.entry("T06", new LegacyTask("看板增强与趋势", 1, 12, 3, 3, "US09,15")),
            Map.entry("T07", new LegacyTask("甘特图与成员任务图", 2, 12, 3, 4, "US10,11")),
            Map.entry("T08", new LegacyTask("UML 自动生成与编辑", 3, 20, 2, 4, "US13")),
            Map.entry("T09", new LegacyTask("权限扩展与联动", 0, 8, 4, 4, "US08,12")),
            Map.entry("T10", new LegacyTask("AI 拆解与估算", 1, 12, 2, 4, "US14,17")),
            Map.entry("T11", new LegacyTask("AI 进度预测与排期", 2, 12, 4, 5, "US18,19")),
            Map.entry("T12", new LegacyTask("AI 质量分析", 3, 12, 4, 5, "US22")),
            Map.entry("T13", new LegacyTask("AI 风险/效率/闭环", 0, 12, 5, 6, "US16,20,23,24")),
            Map.entry("T14", new LegacyTask("类图关联与影响传播", 3, 12, 4, 5, "US21")),
            Map.entry("T15", new LegacyTask("完整回归与部署演示", 2, 12, 6, 6, "全量回归")),
            Map.entry("T16", new LegacyTask("Scrum 管理与证据", 0, 8, 1, 6, "持续管理")));

    private static final Pattern LEGACY_STORY_ID = Pattern.compile("^M(\\d+)$");

    @Override
    public void run(ApplicationArguments args) {
        if (!seedOnStart) {
            log.info("数据播种已关闭(aicap.seed-on-start=false)");
            return;
        }
        seedUsers();
        boolean migrated = migrateLegacyStorySeed();
        seedStories();
        seedTasks(migrated);
    }

    // ---------- 用户 ----------

    private void seedUsers() {
        List<User> existing = userMapper.selectList(null);
        existing.sort((a, b) -> Integer.compare(a.getId(), b.getId()));

        if (existing.isEmpty()) {
            for (UserRow row : USERS) {
                userMapper.insert(newUser(row));
            }
            log.info("已播种 {} 个演示用户(密码 123456)", USERS.size());
            return;
        }

        boolean changed = false;
        int aligned = Math.min(existing.size(), USERS.size());
        for (int i = 0; i < aligned; i++) {
            User user = existing.get(i);
            UserRow row = USERS.get(i);
            if (!row.displayName().equals(user.getDisplayName()) || !row.role().equals(user.getRole())
                    || !row.color().equals(user.getColor())
                    || user.getCapacityHours() == null || user.getCapacityHours() != row.capacityHours()) {
                user.setDisplayName(row.displayName());
                user.setRole(row.role());
                user.setColor(row.color());
                user.setCapacityHours(row.capacityHours());
                userMapper.updateById(user);
                changed = true;
            }
        }

        // 缺行补齐;并保证库里存在一个 viewer(对齐 FastAPI seed_users 的兜底)
        List<UserRow> missing = new ArrayList<>(USERS.subList(aligned, USERS.size()));
        boolean hasViewer = existing.stream().anyMatch(u -> "viewer".equals(u.getRole()));
        Set<String> usernames = new HashSet<>();
        for (User u : existing) {
            usernames.add(u.getUsername());
        }
        UserRow viewerRow = USERS.get(USERS.size() - 1);
        if (!hasViewer && !usernames.contains(viewerRow.username())
                && !missing.contains(viewerRow)) {
            missing.add(viewerRow);
        }
        for (UserRow row : missing) {
            userMapper.insert(newUser(row));
            changed = true;
        }
        if (changed) {
            log.info("演示用户已对齐当前基线(缺行补插;存量库沿用原用户名,登录别名生效)");
        }
    }

    private User newUser(UserRow row) {
        User user = new User();
        user.setUsername(row.username());
        user.setDisplayName(row.displayName());
        user.setRole(row.role());
        user.setColor(row.color());
        user.setCapacityHours(row.capacityHours());
        user.setPasswordHash(passwordUtil.hash("123456"));
        return user;
    }

    // ---------- 故事 ----------

    /** 是否为旧的 M01–M23 演示基线(20..23 行且全部是 M1..M23) */
    private boolean isLegacyStorySeed() {
        List<Story> stories = storyMapper.selectList(null);
        if (stories.size() < 20 || stories.size() > 23) {
            return false;
        }
        for (Story story : stories) {
            var m = LEGACY_STORY_ID.matcher(story.getId() == null ? "" : story.getId());
            if (!m.matches()) {
                return false;
            }
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > 23) {
                return false;
            }
        }
        return true;
    }

    /** 把旧的 M 基线整批替换为 US 基线,并清掉任务上指向旧卡/旧故事的引用 */
    private boolean migrateLegacyStorySeed() {
        if (!isLegacyStorySeed()) {
            return false;
        }
        Set<String> legacyIds = new HashSet<>();
        for (Story story : storyMapper.selectList(null)) {
            legacyIds.add(story.getId());
        }
        for (Task task : taskMapper.selectList(null)) {
            boolean touched = false;
            if (task.getKanbanCardId() != null && legacyIds.contains(task.getKanbanCardId())) {
                task.setKanbanCardId(null);
                touched = true;
            }
            if (task.getStoryRef() != null && !task.getStoryRef().isBlank()) {
                for (String ref : task.getStoryRef().split(",")) {
                    if (legacyIds.contains(ref.trim())) {
                        task.setStoryRef("");
                        touched = true;
                        break;
                    }
                }
            }
            if (touched) {
                taskMapper.updateById(task);
            }
        }
        for (String id : legacyIds) {
            storyMapper.deleteById(id);
        }
        insertStories();
        log.info("检测到旧故事基线 M01–M23({} 条),已替换为 US01–US37", legacyIds.size());
        return true;
    }

    private void seedStories() {
        if (storyMapper.selectCount(null) > 0) {
            return;
        }
        insertStories();
        log.info("已播种 {} 条故事(US01–US37)", STORIES.size());
    }

    private void insertStories() {
        LocalDateTime now = LocalDateTime.now();
        for (StoryRow row : STORIES) {
            Story story = new Story();
            story.setId(row.id());
            story.setTitle(row.title());
            story.setDescription(row.description());
            story.setAcceptance(row.acceptance());
            story.setPriority(row.priority());
            story.setSprint(row.sprint());
            story.setActivity(row.activity());
            story.setStatus(row.status());
            story.setOwnerId(row.ownerIdx() + 1);
            story.setCreatedAt(now);
            storyMapper.insert(story);
        }
    }

    // ---------- 任务 ----------

    private void seedTasks(boolean migrated) {
        Map<String, Task> existing = new LinkedHashMap<>();
        for (Task task : taskMapper.selectList(null)) {
            existing.put(task.getId(), task);
        }
        if (existing.isEmpty()) {
            for (TaskRow row : TASKS) {
                Task task = new Task();
                task.setId(row.id());
                applyTaskValues(task, row);
                taskMapper.insert(task);
            }
            log.info("已播种 {} 条任务(T01–T16,含依赖/进度/阻塞)", TASKS.size());
            return;
        }

        boolean upgraded = false;
        for (TaskRow row : TASKS) {
            Task task = existing.get(row.id());
            if (task == null) {
                Task created = new Task();
                created.setId(row.id());
                applyTaskValues(created, row);
                taskMapper.insert(created);
                upgraded = true;
                continue;
            }
            LegacyTask legacy = LEGACY_TASKS.get(row.id());
            if (legacy == null) {
                continue;
            }
            boolean untouchedLegacy = legacy.name().equals(task.getName())
                    && task.getOwnerId() != null && task.getOwnerId() - 1 == legacy.ownerIdx()
                    && task.getHours() != null && task.getHours() == legacy.hours()
                    && task.getWeekStart() != null && task.getWeekStart() == legacy.weekStart()
                    && task.getWeekEnd() != null && task.getWeekEnd() == legacy.weekEnd()
                    && java.util.Objects.equals(task.getStoryRef(), legacy.storyRef());
            if (migrated || untouchedLegacy) {
                applyTaskValues(task, row);
                taskMapper.updateById(task);
                upgraded = true;
            }
        }
        if (upgraded) {
            log.info("任务已升级到当前基线(名称/关联/类型/依赖/状态/进度/阻塞)");
        }
    }

    private void applyTaskValues(Task task, TaskRow row) {
        task.setName(row.name());
        task.setOwnerId(row.ownerIdx() + 1);
        task.setHours(row.hours());
        task.setWeekStart(row.weekStart());
        task.setWeekEnd(row.weekEnd());
        task.setStoryRef(row.storyRef());
        task.setKanbanCardId(TASK_CARD_LINKS.get(task.getId()));
        task.setEstimatedHours(row.hours());
        task.setTaskType(MANAGEMENT_TASKS.contains(task.getId()) ? "management" : "feature");
        task.setDependsOn(row.dependsOn());
        task.setStatus(row.status());
        task.setProgress(row.progress());
        task.setBlocked(row.blocked());
    }
}
