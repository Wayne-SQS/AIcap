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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ProfileCorrectionMapper correctionMapper;
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

    /** 批量导入活动事实(文档 4.2:提交/Review 等活动批量接入;source=import)。
     *  事务性:任一条校验失败则整体回滚,避免中途失败导致部分入库、数据不完整。 */
    @Transactional
    public List<ProfileAgentDtos.ActivityOut> importActivities(List<ProfileAgentDtos.ActivityIn> items, User actor) {
        if (items == null || items.isEmpty()) throw ApiException.badRequest("导入列表不能为空");
        if (items.size() > 200) throw ApiException.unprocessable("单次最多导入 200 条");
        List<ProfileAgentDtos.ActivityOut> out = new ArrayList<>();
        for (ProfileAgentDtos.ActivityIn in : items) {
            out.add(addActivity(in, actor));
        }
        return out;
    }

    /** 贡献活动热力图(文档 4.10):按日聚合活动类型计数,前端画绿格子 */
    public List<Map<String, Object>> heatmap(Integer userId, LocalDate start, LocalDate end) {
        List<ActivityRecord> acts = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .eq(userId != null, "user_id", userId)
                .ge("happened_at", start.atStartOfDay())
                .le("happened_at", end.atTime(23, 59, 59))
                .orderByAsc("happened_at"));
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

    /** 文档 4.9:时间范围对比分析(本期 vs 上期)。
     *  输出每名成员两个周期的活动量/Review/缺陷修复/完成任务/难度承担对比,
     *  以及预计工时(任务分配工时)与完成率;实际完成工时缺数据源时如实说明。 */
    public Map<String, Object> comparePeriods(LocalDate prevStart, LocalDate prevEnd,
                                              LocalDate curStart, LocalDate curEnd) {
        List<User> users = userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"));
        List<Task> tasks = taskMapper.selectList(null);
        Map<String, ProfileAgentDtos.DifficultyOut> diffByTask = new HashMap<>();
        for (ProfileAgentDtos.DifficultyOut d : assessDifficulty(null, null)) {
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
        int commits = 0, reviews = 0, bugfixes = 0, taskDone = 0, unlinked = 0;
        for (ActivityRecord r : acts) {
            switch (r.getActivityType()) {
                case "commit" -> commits++;
                case "review" -> reviews++;
                case "bugfix" -> bugfixes++;
                case "task_done" -> taskDone++;
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
        if (!possiblyLate.isEmpty()) {
            inference.add("任务 " + String.join("、", possiblyLate) + " 计划结束周已过但仍未完成,存在延期风险(依据:tasks 的 week_end 与当前周对比)");
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
                        "suggested_action", "安排其他成员对该任务进行 Review,并补测关键路径"));
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
                        "suggested_action", "确认阻塞原因并调整排期;评估是否拆分任务或增加人手"));
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
    public List<String> submitRiskSuggestions(LocalDate start, LocalDate end, User user) {
        Map<String, Object> result = analyze(start, end);
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
            String affected = kind.equals("stalled") ? "任务 " + risk.get("task_id") : "成员 " + member;
            String evidenceFull = "[" + range + "] " + evidence;
            if (!existingKeys.add(evidenceFull + "|" + affected)) {
                continue;   // 已有同内容 pending 建议
            }

            Suggestion s = new Suggestion();
            s.setId("SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            s.setAgent("画像智能体");
            s.setKind("profile");
            s.setEvidence(evidenceFull);
            s.setAffected(affected);
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
