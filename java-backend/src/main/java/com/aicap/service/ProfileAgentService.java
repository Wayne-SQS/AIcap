package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.ProfileAgentDtos;
import com.aicap.entity.ActivityRecord;
import com.aicap.entity.DifficultyAssessment;
import com.aicap.entity.MemberProfile;
import com.aicap.entity.ProfileAgentRun;
import com.aicap.entity.MeetingSuggestionRecord;
import com.aicap.entity.ProfileCorrection;
import com.aicap.entity.ProfileSnapshot;
import com.aicap.entity.Suggestion;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.ActivityRecordMapper;
import com.aicap.mapper.DifficultyAssessmentMapper;
import com.aicap.mapper.MeetingSuggestionRecordMapper;
import com.aicap.mapper.MemberProfileMapper;
import com.aicap.mapper.ProfileAgentRunMapper;
import com.aicap.mapper.ProfileCorrectionMapper;
import com.aicap.mapper.ProfileSnapshotMapper;
import com.aicap.mapper.SuggestionMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.service.ProjectScopedResolver;
import com.aicap.service.ProjectAccessService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI 任务提交与成员能力画像智能体(画像智能体)服务。
 *
 * 核心语义(对齐《爱管理_三大AI智能体功能设计.md》第 4 章):
 * - 分析每个成员一段时间内做了什么(工作事实)、承担了多大难度(难度分析)、
 *   交付质量与协作贡献、当前负载与项目风险;
 * - 每条结论必须带证据,数据不足时明确说明,不编造;
 * - 区分「客观事实」与「AI 推断」;
 * - 不把提交次数等同于工作量或工作质量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileAgentService {

    private final ActivityRecordMapper activityMapper;
    private final DifficultyAssessmentMapper difficultyMapper;
    private final ProfileSnapshotMapper snapshotMapper;
    private final ProfileAgentRunMapper runMapper;
    private final ProfileCorrectionMapper correctionMapper;
    private final SuggestionMapper suggestionMapper;
    private final MeetingSuggestionRecordMapper meetingSuggestionRecordMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final MemberProfileMapper memberProfileMapper;
    private final ObjectMapper objectMapper;
    private final com.aicap.profile.ProfileAgentBrain brain;   // LLM 内核(未配置密钥时自动降级规则引擎)
    private final ProjectScopedResolver projectScopedResolver;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 活动类型:commit/pr/review/bugfix/task_done/note/test(设计文档 4.2 测试维度) */
    private static final List<String> ACTIVITY_TYPES =
            List.of("commit", "pr", "review", "bugfix", "task_done", "note", "test");
    /** 分析时间范围最大跨度(天):防超大响应/资源耗尽 */
    private static final int MAX_RANGE_DAYS = 400;
    /** 同一用户两次**全团队**正式分析的最短间隔(毫秒):防刷 API 额度(6 次 LLM 调用/次) */
    private static final long RUN_RATE_LIMIT_MS = 30_000L;
    /**
     * 单人评估(成员完成任务触发)的去抖窗口:只防双击。
     * 与全团队分析**分开计数**——四名成员连续完成多个任务时,每次都必须能评估;
     * 单人评估只消耗 1 次成员画像调用,不该被全团队那条 30 秒窗口挡住。
     */
    private static final long SCOPED_RUN_DEBOUNCE_MS = 3_000L;
    /** 按任务粒度的评估锁:防止并发请求对同一任务重复评估/重复落库 */
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();
    /** 分析频率限制:键 = "team:<请求者>" / "scoped:<请求者>",两条通道互不影响 */
    private final ConcurrentHashMap<String, Long> lastRunByUser = new ConcurrentHashMap<>();

    // ---------- 活动录入 ----------

    public ProfileAgentDtos.ActivityOut addActivity(String projectId, ProfileAgentDtos.ActivityIn in, User actor) {
        if (!ACTIVITY_TYPES.contains(in.activityType())) {
            throw ApiException.unprocessable("activity_type 必须是 " + ACTIVITY_TYPES + " 之一");
        }
        // H1 权限修复:member 只能录入自己的活动事实(admin/owner 可代录),防止伪造他人活动污染画像
        boolean isManager = "admin".equals(actor.getRole()) || "owner".equals(actor.getRole());
        if (!isManager && !in.userId().equals(actor.getId())) {
            throw ApiException.forbidden("只能录入自己的活动事实(admin/owner 可代录)");
        }
        if (userMapper.selectById(in.userId()) == null) {
            throw ApiException.notFound("成员不存在");
        }
        if (in.taskId() != null && !in.taskId().isBlank()) {
            if (projectScopedResolver.findTask(projectId, in.taskId()) == null) throw ApiException.notFound("任务不存在: " + in.taskId());
        } else {
            in = new ProfileAgentDtos.ActivityIn(in.userId(), null, in.activityType(),
                    in.title(), in.detail(), in.module(), in.happenedAt());
        }
        LocalDateTime at;
        try {
            at = LocalDateTime.parse(in.happenedAt(), TS);
        } catch (Exception e) {
            throw ApiException.unprocessable("happened_at 格式应为 yyyy-MM-dd HH:mm:ss");
        }
        // L2 修复:活动时间不能是未来时间(容忍 5 分钟时钟偏差)
        if (at.isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw ApiException.unprocessable("happened_at 不能晚于当前时间");
        }

        ActivityRecord r = new ActivityRecord();
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        r.setProjectId(scopedProjectId);
        r.setUserId(in.userId());
        r.setTaskId(in.taskId());
        if (in.taskId() != null && !in.taskId().isBlank()) {
            Task task = projectScopedResolver.requireTask(scopedProjectId, in.taskId());
            r.setTaskInternalId(task.getInternalId());
            r.setTaskCodeSnapshot(task.getId());
        }
        r.setActivityType(in.activityType());
        r.setTitle(in.title());
        r.setDetail(in.detail());
        r.setModule(in.module());
        r.setSource("manual");
        r.setHappenedAt(at);
        r.setCreatedBy(actor.getId());
        r.setCreatedAt(LocalDateTime.now());
        activityMapper.insert(r);
        return toOut(r);
    }

    public List<ProfileAgentDtos.ActivityOut> listActivities(Integer userId, LocalDate start, LocalDate end) {
        QueryWrapper<ActivityRecord> qw = new QueryWrapper<>();
        if (userId != null) qw.eq("user_id", userId);
        if (start != null) qw.ge("happened_at", start.atStartOfDay());
        if (end != null) qw.le("happened_at", end.atTime(23, 59, 59));
        qw.orderByDesc("happened_at").last("LIMIT 1000");   // L3: 防全量返回
        return activityMapper.selectList(qw).stream().map(this::toOut).toList();
    }

    public List<ProfileAgentDtos.ActivityOut> listActivities(String projectId, Integer userId,
                                                              LocalDate start, LocalDate end) {
        QueryWrapper<ActivityRecord> qw = new QueryWrapper<ActivityRecord>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .eq(userId != null, "user_id", userId)
                .ge(start != null, "happened_at", start == null ? null : start.atStartOfDay())
                .le(end != null, "happened_at", end == null ? null : end.atTime(23, 59, 59))
                .orderByDesc("happened_at").last("LIMIT 1000");
        return activityMapper.selectList(qw).stream().map(this::toOut).toList();
    }

    private ProfileAgentDtos.ActivityOut toOut(ActivityRecord r) {
        return new ProfileAgentDtos.ActivityOut(r.getId(), r.getUserId(), r.getTaskId(),
                r.getActivityType(), r.getTitle(), r.getDetail(), r.getModule(), r.getSource(),
                r.getHappenedAt() == null ? null : r.getHappenedAt().format(TS));
    }

    /** 批量导入活动事实(文档 4.2:提交/Review 等活动批量接入;source=import)。
     *  事务性:任一条校验失败则整体回滚,避免中途失败导致部分入库、数据不完整。 */
    @Transactional
    public List<ProfileAgentDtos.ActivityOut> importActivities(String projectId, List<ProfileAgentDtos.ActivityIn> items, User actor) {
        if (items == null || items.isEmpty()) throw ApiException.badRequest("导入列表不能为空");
        if (items.size() > 200) throw ApiException.unprocessable("单次最多导入 200 条");
        List<ProfileAgentDtos.ActivityOut> out = new ArrayList<>();
        for (ProfileAgentDtos.ActivityIn in : items) {
            out.add(addActivity(projectId, in, actor));
        }
        return out;
    }

    /** 贡献活动热力图(文档 4.10):按日聚合活动类型计数,前端画绿格子 */
    public List<Map<String, Object>> heatmap(String projectId, Integer userId, LocalDate start, LocalDate end) {
        validateRange(start, end);   // M2: 时间范围上限与顺序校验
        Set<String> taskIds = projectTaskIds(projectId);
        List<ActivityRecord> acts = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .eq(userId != null, "user_id", userId)
                .ge("happened_at", start.atStartOfDay())
                .le("happened_at", end.atTime(23, 59, 59))
                .orderByAsc("happened_at")).stream()
                .filter(a -> a.getProjectId() == null || ProjectAccessService.normalize(projectId).equals(a.getProjectId())).toList();
        // 连续按天铺满区间,无活动日为 0
        Map<LocalDate, Map<String, Integer>> byDay = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Map<String, Integer> zero = new LinkedHashMap<>();
            for (String t : ACTIVITY_TYPES) zero.put(t, 0);
            byDay.put(d, zero);
        }
        for (ActivityRecord r : acts) {
            LocalDate d = r.getHappenedAt().toLocalDate();
            Map<String, Integer> m = byDay.computeIfAbsent(d, k -> {
                Map<String, Integer> z = new LinkedHashMap<>();
                for (String t : ACTIVITY_TYPES) z.put(t, 0);
                return z;
            });
            m.merge(r.getActivityType(), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byDay.forEach((d, counts) -> {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("total", total);
            row.put("counts", counts);
            out.add(row);
        });
        return out;
    }

    // ---------- 难度评估 ----------

    /** AI 评估:依据任务工时/依赖/阻塞/进度等可量化因素,逐条给出依据。 */
    public List<ProfileAgentDtos.DifficultyOut> assessDifficulty(LocalDate start, LocalDate end) {
        throw ApiException.badRequest("项目级难度评估必须提供 projectId");
    }

    /**
     * 难度评估(双模式):
     * - llmAndPersist=false(只读预览,GET /difficulty、/analysis、/compare):只复用已入库评估;
     *   缺失的任务用规则引擎即时计算但不落库、不调 LLM —— 预览零费用零副作用。
     * - llmAndPersist=true(正式分析,POST /analysis/run、送审):LLM 内核优先,失败降级规则引擎,落库留痕。
     */
    public List<ProfileAgentDtos.DifficultyOut> assessDifficulty(LocalDate start, LocalDate end, boolean llmAndPersist) {
        throw ApiException.badRequest("项目级难度评估必须提供 projectId");
    }

    public List<ProfileAgentDtos.DifficultyOut> assessDifficulty(String projectId, LocalDate start, LocalDate end,
                                                                  boolean llmAndPersist) {
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        List<Task> all = taskMapper.selectList(new QueryWrapper<Task>().eq("project_id", scopedProjectId));
        // 依赖图是全局结构,必须基于全量任务统计(否则窗口外前置任务被漏掉,下游数失真)
        Map<String, Integer> downstream = downstreamCounts(all);   // 4.5: 被后续任务依赖数

        // 按查询范围过滤:此前 start/end 完全没被使用,任意范围都返回全表评估(名不副实)
        int[] span = projectWeekSpan(scopedProjectId, start, end);
        Set<String> scope = scopeTaskIds(scopedProjectId, start, end, all, span);
        List<Task> tasks = (scope == null) ? all : all.stream().filter(t -> scope.contains(t.getId())).toList();
        if (span != null) {
            log.info("[profile-agent] 难度评估范围过滤: {}~{} → 项目周 {}~{}, 任务 {}/{} 在范围内",
                    start, end, span[0], span[1], tasks.size(), all.size());
            // 范围内一个任务都没有(例:项目排期到第 6 周,用户选了第 7 周):
            // 难度是任务固有属性,返回空表没有任何信息量,还会让"跑一次分析"看起来毫无产出。
            // 这里回退为全量并记 warn —— 宁可多给,不要给空。
            if (tasks.isEmpty() && !all.isEmpty()) {
                tasks = all;
                log.warn("[profile-agent] 难度评估范围 {}~{} (项目周 {}~{}) 内无任务,回退为全量 {} 个任务",
                        start, end, span[0], span[1], all.size());
            }
        }

        // 第一遍:区分「可直接复用」与「本轮待评估」,以便把待评估任务合并成一次批量 LLM 调用
        List<ProfileAgentDtos.DifficultyOut> out = new ArrayList<>();
        List<Task> pending = new ArrayList<>();
        for (Task t : tasks) {
            if (t.getStatus() != null && t.getStatus() == 3) continue; // 已取消不评估
            DifficultyAssessment manual = latestAssessment(scopedProjectId, t.getId(), "manual");
            if (manual != null) { // 人工评估优先,不覆盖
                out.add(toDifficultyOut(manual));
                continue;
            }
            DifficultyAssessment existing = latestAssessment(scopedProjectId, t.getId(), "ai", "rules");
            if (existing != null) {   // 已有智能体/规则评估则复用,不重复扣费
                out.add(toDifficultyOut(existing));
                continue;
            }
            pending.add(t);
        }

        if (!llmAndPersist) {
            // 只读预览:规则引擎即时算(assessed_by=rules),不落库、不调 LLM —— 零费用零副作用
            for (Task t : pending) {
                out.add(toDifficultyOut(bindTask(
                        ruleEngineAssessment(t, false, downstream.getOrDefault(t.getId(), 0)), scopedProjectId, t)));
            }
            return out;
        }

        // 正式分析:一次批量调用覆盖全部待评估任务(N 次规划循环 → ceil(N/batchSize) 次直调)
        final boolean llmOk = brain.available();
        final boolean batchOn = llmOk && brain.batchEnabled();
        final Map<String, com.aicap.profile.ProfileAgentBrain.DifficultyConclusion> batch;
        if (!pending.isEmpty() && batchOn) {
            List<com.aicap.profile.ProfileAgentBrain.TaskFacts> facts = new ArrayList<>();
            for (Task t : pending) {
                facts.add(new com.aicap.profile.ProfileAgentBrain.TaskFacts(
                        t, downstream.getOrDefault(t.getId(), 0)));
            }
            batch = brain.batchAssessDifficulty(scopedProjectId, facts);   // null=不可用;空/部分=模型漏了该任务
        } else {
            batch = null;
        }

        // 未命中任务的兜底策略:
        // - 批量被显式关闭(灰度回滚)→ 回退为「每个任务单跑一次规划循环」,即改造前行为
        // - 批量已生效 → 只对少量漏评补单任务,避免"批量失败→逐个重跑"把成本打回原形
        // - LLM 不可用 → 规则引擎
        long missed = pending.stream()
                .filter(t -> batch == null || !batch.containsKey(t.getId())).count();
        boolean allowSingleFallback;
        if (!llmOk) {
            allowSingleFallback = false;
            log.info("[profile-agent] 难度评估:LLM 不可用,{} 个任务全部走规则引擎", pending.size());
        } else if (!batchOn) {
            allowSingleFallback = !pending.isEmpty();
            log.info("[profile-agent] 难度评估:批量已关闭(灰度回滚),{} 个任务逐个跑规划循环", pending.size());
        } else {
            allowSingleFallback = missed > 0 && missed <= Math.max(2, (long) Math.ceil(pending.size() * 0.1));
            log.info("[profile-agent] 难度评估批量完成: 待评估={}, 批量命中={}, 漏评={}, 单任务补评估={}",
                    pending.size(), pending.size() - missed, missed, allowSingleFallback);
        }

        for (Task t : pending) {
            // H3 修复:按任务粒度加锁,同一任务的评估串行化,防并发重复评估/重复落库
            Object lock = taskLocks.computeIfAbsent(t.getId(), k -> new Object());
            synchronized (lock) {
                DifficultyAssessment raced = latestAssessment(scopedProjectId, t.getId(), "manual", "ai", "rules");
                if (raced != null) {   // 加锁后复核:并发下可能已被其它线程评估落库
                    out.add(toDifficultyOut(raced));
                    continue;
                }
                int dc = downstream.getOrDefault(t.getId(), 0);
                com.aicap.profile.ProfileAgentBrain.DifficultyConclusion c =
                        batch == null ? null : batch.get(t.getId());
                DifficultyAssessment ai;
                if (c != null) {
                    ai = toAssessment(scopedProjectId, t, c);              // 批量 LLM 命中
                } else if (allowSingleFallback) {
                    ai = buildAiAssessment(scopedProjectId, t, dc);        // 少量漏评:单任务 LLM 补
                } else {
                    ai = ruleEngineAssessment(t, true, dc);                // 规则引擎兜底
                }
                ai.setProjectId(scopedProjectId);
                ai.setTaskInternalId(t.getInternalId());
                ai.setTaskCodeSnapshot(t.getId());
                difficultyMapper.insert(ai);
                out.add(toDifficultyOut(ai));
            }
        }
        return out;
    }

    /** 把批量/单任务的 LLM 结论落成评估实体(与 buildAiAssessment 同口径,抽出避免重复) */
    private DifficultyAssessment toAssessment(String projectId, Task t,
                                              com.aicap.profile.ProfileAgentBrain.DifficultyConclusion c) {
        List<String> basis = new ArrayList<>(c.basis());
        basis.add("[confidence=" + c.confidence() + ", evidence=" + c.evidenceRefs() + "]");
        DifficultyAssessment a = new DifficultyAssessment();
        a.setProjectId(ProjectAccessService.normalize(projectId));
        a.setTaskId(t.getId());
        a.setTaskInternalId(t.getInternalId());
        a.setTaskCodeSnapshot(t.getId());
        a.setLevel(c.level());
        a.setScore(c.score());
        a.setBasis(writeJson(basis));
        a.setAssessedBy("ai");
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }

    /**
     * 项目第 1 周的锚点 = 最早一条活动记录所在周的周一;无活动记录 → null(无法映射周次)。
     */
    private LocalDate projectAnchorOrNull(String projectId) {
        ActivityRecord first = activityMapper.selectOne(new QueryWrapper<ActivityRecord>()
                .eq("project_id", projectId)
                .orderByAsc("happened_at").last("LIMIT 1"));
        if (first == null || first.getHappenedAt() == null) return null;
        return first.getHappenedAt().toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 把查询时间范围映射成「项目相对周」闭区间 [w1,w2]。
     * start/end 任一为空、或无锚点可映射 → 返回 null 表示「不按范围过滤」。
     */
    private int[] projectWeekSpan(String projectId, LocalDate start, LocalDate end) {
        if (start == null || end == null) return null;
        LocalDate anchor = projectAnchorOrNull(projectId);
        if (anchor == null) return null;
        int w1 = (int) java.time.temporal.ChronoUnit.WEEKS.between(anchor, start) + 1;
        int w2 = (int) java.time.temporal.ChronoUnit.WEEKS.between(anchor, end) + 1;
        return new int[]{Math.min(w1, w2), Math.max(w1, w2)};
    }

    /**
     * 本次范围内的任务业务码集合;返回 null = 不过滤(全量)。
     * 纳入条件(满足其一):计划周 [week_start, week_end] 与范围周区间重叠;或窗口内有该任务的活动记录。
     * week 字段缺失的任务无法判定,保守纳入。
     */
    private Set<String> scopeTaskIds(String projectId, LocalDate start, LocalDate end, List<Task> tasks, int[] span) {
        if (span == null) return null;
        Set<String> ids = new HashSet<>();
        for (Task t : tasks) {
            Integer ws = t.getWeekStart(), we = t.getWeekEnd();
            if (ws == null || we == null) {
                ids.add(t.getId());           // 无周次信息,无从排除 → 纳入
                continue;
            }
            if (ws <= span[1] && we >= span[0]) ids.add(t.getId());
        }
        List<ActivityRecord> acts = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .eq("project_id", projectId)
                .ge("happened_at", start.atStartOfDay())
                .le("happened_at", end.atTime(23, 59, 59)));
        for (ActivityRecord a : acts) {
            if (a.getTaskId() != null && !a.getTaskId().isBlank()) ids.add(a.getTaskId());
        }
        return ids;
    }

    /** 4.5: 统计每个任务被多少后续任务依赖(影响后续任务的程度,如"被两个后续任务依赖→高难度") */
    private Map<String, Integer> downstreamCounts(List<Task> tasks) {
        Map<String, Integer> down = new HashMap<>();
        for (Task t : tasks) {
            for (String dep : splitDeps(t.getDependsOn())) {
                down.merge(dep, 1, Integer::sum);
            }
        }
        return down;
    }

    /** 人工修正难度(覆盖 AI 结论;assessed_by=manual 永远优先) */
    public ProfileAgentDtos.DifficultyOut overrideDifficulty(String projectId, String taskId, ProfileAgentDtos.DifficultyIn in) {
        if (projectScopedResolver.findTask(projectId, taskId) == null) throw ApiException.notFound("任务不存在: " + taskId);
        List<String> levels = List.of("low", "medium", "high", "extreme");
        if (!levels.contains(in.level())) {
            throw ApiException.unprocessable("level 必须是 low/medium/high/extreme 之一");
        }
        DifficultyAssessment a = new DifficultyAssessment();
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        Task task = projectScopedResolver.requireTask(scopedProjectId, taskId);
        a.setProjectId(scopedProjectId);
        a.setTaskId(taskId);
        a.setTaskInternalId(task.getInternalId());
        a.setTaskCodeSnapshot(task.getId());
        a.setLevel(in.level());
        a.setScore(in.score() == null ? defaultScore(in.level()) : in.score());
        // L6 修复:人工修正可附理由,进入记忆时能区分修正动机
        String reason = in.reason() == null || in.reason().isBlank() ? "" : " 理由: " + in.reason().trim();
        a.setBasis(writeJson(List.of("人工修正:项目负责人确认的难度等级" + reason)));
        a.setAssessedBy("manual");
        a.setCreatedAt(LocalDateTime.now());
        difficultyMapper.insert(a);
        return toDifficultyOut(a);
    }

    /**
     * 难度评估:LLM 内核(推理+工具+记忆)优先;未配置密钥/调用失败/输出不合法 → 降级规则引擎。
     * LLM 结论带 confidence 与 evidence_refs,存入 basis 供追溯(可解释验收)。
     */
    private DifficultyAssessment buildAiAssessment(String projectId, Task t, int downstreamCount) {
        // 1) LLM 内核
        com.aicap.profile.ProfileAgentBrain.DifficultyConclusion c = brain.assessDifficulty(projectId, t, downstreamCount);
        if (c != null) {
            List<String> basis = new ArrayList<>(c.basis());
            basis.add("[confidence=" + c.confidence() + ", evidence=" + c.evidenceRefs() + "]");
            DifficultyAssessment a = new DifficultyAssessment();
            a.setProjectId(ProjectAccessService.normalize(projectId));
            a.setTaskId(t.getId());
            a.setTaskInternalId(t.getInternalId());
            a.setTaskCodeSnapshot(t.getId());
            a.setLevel(c.level());
            a.setScore(c.score());
            a.setBasis(writeJson(basis));
            a.setAssessedBy("ai");
            a.setCreatedAt(LocalDateTime.now());
            return a;
        }
        // 2) 规则引擎降级备胎(原打分卡,仅作降级,非主路径)
        return ruleEngineAssessment(t, true, downstreamCount);
    }

    /** 降级备胎:原规则打分卡(LLM 不可用时才走;assessed_by=rules 与 LLM 的 ai 区分) */
    private DifficultyAssessment ruleEngineAssessment(Task t, boolean persist, int downstreamCount) {
        int score = 0;
        List<String> basis = new ArrayList<>();
        int hours = t.getHours() != null ? t.getHours() : 0;
        if (hours >= 16) { score += 30; basis.add("预计工时 " + hours + "h,属于较大工作量(+30)"); }
        else if (hours >= 8) { score += 18; basis.add("预计工时 " + hours + "h,工作量中等(+18)"); }
        else { score += 6; basis.add("预计工时 " + hours + "h,工作量较小(+6)"); }

        List<String> deps = splitDeps(t.getDependsOn());
        if (deps.size() >= 3) { score += 25; basis.add("依赖 " + deps.size() + " 个前置任务,存在复杂依赖链(+25)"); }
        else if (deps.size() >= 1) { score += 12; basis.add("依赖 " + deps.size() + " 个前置任务(+12)"); }

        // 4.5: 影响后续任务——被越多后续任务依赖,影响面越大(设计文档示例"被两个后续任务依赖")
        if (downstreamCount >= 2) { score += 10; basis.add("被 " + downstreamCount + " 个后续任务依赖,影响面大(+10)"); }
        else if (downstreamCount >= 1) { score += 5; basis.add("被 " + downstreamCount + " 个后续任务依赖(+5)"); }

        String name = t.getName() == null ? "" : t.getName();
        boolean core = name.contains("权限") || name.contains("数据库") || name.contains("认证") || name.contains("核心");
        if (core) { score += 20; basis.add("涉及权限/数据库/认证等核心模块(+20)"); }

        if (t.getBlocked() != null && t.getBlocked() == 1) { score += 15; basis.add("任务当前处于阻塞状态(+15)"); }
        if (t.getWeekEnd() != null && t.getWeekStart() != null && t.getWeekEnd() - t.getWeekStart() >= 2) {
            score += 10; basis.add("跨 " + (t.getWeekEnd() - t.getWeekStart() + 1) + " 周,周期较长(+10)");
        }

        String level = score >= 75 ? "extreme" : score >= 50 ? "high" : score >= 25 ? "medium" : "low";
        if (basis.isEmpty()) basis.add("任务要素简单,无显著复杂因素(+0)");

        DifficultyAssessment a = new DifficultyAssessment();
        a.setTaskInternalId(t.getInternalId());
        a.setTaskCodeSnapshot(t.getId());
        a.setTaskId(t.getId());
        a.setLevel(level);
        a.setScore(Math.min(score, 100));
        a.setBasis(writeJson(basis));
        a.setAssessedBy("rules");   // L5: 与 LLM 的 ai 区分,规则引擎结论不干扰 LLM 历史记忆
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }

    private DifficultyAssessment bindTask(DifficultyAssessment assessment, String projectId, Task task) {
        assessment.setProjectId(projectId);
        assessment.setTaskInternalId(task.getInternalId());
        assessment.setTaskCodeSnapshot(task.getId());
        return assessment;
    }

    private int defaultScore(String level) {
        return switch (level) {
            case "low" -> 10;
            case "medium" -> 35;
            case "high" -> 60;
            default -> 85;
        };
    }

    /** 查某任务指定评估来源的最新一条(支持多来源,如 ai/rules) */
    private DifficultyAssessment latestAssessment(String projectId, String taskId, String... by) {
        QueryWrapper<DifficultyAssessment> qw = new QueryWrapper<DifficultyAssessment>().eq("task_id", taskId);
        if (projectId != null && !projectId.isBlank()) qw.eq("project_id", ProjectAccessService.normalize(projectId));
        if (by.length == 1) qw.eq("assessed_by", by[0]);
        else if (by.length > 1) qw.in("assessed_by", List.of(by));
        qw.orderByDesc("id").last("LIMIT 1");
        List<DifficultyAssessment> list = difficultyMapper.selectList(qw);
        return list.isEmpty() ? null : list.get(0);
    }

    private ProfileAgentDtos.DifficultyOut toDifficultyOut(DifficultyAssessment a) {
        return new ProfileAgentDtos.DifficultyOut(a.getTaskId(), a.getLevel(), a.getScore(),
                readJsonList(a.getBasis()), a.getAssessedBy());
    }

    private List<String> splitDeps(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank()) return List.of();
        return List.of(dependsOn.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ---------- 核心分析(工作事实/贡献/画像/风险) ----------

    /** 文档 4.11:整包分析结果(只读预览:复用已存难度评估,成员/风险走规则引擎,不调 LLM、不落库) */
    public Map<String, Object> analyze(LocalDate start, LocalDate end) {
        throw ApiException.badRequest("项目级画像分析必须提供 projectId");
    }

    /** 全团队成员分析(不带范围收窄) */
    public Map<String, Object> analyze(LocalDate start, LocalDate end, boolean useLlm) {
        throw ApiException.badRequest("项目级画像分析必须提供 projectId");
    }

    /**
     * 整包分析(双模式,每条结论带证据、区分事实与推断):
     * - useLlm=false(预览):零 LLM 调用、零写库,难度复用已存评估,成员/风险走规则引擎;
     * - useLlm=true(正式分析):LLM 内核优先(成员画像+团队风险+难度评估),失败逐项降级规则引擎并落库。
     *
     * <p><b>onlyUserId(可选)</b>:只分析这一名成员。成员完成任务后前端只传提交者,
     * 把"每次都把 5 个人重新 LLM 评估一遍"降为 1 次成员画像调用(6 次 → 1 次);
     * 该范围下跳过 LLM 团队风险归因(只喂一个人的事实会得出错的团队结论),团队风险走规则引擎,
     * 返回结果带 scope 字段如实标出本次范围。
     */
    public Map<String, Object> analyze(String projectId, LocalDate start, LocalDate end, boolean useLlm, Integer onlyUserId) {
        validateRange(start, end);   // M2: 时间范围上限与顺序校验
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        List<User> allUsers = userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"));
        List<User> users = allUsers;
        if (onlyUserId != null) {
            users = allUsers.stream().filter(u -> onlyUserId.equals(u.getId())).toList();
            if (users.isEmpty()) {
                throw ApiException.notFound("成员不存在: " + onlyUserId);
            }
        }
        List<Task> tasks = taskMapper.selectList(new QueryWrapper<Task>().eq("project_id", scopedProjectId));
        Map<String, ProfileAgentDtos.DifficultyOut> diffByTask = new HashMap<>();
        List<ProfileAgentDtos.DifficultyOut> difficultyOuts = assessDifficulty(scopedProjectId, start, end, useLlm);
        for (ProfileAgentDtos.DifficultyOut d : difficultyOuts) {
            diffByTask.put(d.taskId(), d);
        }

        List<Map<String, Object>> members = new ArrayList<>();
        List<Map<String, Object>> teamRisks = new ArrayList<>();
        for (User u : users) {
            members.add(memberAnalysis(projectId, u, tasks, diffByTask, start, end, useLlm));
        }
        final List<Map<String, Object>> membersForFacts = members;
        final List<User> usersForFacts = users;
        final List<Task> tasksForFacts = tasks;
        final Map<String, ProfileAgentDtos.DifficultyOut> diffForFacts = diffByTask;
        // ---- LLM 内核优先:团队风险归因;失败/未配置 → 规则引擎降级 ----
        // 单人范围(onlyUserId!=null)不调 LLM:团队风险必须看全体成员,只看一个人会把
        // "他在做关键路径""模块集中在他身上"这类判断做错,宁可降级规则引擎并如实标注。
        boolean llmTeam = false;
        if (useLlm && onlyUserId == null && brain.available()) {
            try {
                /* 事实基座:规则引擎判定所用的事实在这里全部注入给模型。
                   此前只喂了任务名/工时/负载率,模型既无从归因、也无从判断"是缺评审还是没录数据",
                   这是团队风险 LLM 结论质量差(乃至整批失败→静默降级)的重要成因。 */
                List<ActivityRecord> acts = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                        .eq("project_id", scopedProjectId)
                        .ge("happened_at", start.atStartOfDay())
                        .le("happened_at", end.atTime(23, 59, 59)));
                LocalDate recentCut = LocalDate.now().minusDays(7);

                // 任务维度:[review 数, test 数, 活动总数, 近 7 天活动数]
                Map<String, int[]> taskStat = new HashMap<>();
                Map<String, String> taskLast = new HashMap<>();
                // 成员维度:活动类型计数 + 最近活动
                Map<Integer, Map<String, Integer>> userTypeCount = new HashMap<>();
                Map<Integer, String> userLast = new HashMap<>();
                for (ActivityRecord a : acts) {
                    String d = a.getHappenedAt() == null ? "" : a.getHappenedAt().toLocalDate().toString();
                    String tid = a.getTaskId();
                    Integer uid = a.getUserId();
                    if (uid != null) {
                        userTypeCount.computeIfAbsent(uid, k -> new HashMap<>())
                                .merge(a.getActivityType() == null ? "other" : a.getActivityType(), 1, Integer::sum);
                        if (!d.isBlank() && d.compareTo(userLast.getOrDefault(uid, "")) > 0) userLast.put(uid, d);
                    }
                    if (tid == null || tid.isBlank()) continue;
                    int[] v = taskStat.computeIfAbsent(tid, k -> new int[4]);
                    v[2]++;
                    String type = a.getActivityType() == null ? "" : a.getActivityType();
                    if ("review".equals(type)) v[0]++;
                    if ("test".equals(type)) v[1]++;
                    if (!d.isBlank() && d.compareTo(recentCut.toString()) >= 0) v[3]++;
                    if (!d.isBlank() && d.compareTo(taskLast.getOrDefault(tid, "")) > 0) taskLast.put(tid, d);
                }
                // 成员未完成任务工时 / 高难度未完成任务数
                Map<Integer, Integer> openHours = new HashMap<>();
                Map<Integer, Integer> highCnt = new HashMap<>();
                for (Task t : tasksForFacts) {
                    if (t.getOwnerId() == null || (t.getStatus() != null && t.getStatus() >= 2)) continue;
                    openHours.merge(t.getOwnerId(), t.getHours() == null ? 0 : t.getHours(), Integer::sum);
                    ProfileAgentDtos.DifficultyOut d = diffForFacts.get(t.getId());
                    if (d != null && ("high".equals(d.level()) || "extreme".equals(d.level()))) {
                        highCnt.merge(t.getOwnerId(), 1, Integer::sum);
                    }
                }

                Map<String, Object> teamFacts = new LinkedHashMap<>();
                teamFacts.put("range", start + " ~ " + end);
                teamFacts.put("current_project_week", currentProjectWeek(scopedProjectId));
                teamFacts.put("members", membersForFacts.stream().map(m -> {
                    // 用 LinkedHashMap:允许 null 值(Map.of 遇 null 会直接 NPE)
                    LinkedHashMap<String, Object> o = new LinkedHashMap<>();
                    Integer ui = toIntOrNull(m.get("user_id"));
                    User u = ui == null ? null
                            : usersForFacts.stream().filter(x -> ui.equals(x.getId())).findFirst().orElse(null);
                    o.put("user_id", m.get("user_id"));
                    o.put("display_name", m.get("display_name"));
                    o.put("current_load_percent", m.get("current_load_percent"));
                    o.put("capacity_hours", u == null || u.getCapacityHours() == null ? 60 : u.getCapacityHours());
                    o.put("open_task_hours", ui == null ? 0 : openHours.getOrDefault(ui, 0));
                    o.put("high_difficulty_open_tasks", ui == null ? 0 : highCnt.getOrDefault(ui, 0));
                    o.put("activity_counts", userTypeCount.getOrDefault(ui, Map.of()));
                    o.put("last_activity_at", userLast.getOrDefault(ui, ""));
                    return o;
                }).toList());
                teamFacts.put("tasks", tasksForFacts.stream().map(t -> {
                    LinkedHashMap<String, Object> o = new LinkedHashMap<>();
                    ProfileAgentDtos.DifficultyOut d = diffForFacts.get(t.getId());
                    int[] v = taskStat.getOrDefault(t.getId(), new int[4]);
                    o.put("task_id", t.getId());
                    o.put("name", t.getName() == null ? "" : t.getName());
                    o.put("status", t.getStatus() == null ? 0 : t.getStatus());
                    o.put("status_text", statusText(t.getStatus()));
                    o.put("owner_id", t.getOwnerId() == null ? 0 : t.getOwnerId());
                    o.put("hours", t.getHours() == null ? 0 : t.getHours());
                    o.put("week_end", t.getWeekEnd() == null ? 0 : t.getWeekEnd());
                    o.put("difficulty", d == null ? "" : d.level());
                    o.put("difficulty_score", d == null ? 0 : d.score());
                    o.put("review_count", v[0]);
                    o.put("test_count", v[1]);
                    o.put("activity_count", v[2]);
                    o.put("recent_activity_count_7d", v[3]);
                    o.put("last_activity_at", taskLast.getOrDefault(t.getId(), ""));
                    return o;
                }).toList());
                List<Map<String, Object>> llmRisks = brain.teamRisks(projectId, teamFacts);
                if (llmRisks != null) {
                    // H5 修复:过滤幻觉实体——LLM 引用的 user_id/task_id 必须在真实集合内,否则丢弃该条
                    Set<Integer> userIds = users.stream().map(User::getId).collect(Collectors.toSet());
                    Set<String> taskIds = tasks.stream().map(Task::getId).collect(Collectors.toSet());
                    for (Map<String, Object> r : llmRisks) {
                        Object uid = r.get("user_id");
                        if (uid != null && !"null".equals(String.valueOf(uid))) {
                            Integer ui = toIntOrNull(uid);
                            if (ui == null || !userIds.contains(ui)) continue;
                        }
                        Object tid = r.get("task_id");
                        if (tid != null && !"null".equals(String.valueOf(tid))) {
                            String ts = String.valueOf(tid);
                            if (!taskIds.contains(ts)) continue;
                        }
                        teamRisks.add(r);
                    }
                    llmTeam = true;   // LLM 结论(即使为空列表也视为合法结论)优先于规则引擎
                    log.info("[profile-agent] 团队风险 LLM 归因完成: {} 条", teamRisks.size());
                }
            } catch (Exception e) {
                // 不再静默:降级必须留痕,否则"配了 LLM 却出规则结论"无从察觉
                log.warn("[profile-agent] 团队风险 LLM 归因失败,降级规则引擎: {}", e.getMessage());
            }
        }
        if (!llmTeam) {
            /* 规则引擎的团队风险遍历**全部任务**(不只本次评估的成员),其 nameOf 成员名表必须来自
               全体成员:只传范围收窄后的列表会让 nameOf 缺人 → Map.of 拒绝 null → NPE
               (症状是 503「分析失败…: null」,因为 NPE 的 getMessage() 为 null)。 */
            teamRisks.addAll(teamAnalysis(allUsers, tasks, diffByTask, start, end));
            if (onlyUserId != null) {
                // 单人范围:只留与这名成员相关的风险,不把全团队风险混进"只评估了他"的结果
                teamRisks.removeIf(r -> !onlyUserId.equals(toIntOrNull(r.get("user_id"))));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range_start", start.toString());
        result.put("range_end", end.toString());
        result.put("generated_at", LocalDateTime.now().format(TS));
        // 成员画像与团队风险各自的实际引擎:分开标注,避免"engine=llm"掩盖风险部分其实走了规则引擎
        boolean anyMemberLlm = members.stream().anyMatch(m -> "llm".equals(m.get("generated_by")));
        boolean anyLlm = anyMemberLlm || (!useLlm ? false : llmTeam);
        result.put("engine", anyLlm ? "llm" : "rules_fallback");
        result.put("members_engine", anyMemberLlm ? "llm" : "rules_fallback");
        result.put("risks_engine", llmTeam ? "llm" : "rules_fallback");
        // 本次评估范围:team = 全团队(人工触发/送审);member:N = 只评估第 N 号成员(完成任务触发)
        result.put("scope", onlyUserId == null ? "team" : "member:" + onlyUserId);
        result.put("data_sources", List.of("activity_records(手动/导入的活动事实)", "tasks(任务状态与工时)", "difficulty_assessments(难度评估)", "member_profiles(成员画像)"));
        result.put("members", members);
        result.put("team_risks", teamRisks);
        // 本次范围内的任务难度评估(范围生效后可直观察:换范围 → 条目随之变化)
        result.put("difficulty", difficultyOuts);
        return result;
    }

    /** 文档 4.9:时间范围对比分析(本期 vs 上期)。
     *  输出每名成员两个周期的活动量/Review/缺陷修复/完成任务/难度承担对比,
     *  以及预计工时(任务分配工时)与完成率;实际完成工时缺数据源时如实说明。 */
    public Map<String, Object> comparePeriods(String projectId, LocalDate prevStart, LocalDate prevEnd,
                                              LocalDate curStart, LocalDate curEnd) {
        validateRange(prevStart, prevEnd);   // M2
        validateRange(curStart, curEnd);     // M2
        List<User> users = userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"));
        List<Task> tasks = taskMapper.selectList(new QueryWrapper<Task>().eq("project_id", ProjectAccessService.normalize(projectId)));
        Map<String, ProfileAgentDtos.DifficultyOut> diffByTask = new HashMap<>();
        for (ProfileAgentDtos.DifficultyOut d : assessDifficulty(projectId, null, null, false)) {  // H2: 只读,不调 LLM
            diffByTask.put(d.taskId(), d);
        }

        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> prev = periodStats(u, tasks, diffByTask, prevStart, prevEnd);
            Map<String, Object> cur = periodStats(u, tasks, diffByTask, curStart, curEnd);

            List<String> changes = new ArrayList<>();
            diffStat(changes, "活动量", prev, cur, "total_activities");
            diffStat(changes, "Review 参与", prev, cur, "review_count");
            diffStat(changes, "缺陷修复", prev, cur, "bugfix_count");
            diffStat(changes, "测试活动", prev, cur, "test_count");
            diffStat(changes, "完成任务", prev, cur, "task_done_count");
            diffStat(changes, "未关联活动", prev, cur, "unlinked_count");
            diffStat(changes, "高难度任务承担", prev, cur, "high_difficulty_count");
            if (changes.isEmpty()) changes.add("两个周期指标基本持平");

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("user_id", u.getId());
            c.put("display_name", u.getDisplayName());
            c.put("previous_period", prev);
            c.put("current_period", cur);
            c.put("changes", changes);
            comparisons.add(c);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previous_range", prevStart + " ~ " + prevEnd);
        result.put("current_range", curStart + " ~ " + curEnd);
        result.put("generated_at", LocalDateTime.now().format(TS));
        result.put("comparisons", comparisons);
        return result;
    }

    /** 单个周期的可量化指标(事实层,含完成率与预计工时口径说明) */
    private Map<String, Object> periodStats(User u, List<Task> tasks,
                                            Map<String, ProfileAgentDtos.DifficultyOut> diffByTask,
                                            LocalDate start, LocalDate end) {
        List<ActivityRecord> acts = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .eq("user_id", u.getId())
                .ge("happened_at", start.atStartOfDay())
                .le("happened_at", end.atTime(23, 59, 59)));
        int commits = 0, reviews = 0, bugfixes = 0, taskDone = 0, unlinked = 0, tests = 0;
        for (ActivityRecord r : acts) {
            switch (r.getActivityType()) {
                case "commit" -> commits++;
                case "review" -> reviews++;
                case "bugfix" -> bugfixes++;
                case "task_done" -> taskDone++;
                case "test" -> tests++;   // 4.2/4.9 测试活动(质量结果对比的一维)
            }
            if (r.getTaskId() == null || r.getTaskId().isBlank()) unlinked++;
        }
        List<Task> myTasks = tasks.stream().filter(t -> u.getId().equals(t.getOwnerId())).toList();
        int highCount = 0, assignedHours = 0, doneNow = 0;
        for (Task t : myTasks) {
            assignedHours += t.getHours() == null ? 0 : t.getHours();
            if (t.getStatus() != null && t.getStatus() == 2) doneNow++;
            ProfileAgentDtos.DifficultyOut d = diffByTask.get(t.getId());
            if (d != null && ("high".equals(d.level()) || "extreme".equals(d.level()))) highCount++;
        }
        int completionRate = myTasks.isEmpty() ? 0 : Math.round(doneNow * 100.0f / myTasks.size());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_activities", acts.size());
        stats.put("commit_count", commits);
        stats.put("review_count", reviews);
        stats.put("bugfix_count", bugfixes);
        stats.put("task_done_count", taskDone);
        stats.put("unlinked_count", unlinked);
        stats.put("test_count", tests);   // 4.9 测试活动量(质量结果对比)
        stats.put("high_difficulty_count", highCount);
        stats.put("assigned_hours", assignedHours);   // 预计工时(任务分配工时口径)
        stats.put("completion_rate_percent", completionRate);   // 完成任务数/承担任务数
        stats.put("actual_hours", "无实际工时数据源,暂无法对比(数据不足时如实说明)");
        return stats;
    }

    /** 生成「X 由 A 变为 B」的变化描述 */
    private void diffStat(List<String> changes, String label, Map<String, Object> prev,
                          Map<String, Object> cur, String key) {
        Object p = prev.get(key), c = cur.get(key);
        if (p == null || c == null) return;
        String ps = String.valueOf(p), cs = String.valueOf(c);
        if (ps.equals(cs)) return;
        changes.add(label + " " + ps + " → " + cs);
    }

    private Map<String, Object> memberAnalysis(String projectId, User u, List<Task> tasks,
                                               Map<String, ProfileAgentDtos.DifficultyOut> diffByTask,
                                               LocalDate start, LocalDate end, boolean useLlm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getId());
        m.put("display_name", u.getDisplayName());
        m.put("role", u.getRole());

        // --- 客观事实层(直接来自数据) ---
        List<ActivityRecord> acts = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .eq("user_id", u.getId())
                .ge("happened_at", start.atStartOfDay())
                .le("happened_at", end.atTime(23, 59, 59))
                .orderByDesc("happened_at"));

        Map<String, Integer> typeCount = new LinkedHashMap<>();
        for (String t : ACTIVITY_TYPES) typeCount.put(t, 0);
        List<String> workItems = new ArrayList<>();
        List<String> unlinkedCommits = new ArrayList<>();
        Map<String, Integer> moduleCount = new HashMap<>();
        Map<String, Integer> bugfixByTask = new HashMap<>();   // 返工提示:同任务多次缺陷修复
        Map<String, Integer> commitByTask = new HashMap<>();
        for (ActivityRecord r : acts) {
            typeCount.merge(r.getActivityType(), 1, Integer::sum);
            String item = "[" + r.getActivityType() + "] " + r.getTitle()
                    + (r.getModule() == null || r.getModule().isEmpty() ? "" : "(模块:" + r.getModule() + ")");
            workItems.add(item);
            if (r.getTaskId() == null || r.getTaskId().isBlank()) {
                unlinkedCommits.add("[" + r.getActivityType() + "] " + r.getTitle());
            } else {
                if ("bugfix".equals(r.getActivityType())) bugfixByTask.merge(r.getTaskId(), 1, Integer::sum);
                if ("commit".equals(r.getActivityType())) commitByTask.merge(r.getTaskId(), 1, Integer::sum);
            }
            if (r.getModule() != null && !r.getModule().isEmpty()) {
                moduleCount.merge(r.getModule(), 1, Integer::sum);
            }
        }

        List<Task> myTasks = tasks.stream()
                .filter(t -> u.getId().equals(t.getOwnerId()))
                .toList();
        // 协作贡献(文档 4.4):参与他人任务的 Review 次数与对象
        Map<String, Integer> reviewOthersByTask = new LinkedHashMap<>();
        for (ActivityRecord r : acts) {
            if (!"review".equals(r.getActivityType())) continue;
            if (r.getTaskId() == null || r.getTaskId().isBlank()) continue;
            Task t = tasks.stream().filter(x -> x.getId().equals(r.getTaskId())).findFirst().orElse(null);
            if (t != null && !u.getId().equals(t.getOwnerId())) {
                reviewOthersByTask.merge(r.getTaskId(), 1, Integer::sum);
            }
        }
        String collaboration;
        if (reviewOthersByTask.isEmpty()) {
            collaboration = "范围内未参与他人任务的 Review(仅统计关联到任务的 review 活动)";
        } else {
            collaboration = "参与 " + reviewOthersByTask.size() + " 个他人任务的 Review 共 " +
                    reviewOthersByTask.values().stream().mapToInt(Integer::intValue).sum() + " 次(" +
                    String.join("、", reviewOthersByTask.keySet()) + ")";
        }
        List<Map<String, Object>> taskFacts = new ArrayList<>();
        int doneCount = 0, highDifficultyCount = 0, assignedHours = 0;
        List<String> possiblyLate = new ArrayList<>();   // 计划结束周已过但仍未完成
        int projWeek = currentProjectWeek();   // week_end 是项目相对周(1..6),不能用 ISO 日历周比较
        for (Task t : myTasks) {
            Map<String, Object> tf = new LinkedHashMap<>();
            tf.put("task_id", t.getId());
            tf.put("name", t.getName());
            tf.put("status", statusText(t.getStatus()));
            tf.put("hours", t.getHours());
            assignedHours += t.getHours() == null ? 0 : t.getHours();
            if (t.getStatus() != null && t.getStatus() == 2) doneCount++;
            if (projWeek > 0 && t.getStatus() != null && (t.getStatus() == 0 || t.getStatus() == 1)
                    && t.getWeekEnd() != null && t.getWeekEnd() < projWeek) {
                possiblyLate.add(t.getId());
            }
            ProfileAgentDtos.DifficultyOut d = diffByTask.get(t.getId());
            if (d != null) {
                tf.put("difficulty", d.level());
                if ("high".equals(d.level()) || "extreme".equals(d.level())) highDifficultyCount++;
            }
            taskFacts.add(tf);
        }

        // 返工提示:同一任务反复出现缺陷修复活动
        List<String> reworkFlags = bugfixByTask.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(e -> "任务 " + e.getKey() + " 在范围内有 " + e.getValue() + " 次缺陷修复活动,可能属于同一问题反复修改")
                .toList();
        // 按时交付:已完成任务在范围内有关联活动即视为有交付证据(实际完成日期缺省时如实说明)
        int reviewCount = typeCount.getOrDefault("review", 0);
        int bugfixCount = typeCount.getOrDefault("bugfix", 0);

        // ---- 设计文档 4.3/4.4/4.6 补齐 ----
        // 4.4 跨模块任务:范围内活动涉及的模块数与模块列表
        List<String> crossModules = new ArrayList<>(moduleCount.keySet());
        // 4.3 王五场景:进行中的任务在范围内近 7 天无任何活动(以范围结束日为基准)
        LocalDate inactiveCut = end.minusDays(7);
        List<String> longInactiveTasks = new ArrayList<>();
        // 4.6 Review 缺失(成员级):承担的高难度任务范围内无 review 活动
        List<String> missingReviewTasks = new ArrayList<>();
        // 4.6/4.8 测试缺失(成员级):完成/进行中的任务范围内无 test 活动记录
        List<String> missingTestTasks = new ArrayList<>();
        for (Task t : myTasks) {
            if (t.getStatus() != null && t.getStatus() == 1) {
                boolean hasRecent = acts.stream().anyMatch(r -> t.getId().equals(r.getTaskId())
                        && r.getHappenedAt() != null && !r.getHappenedAt().toLocalDate().isBefore(inactiveCut));
                if (!hasRecent) longInactiveTasks.add(t.getId());
            }
            if (t.getStatus() != null && t.getStatus() < 2) {
                ProfileAgentDtos.DifficultyOut d = diffByTask.get(t.getId());
                if (d != null && ("high".equals(d.level()) || "extreme".equals(d.level()))) {
                    boolean hasReview = acts.stream().anyMatch(r -> t.getId().equals(r.getTaskId())
                            && "review".equals(r.getActivityType()));
                    if (!hasReview) missingReviewTasks.add(t.getId());
                }
            }
            if (t.getStatus() != null && (t.getStatus() == 1 || t.getStatus() == 2)) {
                boolean hasTest = acts.stream().anyMatch(r -> t.getId().equals(r.getTaskId())
                        && "test".equals(r.getActivityType()));
                if (!hasTest) missingTestTasks.add(t.getId());
            }
        }
        // 4.6 多人重复修改同一问题:范围内同一任务被多名成员提交代码/修复
        List<String> multiMemberEdits = new ArrayList<>();
        if (!myTasks.isEmpty()) {
            List<ActivityRecord> taskActs = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                    .in("task_id", myTasks.stream().map(Task::getId).toList())
                    .in("activity_type", List.of("commit", "bugfix")));
            Map<String, Set<Integer>> editorsByTask = new HashMap<>();
            for (ActivityRecord r : taskActs) {
                editorsByTask.computeIfAbsent(r.getTaskId(), k -> new java.util.HashSet<>()).add(r.getUserId());
            }
            for (Map.Entry<String, Set<Integer>> e : editorsByTask.entrySet()) {
                if (e.getValue().size() >= 2) {
                    multiMemberEdits.add("任务 " + e.getKey() + " 在范围内有 " + e.getValue().size()
                            + " 名成员提交过代码/修复,可能存在重复修改或协作边界不清(文档 4.6)");
                }
            }
        }

        // --- 提交异常模式扩展(文档 4.6) ---
        // 1) 多次提交修改相似内容:同标题未关联活动重复出现 ≥2 次
        List<String> similarRepeats = new ArrayList<>();
        Map<String, Integer> titleCount = new HashMap<>();
        for (ActivityRecord r : acts) {
            if (r.getTaskId() == null || r.getTaskId().isBlank()) {
                titleCount.merge(r.getTitle() == null ? "" : r.getTitle(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : titleCount.entrySet()) {
            if (e.getValue() >= 2 && !e.getKey().isEmpty()) {
                similarRepeats.add("近段时间有 " + e.getValue() + " 条未关联任务的提交标题均为「" + e.getKey() + "」,可能属于调试、返工或任务拆分问题");
            }
        }
        // 2) 提交信息不清晰:标题过短(≤3 字)或为泛化占位词
        List<String> vagueWords = List.of("修改", "更新", "调试", "修复", "fix", "update", "wip");
        List<String> unclearMessages = new ArrayList<>();
        for (ActivityRecord r : acts) {
            String t = r.getTitle() == null ? "" : r.getTitle().trim();
            if (t.length() <= 3 || vagueWords.contains(t.toLowerCase())) {
                unclearMessages.add("[" + r.getActivityType() + "] " + (t.isEmpty() ? "(空标题)" : t));
            }
        }
        // 3) 任务已完成但没有对应代码活动:范围内 status=2 的任务无任何关联活动记录
        List<String> doneWithoutActivity = new ArrayList<>();
        for (Task t : myTasks) {
            if (t.getStatus() != null && t.getStatus() == 2) {
                boolean hasAct = acts.stream().anyMatch(r -> t.getId().equals(r.getTaskId()));
                if (!hasAct) {
                    doneWithoutActivity.add(t.getId() + " " + t.getName());
                }
            }
        }

        // 交付及时性:已关联活动与任务完成情况
        Map<String, Object> objective = new LinkedHashMap<>();
        objective.put("period_activity_counts", typeCount);
        objective.put("work_items", workItems);
        objective.put("total_activities", acts.size());
        objective.put("tasks_owned", taskFacts);
        objective.put("tasks_done", doneCount);
        objective.put("high_difficulty_tasks", highDifficultyCount);
        objective.put("assigned_hours", assignedHours);
        objective.put("modules_touched", moduleCount.keySet().stream().toList());
        objective.put("unlinked_activities", unlinkedCommits);
        objective.put("review_participation", reviewCount);
        objective.put("bugfix_count", bugfixCount);
        objective.put("collaboration_review", collaboration);   // 文档 4.4 协作贡献
        objective.put("rework_flags", reworkFlags);
        objective.put("possibly_late_tasks", possiblyLate);
        objective.put("similar_repeated_commits", similarRepeats);
        objective.put("unclear_commit_messages", unclearMessages);
        objective.put("done_tasks_without_activity", doneWithoutActivity);
        objective.put("cross_module_modules", crossModules);          // 4.4 跨模块任务
        objective.put("cross_module_count", crossModules.size());
        objective.put("long_inactive_tasks", longInactiveTasks);      // 4.3 进行中无进展
        objective.put("missing_review_tasks", missingReviewTasks);    // 4.6 Review 缺失
        objective.put("missing_test_tasks", missingTestTasks);        // 4.6/4.8 测试缺失
        objective.put("multi_member_edits", multiMemberEdits);        // 4.6 多人重复修改
        m.put("objective", objective);

        // --- AI 推断层(带依据,明确标注为推断) ---
        List<String> inference = new ArrayList<>();
        String mainModule = moduleCount.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue))
                .map(Map.Entry::getKey).orElse(null);
        if (mainModule != null) {
            inference.add("近期主要承担「" + mainModule + "」相关工作(依据:该模块活动 " +
                    moduleCount.get(mainModule) + " 次,为各模块最多)");
        }
        if (!unlinkedCommits.isEmpty()) {
            inference.add("有 " + unlinkedCommits.size() + " 条活动未关联明确任务,可能属于调试、返工或任务拆分问题,建议项目经理进一步确认(文档 4.6 提交异常模式)");
        }
        reworkFlags.forEach(inference::add);
        similarRepeats.forEach(inference::add);
        if (!unclearMessages.isEmpty()) {
            inference.add("有 " + unclearMessages.size() + " 条提交信息不清晰(标题过短或为泛化占位词),建议规范提交信息(依据:标题长度/内容检测)");
        }
        if (!doneWithoutActivity.isEmpty()) {
            inference.add("任务 " + String.join("、", doneWithoutActivity) + " 已标记完成但范围内无对应代码活动,状态与代码活动可能不一致,建议确认(文档 4.6)");
        }
        if (!longInactiveTasks.isEmpty()) {
            inference.add("任务 " + String.join("、", longInactiveTasks) + " 处于进行中但范围内近 7 天无活动,可能存在阻塞或任务拆分不清,建议确认(文档 4.3 王五场景)");
        }
        if (!missingReviewTasks.isEmpty()) {
            inference.add("高难度任务 " + String.join("、", missingReviewTasks) + " 范围内无 Review 活动,建议安排同行评审(文档 4.6/4.8)");
        }
        if (!missingTestTasks.isEmpty()) {
            inference.add("任务 " + String.join("、", missingTestTasks) + " 完成/进行中但范围内无测试活动记录,可能缺少测试或未录入(文档 4.6/4.8)");
        }
        if (!multiMemberEdits.isEmpty()) {
            multiMemberEdits.forEach(inference::add);
        }
        if (crossModules.size() >= 3) {
            inference.add("范围内活动涉及 " + crossModules.size() + " 个模块(" + String.join("、", crossModules)
                    + "),承担跨模块任务,建议确认资源与沟通成本(文档 4.4)");
        }
        if (!possiblyLate.isEmpty()) {
            inference.add("任务 " + String.join("、", possiblyLate) + " 计划结束周已过但仍未完成,存在延期风险(依据:tasks 的 week_end 与当前周对比)");
        }
        if (acts.isEmpty() && doneCount == 0) {
            inference.add("该时间范围内暂无足够数据,无法确认实际完成内容,需要成员补充说明");
        }
        int loadPct = loadPercent(u, myTasks);
        m.put("current_load_percent", loadPct);

        // ---- 规则引擎完整画像(9 字段):既是 LLM 不可用时的降级结果,也是 LLM 路径的字段兜底 ----
        Map<String, Object> portrait = new LinkedHashMap<>();
        MemberProfile mp = memberProfileMapper.selectOne(
                new QueryWrapper<MemberProfile>().eq("user_id", u.getId()).last("LIMIT 1"));
        portrait.put("tech_stack", mp == null ? List.of() : readJsonList(mp.getTechStack()));
        portrait.put("good_at", mainModule == null ? "暂无足够数据" : mainModule + " 相关工作(近 " + daysBetween(start, end) + " 天活动归纳)");
        portrait.put("difficulty_capacity", highDifficultyCount >= 2 ? "较高(当前承担 " + highDifficultyCount + " 项高难度任务)" :
                highDifficultyCount == 1 ? "中等(当前承担 1 项高难度任务)" : "待观察(暂无高难度任务记录)");
        portrait.put("delivery_timeliness", doneCount == 0 ? "暂无已完成任务可评估" :
                "已交付 " + doneCount + " 项任务(依据:tasks 状态,与活动记录交叉印证)");
        // 4.7 画像维度扩展:代码稳定性/返工率/缺陷修复能力/Review 参与度(证据化描述,非评判)
        portrait.put("code_stability", reworkFlags.isEmpty() && bugfixCount <= 1 ?
                "较高(范围内返工/缺陷记录少)" : reworkFlags.size() >= 2 ?
                "待观察(存在 " + reworkFlags.size() + " 个返工信号)" : "中等(有少量返工/缺陷记录)");
        portrait.put("rework_rate", bugfixCount == 0 ? "无缺陷修复记录" :
                "范围内 " + reworkFlags.size() + " 个任务出现多次修复,共 " + bugfixCount + " 次缺陷修复活动");
        portrait.put("defect_fix_capability", bugfixCount >= 3 ? "较强(修复 " + bugfixCount + " 个缺陷)" :
                bugfixCount >= 1 ? "一般(修复 " + bugfixCount + " 个缺陷)" : "暂无缺陷修复记录");
        portrait.put("review_participation", reviewCount >= 3 ? "较高(参与 " + reviewCount + " 次 Review)" :
                reviewCount >= 1 ? "一般(参与 " + reviewCount + " 次 Review)" : "暂无 Review 记录");
        portrait.put("current_load_percent", loadPct);
        portrait.put("risk_flags", loadPct >= 90 ? "负载过高,可能影响 Sprint 里程碑" :
                loadPct >= 70 ? "负载偏高,建议关注" : "正常");
        portrait.put("recommended_task_types", buildRecommendations(mp, mainModule));

        // ---- LLM 内核优先:成员分析由大脑(推理+工具+记忆)生成;失败/未配置/预览模式 → 规则引擎降级 ----
        Map<String, Object> brainOut = useLlm ? brain.memberAnalysis(projectId, u.getId(), objective) : null;
        if (brainOut != null) {
            @SuppressWarnings("unchecked")
            List<String> llmInference = (List<String>) brainOut.get("ai_inference");
            m.put("ai_inference", llmInference);
            @SuppressWarnings("unchecked")
            Map<String, Object> llmPortrait = (Map<String, Object>) brainOut.get("dynamic_profile");
            /* 只覆盖 LLM 真正给出的白名单字段,缺失字段保留上面规则引擎算出的完整值。
               ProfileAgentBrain 只保留 LLM 实际输出且非空的字段,若像原先那样整体替换,
               前端「动态能力画像」逐字段直取(ProfileAgentPanel「动态能力画像」9 行)会把缺失项
               渲染成字面量 undefined;原实现 llmPortrait 为 null 时 putIfAbsent 还会直接 NPE。 */
            if (llmPortrait != null) {
                for (Map.Entry<String, Object> e : llmPortrait.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if ("recommended_task_types".equals(e.getKey())) {
                        /* 前端用 recommended_task_types.join 渲染:LLM 把它输出成字符串时
                           必须归一成列表,否则前端会因 .join 不是函数而整块渲染失败 */
                        if (v instanceof List<?> list) portrait.put(e.getKey(), list);
                        else if (v instanceof String s && !s.isBlank()) portrait.put(e.getKey(), List.of(s));
                        continue;
                    }
                    portrait.put(e.getKey(), v);
                }
            }
            portrait.put("current_load_percent", loadPct);   // 负载是系统算术值,以系统为准
            m.put("dynamic_profile", portrait);
            m.put("generated_by", "llm");
            m.put("confidence", brainOut.get("confidence"));
            m.put("evidence_refs", brainOut.get("evidence_refs"));
            return m;
        }

        // ---- 规则引擎降级备胎(原模板推断) ----
        m.put("ai_inference", inference);
        m.put("dynamic_profile", portrait);
        m.put("generated_by", "rules_fallback");
        // 规则引擎路径如实标注:无外部工具调用,依据为系统内事实(与 LLM 路径的 evidence_refs 对齐)
        m.put("confidence", 0.6);
        m.put("evidence_refs", List.of("rules_fallback:规则引擎基于任务状态/活动记录/负载算术推断,无外部工具调用"));
        return m;
    }

    private int loadPercent(User u, List<Task> myTasks) {
        if (u.getCapacityHours() == null) return 0;
        int capacity = Math.max(0, u.getCapacityHours()) * 6;
        int openHours = 0;
        for (Task t : myTasks) {
            if (t.getStatus() != null && (t.getStatus() == 0 || t.getStatus() == 1) && t.getHours() != null) {
                openHours += t.getHours();
            }
        }
        if (capacity == 0) return openHours > 0 ? 100 : 0;
        return Math.min(100, Math.round(openHours * 100.0f / capacity));
    }

    private List<String> buildRecommendations(MemberProfile mp, String mainModule) {
        List<String> rec = new ArrayList<>();
        if (mp != null) {
            for (Object item : readJsonList(mp.getTechStack())) {
                if (item instanceof Map<?, ?> s && s.get("name") != null) {
                    Object level = s.get("level");
                    if (level instanceof Number n && n.intValue() >= 4) {
                        rec.add("「" + s.get("name") + "」相关任务(熟练度 " + n + "/5)");
                    }
                }
            }
        }
        if (rec.isEmpty() && mainModule != null) rec.add("「" + mainModule + "」相关任务(依据近期活动经验)");
        if (rec.isEmpty()) rec.add("暂无足够数据,无法推荐任务类型");
        return rec;
    }

    /**
     * 当前「项目相对周」:tasks.week_start/week_end 是项目第 N 周(1..6),不能用 ISO 日历周直接比较。
     * 锚点 = 最早一条活动记录所在周的周一(记为项目第 1 周);无任何活动数据时返回 -1(数据不足,跳过周次类检查)。
     */
    private int currentProjectWeek() {
        ActivityRecord first = activityMapper.selectOne(new QueryWrapper<ActivityRecord>()
                .orderByAsc("happened_at").last("LIMIT 1"));
        if (first == null || first.getHappenedAt() == null) return -1;
        LocalDate anchor = first.getHappenedAt().toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(anchor, LocalDate.now());
        return (int) weeks + 1;
    }

    /** 按项目取当前项目周(锚点只在该项目内取,避免跨项目活动污染周次) */
    private int currentProjectWeek(String projectId) {
        LocalDate anchor = projectAnchorOrNull(projectId);
        if (anchor == null) return -1;
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(anchor, LocalDate.now());
        return (int) weeks + 1;
    }

    /** 文档 4.8:团队级风险分析 */
    private List<Map<String, Object>> teamAnalysis(List<User> users, List<Task> tasks,
                                                   Map<String, ProfileAgentDtos.DifficultyOut> diffByTask,
                                                   LocalDate start, LocalDate end) {
        List<Map<String, Object>> risks = new ArrayList<>();
        Map<Integer, String> nameOf = new HashMap<>();
        users.forEach(u -> nameOf.put(u.getId(), u.getDisplayName()));

        // 模块/高难度任务集中于一人
        Map<Integer, Integer> openLoad = new HashMap<>();
        for (Task t : tasks) {
            if (t.getOwnerId() == null || (t.getStatus() != null && t.getStatus() >= 2)) continue;
            openLoad.merge(t.getOwnerId(), t.getHours() == null ? 0 : t.getHours(), Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : openLoad.entrySet()) {
            User u = users.stream().filter(x -> x.getId().equals(e.getKey())).findFirst().orElse(null);
            if (u == null) continue;
            int pct = loadPercent(u, tasks.stream().filter(t -> e.getKey().equals(t.getOwnerId())).toList());
            if (pct >= 90) {
                risks.add(Map.of(
                        "kind", "overload",
                        "user_id", e.getKey(),
                        "member", nameOf.get(e.getKey()),
                        "evidence", "未完成任务工时 " + e.getValue() + "h / 容量 " +
                                (u.getCapacityHours() == null ? "未配置" : (u.getCapacityHours() * 6) + "h（六周）"),
                        "inference", "负载约 " + pct + "%,长期过载风险,建议重新分配部分任务",
                        "suggested_action", "将低优先级任务拆分给负载较低的成员,或安排他人参与 Review",
                        "impact", "若不重新分配，该成员承担的任务存在延期风险，Sprint 里程碑与后续联调窗口会被压缩"));
            }
        }
        // 高难度集中于一人 → 关键单点依赖
        Map<Integer, Integer> highCount = new HashMap<>();
        for (Task t : tasks) {
            if (t.getOwnerId() == null) continue;
            ProfileAgentDtos.DifficultyOut d = diffByTask.get(t.getId());
            if (d != null && ("high".equals(d.level()) || "extreme".equals(d.level()))
                    && t.getStatus() != null && t.getStatus() < 2) {
                highCount.merge(t.getOwnerId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<Integer, Integer> e : highCount.entrySet()) {
            if (e.getValue() >= 2) {
                risks.add(Map.of(
                        "kind", "single_point",
                        "user_id", e.getKey(),
                        "member", nameOf.get(e.getKey()),
                        "evidence", "同时承担 " + e.getValue() + " 项未完成的高难度任务",
                        "inference", "存在关键知识单点依赖,该成员请假/阻塞将直接影响里程碑",
                        "suggested_action", "安排其他成员参与相关任务 Review,沉淀关键知识",
                        "impact", "该成员一旦请假或阻塞，高难度任务无人接手，里程碑存在延期风险"));
            }
        }
        // 长期无进展任务(进行中但无近期活动)
        LocalDate cutoff = LocalDate.now().minusDays(7);
        for (Task t : tasks) {
            if (t.getStatus() == null || t.getStatus() != 1 || t.getOwnerId() == null) continue;
            Long recent = activityMapper.selectCount(new QueryWrapper<ActivityRecord>()
                    .eq("task_id", t.getId())
                    .gt("happened_at", cutoff.atStartOfDay()));
            if (recent == 0) {
                risks.add(Map.of(
                        "kind", "stalled",
                        "task_id", t.getId(),
                        "task_name", t.getName(),
                        "member", nameOf.getOrDefault(t.getOwnerId(), "未分配"),
                        "evidence", "任务处于「进行中」状态,但近 7 天无任何关联活动记录",
                        "inference", "可能存在任务阻塞或任务拆分不清的问题,需进一步确认(文档 4.3 王五案例)",
                        "suggested_action", "与负责人确认任务状态;若已阻塞,补充阻塞原因并调整排期",
                        "impact", "任务继续停滞会推迟其后续任务与集成联调，Sprint 交付范围可能缩小"));
            }
        }
        // 高难度任务缺 Review(文档 4.8:哪些任务缺少 Review)
        for (Task t : tasks) {
            if (t.getOwnerId() == null || t.getStatus() == null || t.getStatus() >= 2) continue;
            ProfileAgentDtos.DifficultyOut d = diffByTask.get(t.getId());
            if (d == null || !("high".equals(d.level()) || "extreme".equals(d.level()))) continue;
            Long reviews = activityMapper.selectCount(new QueryWrapper<ActivityRecord>()
                    .eq("task_id", t.getId()).eq("activity_type", "review"));
            if (reviews == 0) {
                risks.add(Map.of(
                        "kind", "missing_review",
                        "task_id", t.getId(),
                        "task_name", t.getName(),
                        "member", nameOf.getOrDefault(t.getOwnerId(), "未分配"),
                        "evidence", "任务为" + ("extreme".equals(d.level()) ? "极高" : "高") +
                                "难度(" + d.score() + "分),且无任何 Review 活动记录",
                        "inference", "高难度/核心任务缺少同行评审,质量问题可能在后期集中爆发",
                        "suggested_action", "安排其他成员对该任务进行 Review,并补测关键路径",
                        "impact", "缺陷留到联调或发布前才暴露，返工成本高于当前评审成本"));
            }
        }
        // 任务缺测试活动记录(文档 4.8:哪些任务缺少测试;证据为 test 类型活动)
        for (Task t : tasks) {
            if (t.getOwnerId() == null || t.getStatus() == null
                    || (t.getStatus() != 1 && t.getStatus() != 2)) continue;
            Long tests = activityMapper.selectCount(new QueryWrapper<ActivityRecord>()
                    .eq("task_id", t.getId()).eq("activity_type", "test"));
            if (tests == 0) {
                risks.add(Map.of(
                        "kind", "missing_test",
                        "task_id", t.getId(),
                        "task_name", t.getName(),
                        "member", nameOf.getOrDefault(t.getOwnerId(), "未分配"),
                        "evidence", "任务处于「" + statusText(t.getStatus()) + "」状态,但范围内无任何测试活动记录",
                        "inference", "可能缺少测试或测试活动未录入,质量问题可能后置暴露",
                        "suggested_action", "确认任务是否已补充测试;未录入的测试活动请补充录入",
                        "impact", "无测试记录无法确认功能可用，发布前回归可能失败，存在带缺陷发布风险"));
            }
        }
        // 模块工作集中在少数人(文档 4.8:哪个模块的工作集中在一个人身上)
        Map<String, Map<Integer, Integer>> moduleByUser = new LinkedHashMap<>();
        Map<String, Integer> moduleTotal = new HashMap<>();
        List<ActivityRecord> rangeActs = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .ge("happened_at", start.atStartOfDay())
                .le("happened_at", end.atTime(23, 59, 59)));
        for (ActivityRecord r : rangeActs) {
            if (r.getModule() == null || r.getModule().isBlank()) continue;
            moduleTotal.merge(r.getModule(), 1, Integer::sum);
            moduleByUser.computeIfAbsent(r.getModule(), k -> new HashMap<>())
                    .merge(r.getUserId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Map<Integer, Integer>> e : moduleByUser.entrySet()) {
            String mod = e.getKey();
            int total = moduleTotal.getOrDefault(mod, 0);
            if (total < 3) continue;   // 活动太少不构成集中度证据
            int maxUid = -1, maxCnt = 0;
            for (Map.Entry<Integer, Integer> ue : e.getValue().entrySet()) {
                if (ue.getValue() > maxCnt) { maxCnt = ue.getValue(); maxUid = ue.getKey(); }
            }
            if (maxCnt * 10 >= total * 6) {   // 单人占比 ≥60%
                risks.add(Map.of(
                        "kind", "module_concentration",
                        "user_id", maxUid,
                        "member", nameOf.getOrDefault(maxUid, "成员" + maxUid),
                        "evidence", "模块「" + mod + "」范围内 " + total + " 条活动中 " + maxCnt
                                + " 条来自同一成员(" + Math.round(maxCnt * 100.0 / total) + "%)",
                        "inference", "该模块知识/工作高度集中在少数人,存在关键单点依赖风险",
                        "suggested_action", "安排其他成员参与该模块任务或 Review,分散知识集中度",
                        "impact", "知识集中在该成员，人员变动会导致模块维护与排期风险"));
            }
        }
        // 本周应结束未完成(文档 4.8:延期是否影响里程碑);week_end 是项目相对周,需换算后比较
        int currentWeek = currentProjectWeek();
        if (currentWeek > 0) {
            for (Task t : tasks) {
                if (t.getOwnerId() == null || t.getStatus() == null || t.getStatus() >= 2) continue;
                if (t.getWeekEnd() == null || t.getWeekEnd() > currentWeek) continue;
                risks.add(Map.of(
                        "kind", "milestone_risk",
                        "task_id", t.getId(),
                        "task_name", t.getName(),
                        "member", nameOf.getOrDefault(t.getOwnerId(), "未分配"),
                        "evidence", "任务计划在项目第 " + t.getWeekEnd() + " 周结束(当前项目第 " + currentWeek + " 周),仍未完成",
                        "inference", "临近/超过计划截止周仍未完成,可能影响 Sprint 与里程碑交付",
                        "suggested_action", "确认阻塞原因并调整排期;评估是否拆分任务或增加人手",
                        "impact", "任务已超过计划结束周，可能影响 Sprint 交付与里程碑验收"));
            }
        }
        return risks;
    }

    // ---------- 运行记录与快照 ----------

    /** 触发一次分析:同步执行并留痕(小数据量同步即可;失败落库可重试) */
    public Map<String, Object> runAnalysis(LocalDate start, LocalDate end, User user) {
        throw ApiException.badRequest("项目级画像分析必须提供 projectId");
    }

    /**
     * 触发一次分析(带范围):onlyUserId 非空时只评估这一名成员。
     * 成员完成任务后前端只传提交者 —— 每次完成任务的 LLM 成本从「5 次成员画像 + 1 次团队风险」
     * 降到「1 次成员画像」,且只对提交者的提交物做评估。
     */
    public Map<String, Object> runAnalysis(String projectId, LocalDate start, LocalDate end, User user, Integer onlyUserId) {
        assertWithinRateLimit(user.getId(), onlyUserId);
        ProfileAgentRun run = newRun(projectId, start, end, user.getId(), onlyUserId);
        run.setStatus("running");   // 同步入口:建 run 的这一步就已经在跑了
        runMapper.insert(run);
        return execute(run);
    }

    /**
     * 只建 run、<b>不执行</b>(S5′ 触发型工具 {@code start_profile_analysis} 的入口)。
     *
     * <p>为什么必须拆出这条路径:编排层的有界循环有 180s 总预算,而一次全团队画像分析
     * 是 6 次 LLM 调用 —— 同步跑会把预算吃穿。触发型工具的契约是"只创建 run 就返回",
     * 真正的执行交给后台消费者 {@code ProfileWorkerTask}。
     *
     * <p>限流放在<b>入队</b>而不是执行时:限流保护的是"用户狂点",那是入队侧的压力;
     * 执行侧再限一次会让排队中的 run 被自己限死。
     */
    public ProfileAgentRun enqueue(String projectId, LocalDate start, LocalDate end, User user, Integer onlyUserId) {
        validateRange(start, end);   // M2: 范围不合法要在入队时就拒绝,别等消费者跑起来才失败
        assertWithinRateLimit(user.getId(), onlyUserId);
        ProfileAgentRun run = newRun(projectId, start, end, user.getId(), onlyUserId);
        run.setStatus("queued");
        runMapper.insert(run);
        return run;
    }

    /**
     * 执行一条已存在的 run 并把终局状态落库(同步入口与后台消费者共用)。
     *
     * <p>共用这一处的理由:快照落库、observability 录制、失败时的分类文案这三件事
     * 只要有两份实现,就一定会有一份漏掉"失败也要 reset 录制"这类补丁。
     */
    public Map<String, Object> executeRun(ProfileAgentRun run) {
        run.setStatus("running");
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return execute(run);
    }

    /** 建一条未执行的 run 对象(不含 status,由调用方按"同步/入队"决定) */
    private ProfileAgentRun newRun(String projectId, LocalDate start, LocalDate end, Integer userId, Integer onlyUserId) {
        ProfileAgentRun run = new ProfileAgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setProjectId(ProjectAccessService.normalize(projectId));
        run.setRangeStart(start);
        run.setRangeEnd(end);
        run.setRequestedBy(userId);
        run.setOnlyUserId(onlyUserId);
        run.setAttempt(1 + previousAttempt(userId, start, end));   // M5: attempt 递增
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }

    /** 限流:两条通道分开计数,理由见 {@link #runAnalysis} 上的 M4 注释 */
    private void assertWithinRateLimit(Integer userId, Integer onlyUserId) {
        long now = System.currentTimeMillis();
        boolean scoped = onlyUserId != null;
        String limitKey = (scoped ? "scoped:" : "team:") + userId;
        long limitMs = scoped ? SCOPED_RUN_DEBOUNCE_MS : RUN_RATE_LIMIT_MS;
        Long last = lastRunByUser.get(limitKey);
        if (last != null && now - last < limitMs) {
            double waitSec = Math.ceil((limitMs - (now - last)) / 1000.0);
            throw ApiException.unprocessable("分析请求过于频繁,请 " + (long) waitSec + " 秒后再试");
        }
        lastRunByUser.put(limitKey, now);
    }

    /** 执行本体:分析 → 快照落库 → 终局状态;参数全部来自 run,便于跨进程交接 */
    private Map<String, Object> execute(ProfileAgentRun run) {
        String projectId = run.getProjectId();
        LocalDate start = run.getRangeStart();
        LocalDate end = run.getRangeEnd();
        Integer onlyUserId = run.getOnlyUserId();
        brain.startRecording();
        try {
            // 可观测:录制本轮分析的每步 thought/action/observation/tokens(验收标准 3:决策可回溯)
            Map<String, Object> result = analyze(projectId, start, end, true, onlyUserId);
            Map<String, Object> observability = brain.collectSteps();
            result.put("observability", observability);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
            for (Map<String, Object> m : members) {
                saveSnapshot((Integer) m.get("user_id"), start, end, m,
                        String.valueOf(m.getOrDefault("generated_by", "ai")));   // H6: 快照来源标注实际引擎
            }
            run.setStatus("succeeded");
            run.setResultJson(writeJson(Map.of(
                    "member_count", members.size(),
                    "risk_count", ((List<?>) result.get("team_risks")).size(),
                    "engine", result.get("engine"),
                    "scope", result.get("scope"),   // team / member:N —— 运行记录里能看出这次评估了谁
                    "observability", observability)));
            runMapper.updateById(run);
            return result;
        } catch (Exception e) {
            // H4 修复:无论成功失败都复位录制状态,防止 ThreadLocal 泄漏到线程池复用线程
            try { brain.collectSteps(); } catch (Exception ignore) { }
            // 内部异常文案可能带表名/约束名/片段 SQL:原始异常只进服务端日志,
            // 落库与回显用分类文案(与 GlobalExceptionHandler:98-103 同一条原则)
            log.warn("画像分析失败", e);
            run.setStatus("failed");
            run.setErrorMessage("分析失败，运行记录已留痕，可重试");
            runMapper.updateById(run);
            throw ApiException.server("分析失败，运行记录已留痕，可重试");
        }
    }

    /** 同用户同范围的上一次 attempt(重试时递增) */
    private int previousAttempt(Integer userId, LocalDate start, LocalDate end) {
        List<ProfileAgentRun> list = runMapper.selectList(new QueryWrapper<ProfileAgentRun>()
                .eq("requested_by", userId)
                .eq("range_start", start)
                .eq("range_end", end)
                .orderByDesc("created_at").last("LIMIT 1"));
        if (list.isEmpty()) return 0;
        Integer a = list.get(0).getAttempt();
        return a == null ? 0 : a;
    }

    public List<ProfileAgentDtos.RunOut> listRuns() {
        return runMapper.selectList(new QueryWrapper<ProfileAgentRun>().orderByDesc("created_at").last("LIMIT 20"))
                .stream().map(r -> new ProfileAgentDtos.RunOut(r.getId(), r.getRangeStart(), r.getRangeEnd(),
                        r.getStatus(), r.getAttempt(), r.getErrorMessage(),
                        r.getCreatedAt() == null ? null : r.getCreatedAt().format(TS)))
                .toList();
    }

    private void saveSnapshot(Integer userId, LocalDate start, LocalDate end, Map<String, Object> payload, String by) {
        ProfileSnapshot s = new ProfileSnapshot();
        s.setUserId(userId);
        s.setRangeStart(start);
        s.setRangeEnd(end);
        // 4.7 修复:快照存完整成员分析(客观事实 objective + AI 推断 + 画像 + evidence_refs),
        // 使画像趋势可回溯到"对应任务和代码证据";dynamic_profile 字段同时提升到顶层,
        // 兼容旧版快照的读取方式(旧快照 payload 只有顶层画像字段)。
        Map<String, Object> full = new LinkedHashMap<>(payload);
        Object dp = payload.get("dynamic_profile");
        if (dp instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                full.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
            }
        }
        s.setPayloadJson(writeJson(full));
        s.setGeneratedBy(by);
        s.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(s);
    }

    public List<Map<String, Object>> snapshots(Integer userId) {
        QueryWrapper<ProfileSnapshot> qw = new QueryWrapper<>();
        if (userId != null) qw.eq("user_id", userId);
        qw.orderByDesc("id").last("LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProfileSnapshot s : snapshotMapper.selectList(qw)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("snapshot_id", s.getId());
            o.put("user_id", s.getUserId());
            o.put("range_start", s.getRangeStart().toString());
            o.put("range_end", s.getRangeEnd().toString());
            o.put("payload", readJsonMap(s.getPayloadJson()));
            o.put("generated_by", s.getGeneratedBy());
            o.put("created_at", s.getCreatedAt() == null ? null : s.getCreatedAt().format(TS));
            out.add(o);
        }
        return out;
    }

    /** 文档 4.7:画像快照趋势——相邻快照对比,给出「画像变化原因」(能力趋势,随时间更新而非固定标签) */
    public List<Map<String, Object>> snapshotTrend(Integer userId) {
        List<ProfileSnapshot> snaps = snapshotMapper.selectList(new QueryWrapper<ProfileSnapshot>()
                .eq(userId != null, "user_id", userId)
                .orderByAsc("range_start", "id"));
        Map<Integer, String> nameOf = new HashMap<>();
        userMapper.selectList(null).forEach(u -> nameOf.put(u.getId(), u.getDisplayName()));

        List<Map<String, Object>> trends = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            ProfileSnapshot s = snaps.get(i);
            Map<String, Object> payload = readJsonMap(s.getPayloadJson());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_id", s.getUserId());
            row.put("display_name", nameOf.getOrDefault(s.getUserId(), "成员" + s.getUserId()));
            row.put("range_start", s.getRangeStart().toString());
            row.put("range_end", s.getRangeEnd().toString());
            row.put("good_at", payload.getOrDefault("good_at", "暂无足够数据"));
            row.put("risk_flags", payload.getOrDefault("risk_flags", "正常"));
            row.put("load_percent", payload.getOrDefault("current_load_percent", 0));
            // 与上一张快照对比变化原因
            List<String> changes = new ArrayList<>();
            if (i > 0) {
                Map<String, Object> prev = readJsonMap(snaps.get(i - 1).getPayloadJson());
                Object pg = prev.get("good_at"), cg = payload.get("good_at");
                if (pg != null && cg != null && !String.valueOf(pg).equals(String.valueOf(cg))) {
                    changes.add("擅长方向变化: 「" + pg + "」 → 「" + cg + "」(依据:各周期活动模块分布)");
                }
                Object pl = prev.get("current_load_percent"), cl = payload.get("current_load_percent");
                if (pl instanceof Number pn && cl instanceof Number cn) {
                    if (cn.intValue() - pn.intValue() >= 10) {
                        changes.add("负载上升 " + (cn.intValue() - pn.intValue()) + " 个百分点(" + pn + "% → " + cn + "%),注意过载风险");
                    } else if (pn.intValue() - cn.intValue() >= 10) {
                        changes.add("负载回落 " + (pn.intValue() - cn.intValue()) + " 个百分点(" + pn + "% → " + cn + "%)");
                    }
                }
                Object pr = prev.get("risk_flags"), cr = payload.get("risk_flags");
                if (pr != null && cr != null && !String.valueOf(pr).equals(String.valueOf(cr))) {
                    changes.add("风险状态变化: " + pr + " → " + cr);
                }
            } else {
                changes.add("首张画像快照,尚无历史可对比(后续分析将形成趋势)");
            }
            row.put("changes", changes);
            trends.add(row);
        }
        return trends;
    }

    // ---------- 成员纠正机制(文档 4.7:允许成员纠正错误信息) ----------

    /** 提交画像纠正:本人纠正自己的画像,或 admin/owner 代为纠正 */
    public Map<String, Object> addCorrection(Integer profileUserId, String field, String correctedValue,
                                             String reason, User actor) {
        if (!profileUserId.equals(actor.getId()) && !"admin".equals(actor.getRole()) && !"owner".equals(actor.getRole())) {
            throw ApiException.forbidden("只能纠正自己的画像");
        }
        List<String> allowed = List.of("good_at", "recommended_task_types", "difficulty_capacity", "summary", "other");
        if (!allowed.contains(field)) {
            throw ApiException.unprocessable("field 必须是 " + allowed + " 之一");
        }
        if (correctedValue == null || correctedValue.isBlank()) {
            throw ApiException.badRequest("corrected_value 不能为空");
        }
        ProfileCorrection c = new ProfileCorrection();
        c.setUserId(profileUserId);
        c.setField(field);
        c.setCorrectedValue(correctedValue.trim());
        c.setReason(reason == null ? "" : reason.trim());
        c.setCreatedBy(actor.getId());
        c.setCreatedAt(LocalDateTime.now());
        correctionMapper.insert(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("correction_id", c.getId());
        out.put("user_id", profileUserId);
        out.put("field", field);
        out.put("corrected_value", c.getCorrectedValue());
        out.put("reason", c.getReason());
        out.put("created_at", c.getCreatedAt().format(TS));
        return out;
    }

    /** 某成员画像的纠正历史(最新在前);memberAnalysis 输出旁一并展示 */
    public List<Map<String, Object>> corrections(Integer userId) {
        QueryWrapper<ProfileCorrection> qw = new QueryWrapper<>();
        if (userId != null) qw.eq("user_id", userId);
        qw.orderByDesc("id").last("LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProfileCorrection c : correctionMapper.selectList(qw)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("correction_id", c.getId());
            o.put("user_id", c.getUserId());
            o.put("field", c.getField());
            o.put("corrected_value", c.getCorrectedValue());
            o.put("reason", c.getReason());
            o.put("created_by", c.getCreatedBy());
            o.put("created_at", c.getCreatedAt() == null ? null : c.getCreatedAt().format(TS));
            out.add(o);
        }
        return out;
    }

    // ---------- 统一审核中心接入(文档第 5 章) ----------

    /**
     * 把团队风险转成待审建议,进统一 AI 建议审核中心:
     * 复用 suggestions 表(kind=profile),证据=风险事实,影响对象=相关成员/任务,
     * change_json=建议落入需求池的条目内容。人工审核通过后才会写入正式数据。
     */
    public List<String> submitRiskSuggestions(String projectId, LocalDate start, LocalDate end, String agent, User user) {
        // 送审是正式动作:走 runAnalysis(与"触发正式分析"同一条路)——
        // 留 profile_agent_runs 痕迹 + 受 30 秒限流;此前直接 analyze(...) 会绕过限流且事后无记录可查
        Map<String, Object> result = runAnalysis(projectId, start, end, user, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risks = (List<Map<String, Object>>) result.get("team_risks");
        List<String> ids = new ArrayList<>();
        String range = start + " ~ " + end;
        // 去重:同一周期+类型+影响对象已有 pending 建议时跳过,防止重复送审刷出大量重复项
        java.util.Set<String> existingKeys = new java.util.HashSet<>();
        for (Suggestion s : suggestionMapper.selectList(new QueryWrapper<Suggestion>()
                .eq("kind", "profile").eq("status", "pending"))) {
            existingKeys.add(s.getEvidence() + "|" + s.getAffected());
        }
        for (Map<String, Object> risk : risks) {
            String kind = String.valueOf(risk.getOrDefault("kind", ""));
            String member = String.valueOf(risk.getOrDefault("member", ""));
            String evidence = String.valueOf(risk.getOrDefault("evidence", ""));
            String inference = String.valueOf(risk.getOrDefault("inference", ""));
            String action = String.valueOf(risk.getOrDefault("suggested_action", ""));
            String impact = String.valueOf(risk.getOrDefault("impact", ""));
            String affected = kind.equals("stalled") ? "任务 " + risk.get("task_id") : "成员 " + member;
            String evidenceFull = "[" + range + "] " + evidence;
            if (!existingKeys.add(evidenceFull + "|" + affected)) {
                continue;   // 已有同内容 pending 建议
            }

            Suggestion s = new Suggestion();
            s.setId("SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            s.setAgent(agent == null || agent.isBlank() ? "画像智能体" : agent);
            s.setKind("profile");
            s.setEvidence(evidenceFull);
            s.setAffected(affected);
            s.setNote(inference + "；影响: " + impact + " → 建议行动: " + action);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("title", "协调行动(" + range + "): " + kindText(kind) + " - " + member);
            changes.put("description", "画像智能体风险建议\n证据: " + evidence + "\n推断: " + inference
                    + "\n影响: " + impact + "\n建议行动: " + action);
            changes.put("priority", kind.equals("overload") || kind.equals("single_point") ? "Should" : "Could");
            s.setChangeJson(writeJson(changes));
            s.setStatus("pending");
            s.setCreatedAt(LocalDateTime.now());
            suggestionMapper.insert(s);
            // 同步建审核记录(meeting_id 为空=非会议来源),使建议进入统一审核中心列表并可走 /review 流程
            MeetingSuggestionRecord record = new MeetingSuggestionRecord();
            record.setSuggestionId(s.getId());
            record.setMeetingId(null);
            record.setSubmittedBy(user.getId());
            record.setClientRequestId("profile:" + s.getId() + ":manual");
            record.setRequestHash(s.getId());
            record.setOrigin("agent");
            record.setExecutionStatus("not_started");
            record.setReason("");
            meetingSuggestionRecordMapper.insert(record);
            ids.add(s.getId());
        }
        return ids;
    }

    private String kindText(String kind) {
        return switch (kind) {
            case "overload" -> "成员负载过高";
            case "single_point" -> "关键单点依赖";
            case "stalled" -> "任务长期无进展";
            case "missing_review" -> "高难度任务缺 Review";
            case "missing_test" -> "任务缺测试活动";
            case "module_concentration" -> "模块工作集中";
            case "milestone_risk" -> "里程碑延期风险";
            default -> "项目风险";
        };
    }

    // ---------- 工具 ----------

    /** M2 修复:时间范围校验——不能为空、start 不能晚于 end、跨度不超过上限 */
    private void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) throw ApiException.badRequest("start/end 不能为空");
        if (start.isAfter(end)) throw ApiException.unprocessable("start 不能晚于 end");
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("时间范围不能超过 " + MAX_RANGE_DAYS + " 天");
        }
    }

    /** H5 辅助:把对象转成 Integer(数字或数字字符串),失败返回 null */
    private Integer toIntOrNull(Object o) {
        if (o == null) return null;
        try {
            return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String statusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待办";
            case 1 -> "进行中";
            case 2 -> "完成";
            case 3 -> "已取消";
            default -> "未知";
        };
    }

    private long daysBetween(LocalDate a, LocalDate b) {
        return Math.max(1, b.toEpochDay() - a.toEpochDay() + 1);
    }

    private Set<String> projectTaskIds(String projectId) {
        return taskMapper.selectList(new QueryWrapper<Task>()
                        .eq("project_id", ProjectAccessService.normalize(projectId)))
                .stream().map(Task::getId).collect(Collectors.toSet());
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw ApiException.server("JSON 序列化失败: " + e.getMessage());
        }
    }

    private List<Object> readJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
