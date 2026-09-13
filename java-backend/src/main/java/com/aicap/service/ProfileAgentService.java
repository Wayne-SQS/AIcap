package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.ProfileAgentDtos;
import com.aicap.entity.ActivityRecord;
import com.aicap.entity.DifficultyAssessment;
import com.aicap.entity.MemberProfile;
import com.aicap.entity.ProfileAgentRun;
import com.aicap.entity.MeetingSuggestionRecord;
import com.aicap.entity.ProfileSnapshot;
import com.aicap.entity.Suggestion;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.ActivityRecordMapper;
import com.aicap.mapper.DifficultyAssessmentMapper;
import com.aicap.mapper.MeetingSuggestionRecordMapper;
import com.aicap.mapper.MemberProfileMapper;
import com.aicap.mapper.ProfileAgentRunMapper;
import com.aicap.mapper.ProfileSnapshotMapper;
import com.aicap.mapper.SuggestionMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
@Service
@RequiredArgsConstructor
public class ProfileAgentService {

    private final ActivityRecordMapper activityMapper;
    private final DifficultyAssessmentMapper difficultyMapper;
    private final ProfileSnapshotMapper snapshotMapper;
    private final ProfileAgentRunMapper runMapper;
    private final SuggestionMapper suggestionMapper;
    private final MeetingSuggestionRecordMapper meetingSuggestionRecordMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final MemberProfileMapper memberProfileMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> ACTIVITY_TYPES =
            List.of("commit", "pr", "review", "bugfix", "task_done", "note");

    // ---------- 活动录入 ----------

    public ProfileAgentDtos.ActivityOut addActivity(ProfileAgentDtos.ActivityIn in, User actor) {
        if (!ACTIVITY_TYPES.contains(in.activityType())) {
            throw ApiException.unprocessable("activity_type 必须是 " + ACTIVITY_TYPES + " 之一");
        }
        if (userMapper.selectById(in.userId()) == null) {
            throw ApiException.notFound("成员不存在");
        }
        if (in.taskId() != null && !in.taskId().isBlank()) {
            if (taskMapper.selectById(in.taskId()) == null) throw ApiException.notFound("任务不存在: " + in.taskId());
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

        ActivityRecord r = new ActivityRecord();
        r.setUserId(in.userId());
        r.setTaskId(in.taskId());
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
        qw.orderByDesc("happened_at");
        return activityMapper.selectList(qw).stream().map(this::toOut).toList();
    }

    private ProfileAgentDtos.ActivityOut toOut(ActivityRecord r) {
        return new ProfileAgentDtos.ActivityOut(r.getId(), r.getUserId(), r.getTaskId(),
                r.getActivityType(), r.getTitle(), r.getDetail(), r.getModule(), r.getSource(),
                r.getHappenedAt() == null ? null : r.getHappenedAt().format(TS));
    }

    // ---------- 难度评估 ----------

    /** AI 评估:依据任务工时/依赖/阻塞/进度等可量化因素,逐条给出依据。 */
    public List<ProfileAgentDtos.DifficultyOut> assessDifficulty(LocalDate start, LocalDate end) {
        List<Task> tasks = taskMapper.selectList(null);
        List<ProfileAgentDtos.DifficultyOut> out = new ArrayList<>();
        for (Task t : tasks) {
            if (t.getStatus() != null && t.getStatus() == 3) continue; // 已取消不评估
            DifficultyAssessment latest = latestAssessment(t.getId(), "manual");
            if (latest != null) { // 人工评估优先,不覆盖
                out.add(toDifficultyOut(latest));
                continue;
            }
            DifficultyAssessment ai = latestAssessment(t.getId(), "ai");
            if (ai == null) {
                ai = buildAiAssessment(t);
                difficultyMapper.insert(ai);
            }
            out.add(toDifficultyOut(ai));
        }
        return out;
    }

    /** 人工修正难度(覆盖 AI 结论;assessed_by=manual 永远优先) */
    public ProfileAgentDtos.DifficultyOut overrideDifficulty(String taskId, ProfileAgentDtos.DifficultyIn in) {
        if (taskMapper.selectById(taskId) == null) throw ApiException.notFound("任务不存在: " + taskId);
        List<String> levels = List.of("low", "medium", "high", "extreme");
        if (!levels.contains(in.level())) {
            throw ApiException.unprocessable("level 必须是 low/medium/high/extreme 之一");
        }
        DifficultyAssessment a = new DifficultyAssessment();
        a.setTaskId(taskId);
        a.setLevel(in.level());
        a.setScore(in.score() == null ? defaultScore(in.level()) : in.score());
        a.setBasis(writeJson(List.of("人工修正:项目负责人确认的难度等级")));
        a.setAssessedBy("manual");
        a.setCreatedAt(LocalDateTime.now());
        difficultyMapper.insert(a);
        return toDifficultyOut(a);
    }

    /** 文档 4.5:难度由工时/依赖数/核心模块/阻塞/延期等因素综合,必须给出依据 */
    private DifficultyAssessment buildAiAssessment(Task t) {
        int score = 0;
        List<String> basis = new ArrayList<>();
        int hours = t.getHours() != null ? t.getHours() : 0;
        if (hours >= 16) { score += 30; basis.add("预计工时 " + hours + "h,属于较大工作量(+30)"); }
        else if (hours >= 8) { score += 18; basis.add("预计工时 " + hours + "h,工作量中等(+18)"); }
        else { score += 6; basis.add("预计工时 " + hours + "h,工作量较小(+6)"); }

        List<String> deps = splitDeps(t.getDependsOn());
        if (deps.size() >= 3) { score += 25; basis.add("依赖 " + deps.size() + " 个前置任务,存在复杂依赖链(+25)"); }
        else if (deps.size() >= 1) { score += 12; basis.add("依赖 " + deps.size() + " 个前置任务(+12)"); }

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
        a.setTaskId(t.getId());
        a.setLevel(level);
        a.setScore(Math.min(score, 100));
        a.setBasis(writeJson(basis));
        a.setAssessedBy("ai");
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }

    private int defaultScore(String level) {
        return switch (level) {
            case "low" -> 10;
            case "medium" -> 35;
            case "high" -> 60;
            default -> 85;
        };
    }

    private DifficultyAssessment latestAssessment(String taskId, String by) {
        List<DifficultyAssessment> list = difficultyMapper.selectList(
                new QueryWrapper<DifficultyAssessment>()
                        .eq("task_id", taskId).eq("assessed_by", by)
                        .orderByDesc("id").last("LIMIT 1"));
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

    /** 文档 4.11:整包分析结果,每条结论带证据、区分事实与推断 */
    public Map<String, Object> analyze(LocalDate start, LocalDate end) {
        List<User> users = userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"));
        List<Task> tasks = taskMapper.selectList(null);
        Map<String, ProfileAgentDtos.DifficultyOut> diffByTask = new HashMap<>();
        for (ProfileAgentDtos.DifficultyOut d : assessDifficulty(start, end)) {
            diffByTask.put(d.taskId(), d);
        }

        List<Map<String, Object>> members = new ArrayList<>();
        List<Map<String, Object>> teamRisks = new ArrayList<>();
        for (User u : users) {
            members.add(memberAnalysis(u, tasks, diffByTask, start, end));
        }
        teamRisks.addAll(teamAnalysis(users, tasks, diffByTask));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range_start", start.toString());
        result.put("range_end", end.toString());
        result.put("generated_at", LocalDateTime.now().format(TS));
        result.put("data_sources", List.of("activity_records(手动/导入的活动事实)", "tasks(任务状态与工时)", "difficulty_assessments(难度评估)", "member_profiles(成员画像)"));
        result.put("members", members);
        result.put("team_risks", teamRisks);
        return result;
    }

    private Map<String, Object> memberAnalysis(User u, List<Task> tasks,
                                               Map<String, ProfileAgentDtos.DifficultyOut> diffByTask,
                                               LocalDate start, LocalDate end) {
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
        for (ActivityRecord r : acts) {
            typeCount.merge(r.getActivityType(), 1, Integer::sum);
            String item = "[" + r.getActivityType() + "] " + r.getTitle()
                    + (r.getModule() == null || r.getModule().isEmpty() ? "" : "(模块:" + r.getModule() + ")");
            workItems.add(item);
            if (r.getTaskId() == null || r.getTaskId().isBlank()) {
                unlinkedCommits.add("[" + r.getActivityType() + "] " + r.getTitle());
            }
            if (r.getModule() != null && !r.getModule().isEmpty()) {
                moduleCount.merge(r.getModule(), 1, Integer::sum);
            }
        }

        List<Task> myTasks = tasks.stream()
                .filter(t -> u.getId().equals(t.getOwnerId()))
                .toList();
        List<Map<String, Object>> taskFacts = new ArrayList<>();
        int doneCount = 0, highDifficultyCount = 0, assignedHours = 0, doneOnTime = 0, doneLate = 0;
        for (Task t : myTasks) {
            Map<String, Object> tf = new LinkedHashMap<>();
            tf.put("task_id", t.getId());
            tf.put("name", t.getName());
            tf.put("status", statusText(t.getStatus()));
            tf.put("hours", t.getHours());
            assignedHours += t.getHours() == null ? 0 : t.getHours();
            if (t.getStatus() != null && t.getStatus() == 2) doneCount++;
            ProfileAgentDtos.DifficultyOut d = diffByTask.get(t.getId());
            if (d != null) {
                tf.put("difficulty", d.level());
                if ("high".equals(d.level()) || "extreme".equals(d.level())) highDifficultyCount++;
            }
            taskFacts.add(tf);
        }

        // 交付及时性:已关联活动与任务完成情况
        m.put("objective", Map.of(
                "period_activity_counts", typeCount,
                "work_items", workItems,
                "total_activities", acts.size(),
                "tasks_owned", taskFacts,
                "tasks_done", doneCount,
                "high_difficulty_tasks", highDifficultyCount,
                "assigned_hours", assignedHours,
                "modules_touched", moduleCount.keySet().stream().toList(),
                "unlinked_activities", unlinkedCommits));

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
        if (acts.isEmpty() && doneCount == 0) {
            inference.add("该时间范围内暂无足够数据,无法确认实际完成内容,需要成员补充说明");
        }
        int loadPct = loadPercent(u, myTasks);
        m.put("ai_inference", inference);
        m.put("current_load_percent", loadPct);

        // 动态画像快照(文档 4.7)
        Map<String, Object> portrait = new LinkedHashMap<>();
        MemberProfile mp = memberProfileMapper.selectOne(
                new QueryWrapper<MemberProfile>().eq("user_id", u.getId()).last("LIMIT 1"));
        portrait.put("tech_stack", mp == null ? List.of() : readJsonList(mp.getTechStack()));
        portrait.put("good_at", mainModule == null ? "暂无足够数据" : mainModule + " 相关工作(近 " + daysBetween(start, end) + " 天活动归纳)");
        portrait.put("difficulty_capacity", highDifficultyCount >= 2 ? "较高(当前承担 " + highDifficultyCount + " 项高难度任务)" :
                highDifficultyCount == 1 ? "中等(当前承担 1 项高难度任务)" : "待观察(暂无高难度任务记录)");
        portrait.put("delivery_timeliness", doneCount == 0 ? "暂无已完成任务可评估" :
                "已交付 " + doneCount + " 项任务(依据:tasks 状态,与活动记录交叉印证)");
        portrait.put("current_load_percent", loadPct);
        portrait.put("risk_flags", loadPct >= 90 ? "负载过高,可能影响 Sprint 里程碑" :
                loadPct >= 70 ? "负载偏高,建议关注" : "正常");
        portrait.put("recommended_task_types", buildRecommendations(mp, mainModule));
        m.put("dynamic_profile", portrait);
        return m;
    }

    private int loadPercent(User u, List<Task> myTasks) {
        int capacity = u.getCapacityHours() == null || u.getCapacityHours() <= 0 ? 60 : u.getCapacityHours();
        int openHours = 0;
        for (Task t : myTasks) {
            if (t.getStatus() != null && (t.getStatus() == 0 || t.getStatus() == 1) && t.getHours() != null) {
                openHours += t.getHours();
            }
        }
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

    /** 文档 4.8:团队级风险分析 */
    private List<Map<String, Object>> teamAnalysis(List<User> users, List<Task> tasks,
                                                   Map<String, ProfileAgentDtos.DifficultyOut> diffByTask) {
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
                                (u.getCapacityHours() == null ? 60 : u.getCapacityHours()) + "h",
                        "inference", "负载约 " + pct + "%,长期过载风险,建议重新分配部分任务",
                        "suggested_action", "将低优先级任务拆分给负载较低的成员,或安排他人参与 Review"));
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
                        "suggested_action", "安排其他成员参与相关任务 Review,沉淀关键知识"));
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
                        "suggested_action", "与负责人确认任务状态;若已阻塞,补充阻塞原因并调整排期"));
            }
        }
        return risks;
    }

    // ---------- 运行记录与快照 ----------

    /** 触发一次分析:同步执行并留痕(小数据量同步即可;失败落库可重试) */
    public Map<String, Object> runAnalysis(LocalDate start, LocalDate end, User user) {
        ProfileAgentRun run = new ProfileAgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setRangeStart(start);
        run.setRangeEnd(end);
        run.setRequestedBy(user.getId());
        run.setStatus("running");
        run.setAttempt(1);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(run);
        try {
            Map<String, Object> result = analyze(start, end);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
            for (Map<String, Object> m : members) {
                saveSnapshot((Integer) m.get("user_id"), start, end, m, "ai");
            }
            run.setStatus("succeeded");
            run.setResultJson(writeJson(Map.of("member_count", members.size(),
                    "risk_count", ((List<?>) result.get("team_risks")).size())));
            runMapper.updateById(run);
            return result;
        } catch (Exception e) {
            run.setStatus("failed");
            run.setErrorMessage(e.getMessage() == null ? "unknown" : e.getMessage().substring(0, Math.min(490, e.getMessage().length())));
            runMapper.updateById(run);
            throw ApiException.server("分析失败,运行记录已留痕,可重试: " + e.getMessage());
        }
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
        s.setPayloadJson(writeJson(payload.get("dynamic_profile")));
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

    // ---------- 统一审核中心接入(文档第 5 章) ----------

    /**
     * 把团队风险转成待审建议,进统一 AI 建议审核中心:
     * 复用 suggestions 表(kind=profile),证据=风险事实,影响对象=相关成员/任务,
     * change_json=建议落入需求池的条目内容。人工审核通过后才会写入正式数据。
     */
    public List<String> submitRiskSuggestions(LocalDate start, LocalDate end, User user) {
        Map<String, Object> result = analyze(start, end);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risks = (List<Map<String, Object>>) result.get("team_risks");
        List<String> ids = new ArrayList<>();
        String range = start + " ~ " + end;
        for (Map<String, Object> risk : risks) {
            String kind = String.valueOf(risk.getOrDefault("kind", ""));
            String member = String.valueOf(risk.getOrDefault("member", ""));
            String evidence = String.valueOf(risk.getOrDefault("evidence", ""));
            String inference = String.valueOf(risk.getOrDefault("inference", ""));
            String action = String.valueOf(risk.getOrDefault("suggested_action", ""));

            Suggestion s = new Suggestion();
            s.setId("SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            s.setAgent("画像智能体");
            s.setKind("profile");
            s.setEvidence("[" + range + "] " + evidence);
            s.setAffected(kind.equals("stalled") ? "任务 " + risk.get("task_id") : "成员 " + member);
            s.setNote(inference + " → 建议行动: " + action);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("title", "协调行动(" + range + "): " + kindText(kind) + " - " + member);
            changes.put("description", "画像智能体风险建议\n证据: " + evidence + "\n推断: " + inference + "\n建议行动: " + action);
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
            default -> "项目风险";
        };
    }

    // ---------- 工具 ----------

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
