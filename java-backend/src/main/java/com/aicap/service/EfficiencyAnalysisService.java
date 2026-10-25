package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.Project;
import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.aicap.service.EfficiencyRules.*;

/** US23 reads scoped facts, persists analysis/proposals only, never executes task changes. */
@Service
@RequiredArgsConstructor
public class EfficiencyAnalysisService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ModelClient model;
    private final LlmProperties properties;
    private final ProjectAccessService access;

    public ObjectNode calculate(String projectId, LocalDate today) {
        var p = jdbc.queryForMap("SELECT start_date,end_date,total_sprints FROM projects WHERE id=?", projectId);
        Project project = new Project();
        project.setStartDate(date(p.get("start_date"))); project.setEndDate(date(p.get("end_date")));
        project.setTotalSprints(integer(p.get("total_sprints")));
        int weeks = ProjectCapacityPolicy.projectWeeks(project);
        var taskRows = jdbc.queryForList("SELECT t.*,s.code AS linked_story FROM tasks t LEFT JOIN stories s "
            + "ON s.internal_id=t.story_internal_id AND s.project_id=t.project_id WHERE t.project_id=? ORDER BY t.code", projectId);
        var edges = jdbc.queryForList("SELECT p.code AS predecessor,s.code AS successor FROM task_dependencies d "
            + "JOIN tasks p ON p.internal_id=d.predecessor_task_internal_id AND p.project_id=d.project_id "
            + "JOIN tasks s ON s.internal_id=d.successor_task_internal_id AND s.project_id=d.project_id WHERE d.project_id=?", projectId);
        var logs = jdbc.queryForList("SELECT l.* FROM task_logs l JOIN tasks t ON t.code=l.task_id AND t.project_id=l.project_id "
            + "WHERE l.project_id=? AND l.created_at<? ORDER BY l.id", projectId, today.plusDays(1));
        List<EfficiencyBacklogHistory.TaskState> historicalTasks = new ArrayList<>();
        for (var task : taskRows) {
            String code = text(task.get("code"));
            List<EfficiencyBacklogHistory.Change> changes = logs.stream().filter(log -> code.equals(text(log.get("task_id"))))
                .map(log -> new EfficiencyBacklogHistory.Change(dateTime(log.get("created_at")),
                    log.get("old_status") == null ? null : integer(log.get("old_status")), integer(log.get("new_status"))))
                .toList();
            LocalDateTime createdAt = changes.stream().filter(change -> change.oldStatus() == null)
                .map(EfficiencyBacklogHistory.Change::at).min(LocalDateTime::compareTo).orElse(null);
            historicalTasks.add(new EfficiencyBacklogHistory.TaskState(code, integer(task.get("status")), createdAt, changes));
        }
        List<EfficiencyBacklogHistory.Snapshot> backlogSnapshots = EfficiencyBacklogHistory.weekly(historicalTasks, today, 5);
        List<EfficiencyBacklogHistory.Snapshot> reliableBacklog = backlogSnapshots.stream()
            .filter(snapshot -> snapshot.totalTasks() > 0 && snapshot.coverage() >= .7).toList();
        boolean backlogHistoryAvailable = reliableBacklog.size() >= 3
            && reliableBacklog.getFirst().date().equals(today.minusWeeks(4))
            && reliableBacklog.getLast().date().equals(today);
        List<TaskFact> tasks = new ArrayList<>();
        for (var t : taskRows) {
            String code = text(t.get("code"));
            var history = logs.stream().filter(l -> code.equals(l.get("task_id"))).toList();
            var latest = history.isEmpty() ? null : history.getLast();
            LocalDate since = latest != null && integer(latest.get("new_status")) == integer(t.get("status")) ? date(latest.get("created_at")) : null;
            int regressions = (int) history.stream().filter(l -> l.get("old_status") != null && integer(l.get("old_status")) > integer(l.get("new_status"))).count();
            LocalDate due = project.getStartDate() == null || integer(t.get("week_end")) < 1 ? null
                : project.getStartDate().plusDays(integer(t.get("week_end")) * 7L - 1);
            tasks.add(new TaskFact(code, text(t.get("name")), text(t.get("linked_story")), integer(t.get("owner_id")),
                integer(t.get("status")), integer(t.get("progress")), integer(t.get("hours")), integer(t.get("blocked")) != 0,
                edges.stream().filter(e -> code.equals(e.get("successor"))).map(e -> text(e.get("predecessor"))).distinct().toList(),
                due, since, regressions));
        }
        var activityRows = jdbc.queryForList("SELECT a.* FROM activity_records a WHERE a.project_id=? AND a.happened_at<? "
            + "ORDER BY a.happened_at DESC", projectId, today.plusDays(1));
        Set<String> projectTaskCodes = new HashSet<>();
        taskRows.forEach(t -> projectTaskCodes.add(text(t.get("code"))));
        activityRows = activityRows.stream()
            .filter(a -> a.get("task_id") == null || projectTaskCodes.contains(text(a.get("task_id")))).toList();
        List<ActivityFact> activities = activityRows.stream().map(a -> new ActivityFact(text(a.get("id")), text(a.get("task_id")),
            text(a.get("module")), text(a.get("activity_type")), text(a.get("source")), text(a.get("title")))).toList();
        var memberRows = jdbc.queryForList("SELECT m.user_id,u.display_name,m.weekly_capacity_hours,m.capacity_hours,m.project_role "
            + "FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? ORDER BY m.user_id", projectId);
        List<MemberFact> members = new ArrayList<>();
        for (var m : memberRows) {
            int id = integer(m.get("user_id"));
            // Existing manually maintained profile is a user capability, not another project's analysis.
            var profiles = jdbc.queryForList("SELECT tech_stack,capabilities,process_domains FROM member_profiles WHERE user_id=?", id);
            List<String> skills = new ArrayList<>();
            for (var profile : profiles) for (var value : profile.values()) {
                JsonNode values = json(text(value));
                if (values.isArray()) for (var v : values) {
                    String name = v.isTextual() ? v.asText() : v.path("name").asText("");
                    if (!name.isBlank()) skills.add(name);
                }
            }
            List<String> prior = activityRows.stream().filter(a -> integer(a.get("user_id")) == id)
                .filter(a -> Set.of("commit","pr","task_done").contains(text(a.get("activity_type"))))
                .map(a -> text(a.get("module"))).filter(s -> !s.isBlank()).distinct().toList();
            double capacity = ProjectCapacityPolicy.weeklyCapacity(m.get("weekly_capacity_hours") == null ? null : integer(m.get("weekly_capacity_hours")),
                integer(m.get("capacity_hours")), id) * weeks;
            if (!"viewer".equals(m.get("project_role"))) members.add(new MemberFact(id, text(m.get("display_name")), capacity,
                tasks.stream().filter(t -> id == t.ownerId() && t.status() < 2).mapToInt(TaskFact::hours).sum(), skills, prior));
        }
        List<ProcessFact> processFacts = new ArrayList<>();
        // US23 only counts review timestamps that are actually persisted; no inferred meeting/PR durations.
        for (var row : jdbc.queryForList("SELECT id,status,created_at,reviewed_at FROM efficiency_proposals WHERE project_id=?", projectId)) {
            processFacts.add(new ProcessFact(text(row.get("id")), "efficiency_proposal", text(row.get("status")),
                dateTime(row.get("created_at")), dateTime(row.get("reviewed_at"))));
        }
        for (var row : jdbc.queryForList("SELECT CONCAT('meeting-',r.id) AS id,s.status,s.created_at,r.reviewed_at "
                + "FROM meeting_suggestion_records r JOIN suggestions s ON s.id=r.suggestion_id "
                + "JOIN meetings m ON m.id=r.meeting_id WHERE m.project_id=?", projectId)) {
            processFacts.add(new ProcessFact(text(row.get("id")), "meeting_suggestion", text(row.get("status")),
                dateTime(row.get("created_at")), dateTime(row.get("reviewed_at"))));
        }
        ObjectNode result = mapper.createObjectNode().put("projectId", projectId).put("generatedAt", LocalDateTime.now().toString())
            .put("scope", "current_project").put("engine", "rules_fallback").put("projectWeeks", weeks);
        result.set("inputSummary", mapper.valueToTree(Map.of("tasks", tasks.size(), "members", members.size(), "coreDependencies", edges.size(),
            "activityRecords", activities.size(), "statusLogs", logs.size(), "reviewRecords", processFacts.size())));
        Map<String,Object> coverage = new LinkedHashMap<>();
        coverage.put("taskCount", tasks.size()); coverage.put("memberCount", members.size()); coverage.put("dependencyCount", edges.size());
        coverage.put("githubActivityCount", activities.stream().filter(a -> "github".equalsIgnoreCase(a.source())).count());
        coverage.put("activityRecordCount", activities.size()); coverage.put("statusLogCount", logs.size()); coverage.put("reviewRecordCount", processFacts.size());
        coverage.put("actualHoursAvailable", false); coverage.put("reviewTimingAvailable", processFacts.stream().anyMatch(process -> process.reviewedAt() != null));
        coverage.put("backlogHistoryAvailable", backlogHistoryAvailable); coverage.put("backlogSnapshotCount", reliableBacklog.size());
        result.set("coverage", mapper.valueToTree(coverage));
        result.set("audit", mapper.valueToTree(Map.of("analysisTime", LocalDateTime.now().toString(), "projectId", projectId,
            "dataSources", List.of("tasks.status", "task_logs.creation_and_status_history", "task_dependencies", "project_members", "activity_records", "efficiency_proposals", "meeting_suggestion_records", "suggestions", "meetings"),
            "metricsUsed", List.of("weekly_open_task_snapshots", "blocked", "core_dependencies", "status_history", "planned_hours", "capacity", "github_activity", "review_timestamps"),
            "missingEvidence", backlogHistoryAvailable ? List.of("actual_hours", "time_logs", "PR_started_at", "PR_completed_at")
                : List.of("incomplete_task_creation_or_status_history", "actual_hours", "time_logs", "PR_started_at", "PR_completed_at"), "aiExplanationUsed", false)));
        result.set("backlogHistory", mapper.valueToTree(Map.of("available", backlogHistoryAvailable,
            "minimumCoverage", .7, "snapshots", backlogSnapshots.stream().map(snapshot -> Map.of("date", snapshot.date().toString(),
                "openTasks", snapshot.openTasks(), "knownTasks", snapshot.knownTasks(), "totalTasks", snapshot.totalTasks(),
                "coverage", snapshot.coverage())).toList(),
            "limitation", "历史点仅统计创建时间和状态日志可还原的任务；低于70%覆盖率的时间点不用于趋势判断")));
        List<Map<String,Object>> bottlenecks = analyze(tasks,members,activities,processFacts,today);
        if (backlogHistoryAvailable) {
            var first = reliableBacklog.getFirst();
            var latest = reliableBacklog.getLast();
            if (latest.openTasks() > first.openTasks()) {
                bottlenecks.add(EfficiencyRules.backlogGrowth(first.openTasks(), latest.openTasks(),
                    first.date(), latest.date(), latest.coverage(),
                    EfficiencyBacklogHistory.newlyOpenTaskCodes(historicalTasks, first.date(), latest.date())));
            }
        }
        Map<String,Long> ruleHits = bottlenecks.stream().collect(java.util.stream.Collectors.groupingBy(
            row -> text(row.get("type")), java.util.stream.Collectors.counting()));
        ((ObjectNode) result.path("audit")).set("ruleHits", mapper.valueToTree(ruleHits));
        Map<String,Object> actualHours = new LinkedHashMap<>();
        actualHours.put("available", false);
        actualHours.put("reason", "系统没有任务实际工时来源；hours 是计划工时，不能用作已耗工时");
        actualHours.put("tasks", taskRows.stream().map(t -> Map.of("taskCode", text(t.get("code")),
            "plannedHours", integer(t.get("hours")), "progressPercent", integer(t.get("progress")))).toList());
        result.set("actualHoursEvidence", mapper.valueToTree(actualHours));
        result.set("bottlenecks", mapper.valueToTree(bottlenecks));
        result.set("limitations", mapper.valueToTree(List.of("未记录实际工时，不能比较实际/预计耗时；工时仅为计划值",
            "weak dependency不作硬阻塞；未配置项目日期时不推断任务逾期",
            "GitHub仅复用当前项目已同步activity_records，不进行抓取；提交次数不能证明返工",
            "没有PR等待起始/结束或审核队列时间记录时，不能证明真实PR积压；流程提示仅依据有效状态历史",
            "技能来自现有用户手工画像，历史经验仅来自当前项目活动；没有匹配不推荐转移")));
        if (activities.isEmpty()) result.withArray("limitations").add("返工证据不足/暂未发现可确认返工");
        result.put("summary", "基于当前项目证据识别 " + result.path("bottlenecks").size() + " 项待复核瓶颈；实际工时缺失，收益暂无法归因");
        if (result.path("bottlenecks").isEmpty()) result.put("emptyReason", "现有项目数据未达到确定性规则阈值；这不表示项目不存在效率问题");
        return result;
    }

    /** Model may propose wording only; identities, evidence, severity and targets remain rule-owned. */
    ObjectNode explain(ObjectNode rules) {
        if (!properties.settingsReady() || rules.path("bottlenecks").isEmpty()) return rules;
        try {
            String system = """
                你是US23效率分析解释器，不是风险预测器。只依据给定结构化evidence解释当前瓶颈。
                禁止虚构任务、成员、提交、Bug、工时、日期、根因或数据库写操作。无法确认时写可能/证据不足。
                不得更改目标、严重度、证据或确定性规则，不自动分配任务。仅返回JSON：
                {"explanations":[{"id":"原瓶颈id","evidenceRefs":["该瓶颈原始证据原文"],"possibleCause":"谨慎解释","recommendation":"可执行复核步骤"}]}
                每条解释只能引用对应瓶颈的证据，推荐动作不能新增目标对象。
                """;
            var response = model.complete(List.of(mapper.createObjectNode().put("role","system").put("content",system),
                mapper.createObjectNode().put("role","user").put("content",rules.path("bottlenecks").toString())), List.of(), true);
            JsonNode output = mapper.readTree(response.message().path("content").asText());
            if (!output.isObject() || !output.path("explanations").isArray()) throw new IllegalArgumentException();
            var byId = new HashMap<String,JsonNode>();
            for (var e : output.path("explanations")) {
                if (!e.path("id").isTextual() || !e.path("possibleCause").isTextual() || !e.path("recommendation").isTextual()
                    || !e.path("evidenceRefs").isArray() || e.path("evidenceRefs").isEmpty() || byId.put(e.path("id").asText(),e) != null)
                    throw new IllegalArgumentException();
            }
            if (byId.size() != rules.path("bottlenecks").size()) throw new IllegalArgumentException();
            for (var b : rules.path("bottlenecks")) {
                var e = byId.get(b.path("id").asText());
                if (e == null) throw new IllegalArgumentException();
                Set<String> allowed = new HashSet<>(); b.path("evidence").forEach(v -> allowed.add(v.asText()));
                for (var ref : e.path("evidenceRefs")) if (!ref.isTextual() || !allowed.contains(ref.asText())) throw new IllegalArgumentException();
                if (e.path("possibleCause").asText().length() > 1000 || e.path("recommendation").asText().length() > 1000) throw new IllegalArgumentException();
            }
            // Keep AI output separate: always preserve deterministic recommendation and its exact targets.
            for (var b : rules.path("bottlenecks")) ((ObjectNode)b).set("aiExplanation", byId.get(b.path("id").asText()));
            rules.put("engine", "rules_and_ai");
            rules.with("audit").put("aiExplanationUsed", true);
        } catch (Exception ex) { rules.put("aiStatus", "unavailable_or_invalid_rules_preserved"); }
        return rules;
    }

    public ObjectNode run(String projectId, int actor) {
        // Lock project only while creating the durable running marker; no lock during model request.
        String id = UUID.randomUUID().toString();
        int claimed = jdbc.update("INSERT INTO efficiency_analysis_runs(id,project_id,requested_by,status,result_json,created_at,updated_at) "
            + "SELECT ?,?,?,'running','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS "
            + "(SELECT 1 FROM efficiency_analysis_runs WHERE project_id=? AND status='running' AND updated_at>?)",
            id,projectId,actor,projectId,LocalDateTime.now().minusMinutes(10));
        if (claimed == 0) throw ApiException.conflict("当前项目已有分析正在运行，请稍后查看");
        try {
            ObjectNode result = explain(calculate(projectId,LocalDate.now()));
            result.put("analysisId",id);
            jdbc.update("UPDATE efficiency_analysis_runs SET status='succeeded',result_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND project_id=?",
                result.toString(),id,projectId);
            return get(projectId,id);
        } catch (RuntimeException ex) {
            jdbc.update("UPDATE efficiency_analysis_runs SET status='failed',updated_at=CURRENT_TIMESTAMP WHERE id=? AND project_id=?",id,projectId);
            throw ex;
        }
    }
    public ObjectNode latest(String projectId) {
        var rows = jdbc.queryForList("SELECT id FROM efficiency_analysis_runs WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 1",projectId);
        return rows.isEmpty() ? mapper.createObjectNode().put("projectId",projectId).putNull("analysisId") : get(projectId,text(rows.getFirst().get("id")));
    }
    public ObjectNode get(String projectId,String id) {
        var rows = jdbc.queryForList("SELECT * FROM efficiency_analysis_runs WHERE project_id=? AND id=?",projectId,id);
        if (rows.isEmpty()) throw ApiException.notFound("效率分析记录不存在");
        var row = rows.getFirst();
        ObjectNode result = (ObjectNode)json(text(row.get("result_json")));
        result.put("analysisId",id).put("projectId",projectId).put("status",text(row.get("status"))).put("createdAt",text(row.get("created_at")));
        result.set("proposals", mapper.valueToTree(proposalsForAnalysis(projectId,id)));
        return result;
    }
    @Transactional
    public Map<String,Object> submit(String projectId,String analysisId,String recommendationId,int actor) {
        // Serialize repeated clicks on one immutable analysis; no task/Story update.
        var rows = jdbc.queryForList("SELECT result_json FROM efficiency_analysis_runs WHERE project_id=? AND id=? AND status='succeeded' FOR UPDATE",projectId,analysisId);
        if (rows.isEmpty()) throw ApiException.notFound("已完成分析不存在");
        var saved = jdbc.queryForList("SELECT * FROM efficiency_proposals WHERE project_id=? AND analysis_id=? AND recommendation_id=?",projectId,analysisId,recommendationId);
        if (!saved.isEmpty()) return saved.getFirst();
        JsonNode selected = null;
        for (var b : json(text(rows.getFirst().get("result_json"))).path("bottlenecks")) for (var r : b.path("recommendations"))
            if (recommendationId.equals(r.path("id").asText())) { selected = b; break; }
        if (selected == null) throw ApiException.notFound("优化建议不存在");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO efficiency_proposals(id,project_id,analysis_id,recommendation_id,proposal_json,status,submitted_by,created_at,reason) "
            + "VALUES(?,?,?,?,?,'pending',?,CURRENT_TIMESTAMP,'')",id,projectId,analysisId,recommendationId,selected.toString(),actor);
        return proposal(projectId,id);
    }
    public Map<String,Object> proposal(String projectId,String id) {
        var rows = jdbc.queryForList("SELECT * FROM efficiency_proposals WHERE project_id=? AND id=?",projectId,id);
        if (rows.isEmpty()) throw ApiException.notFound("效率提案不存在");
        var out = new LinkedHashMap<>(rows.getFirst()); out.put("proposal",json(text(out.remove("proposal_json"))));
        out.put("planning_run", latestPlanningRun(projectId,id));
        return out;
    }
    public List<Map<String,Object>> proposalsForAnalysis(String projectId,String analysisId) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (var row : jdbc.queryForList("SELECT id,analysis_id,recommendation_id,status,submitted_by,created_at,reviewed_by,reviewed_at,reason,proposal_json "
            + "FROM efficiency_proposals WHERE project_id=? AND analysis_id=? ORDER BY created_at DESC",projectId,analysisId)) {
            Map<String,Object> copy = new LinkedHashMap<>(row);
            copy.put("proposal",json(text(copy.remove("proposal_json"))));
            copy.put("planning_run", latestPlanningRun(projectId,text(copy.get("id"))));
            copy.put("checks", checks(projectId, text(copy.get("id"))));
            result.add(copy);
        }
        return result;
    }
    public Map<String,Object> latestPlanningRun(String projectId, String proposalId) {
        var rows = jdbc.queryForList("SELECT r.id,r.status,r.result_json FROM efficiency_proposal_planning_runs l "
                + "JOIN planning_agent_runs r ON r.id=l.planning_run_id AND r.project_id=l.project_id "
                + "WHERE l.project_id=? AND l.proposal_id=? ORDER BY l.created_at DESC,l.planning_run_id DESC LIMIT 1",
                projectId,proposalId);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        String resultJson = text(row.get("result_json"));
        return Map.of("id",text(row.get("id")), "status",text(row.get("status")),
                "action_count",resultJson.isBlank() ? 0 : json(resultJson).path("actions").size());
    }
    @Transactional
    public void linkPlanningRun(String projectId, String proposalId, String runId) {
        var approved = jdbc.queryForObject("SELECT COUNT(*) FROM efficiency_proposals "
                + "WHERE project_id=? AND id=? AND status='approved'",Integer.class,projectId,proposalId);
        var scopedRun = jdbc.queryForObject("SELECT COUNT(*) FROM planning_agent_runs "
                + "WHERE project_id=? AND id=?",Integer.class,projectId,runId);
        if (approved == null || approved == 0 || scopedRun == null || scopedRun == 0)
            throw ApiException.conflict("提案或规划运行已失效，请刷新");
        jdbc.update("INSERT INTO efficiency_proposal_planning_runs(project_id,proposal_id,planning_run_id,created_at) "
                + "VALUES(?,?,?,CURRENT_TIMESTAMP)",projectId,proposalId,runId);
    }
    public Map<String,Object> recordReviewCheck(String projectId, String proposalId, int actor) {
        Map<String,Object> saved = proposal(projectId, proposalId);
        if (!"approved".equals(saved.get("status"))) throw ApiException.conflict("仅已批准建议可以复查");
        Map<String,Object> plan = latestPlanningRun(projectId,proposalId);
        if (plan == null || !"completed".equals(plan.get("status")) || (int)plan.get("action_count") == 0)
            throw ApiException.conflict("规划尚未完成实际变更，不能记录改善复查");
        JsonNode source = (JsonNode) saved.get("proposal");
        ObjectNode current = calculate(projectId, LocalDate.now());
        String type = source.path("type").asText();
        Set<String> taskCodes = new HashSet<>(); source.path("affectedTaskCodes").forEach(n -> taskCodes.add(n.asText()));
        Set<String> memberIds = new HashSet<>(); source.path("affectedMemberIds").forEach(n -> memberIds.add(n.asText()));
        boolean active = false;
        for (JsonNode item : current.path("bottlenecks")) {
            if (!type.equals(item.path("type").asText())) continue;
            if (taskCodes.isEmpty() && memberIds.isEmpty()) { active = true; break; }
            boolean sameTask = false, sameMember = false;
            for (JsonNode code : item.path("affectedTaskCodes")) if (taskCodes.contains(code.asText())) sameTask = true;
            for (JsonNode id : item.path("affectedMemberIds")) if (memberIds.contains(id.asText())) sameMember = true;
            if (sameTask || sameMember) { active = true; break; }
        }
        JsonNode planned = source.path("recheckMetric");
        String metric = planned.path("metric").asText("same_bottleneck_rule_active");
        Double measured = null;
        String outcome;
        if ("open_task_count_change_over_28_days".equals(metric)) {
            JsonNode snapshots = current.path("backlogHistory").path("snapshots");
            List<JsonNode> reliable = new ArrayList<>();
            snapshots.forEach(s -> { if (s.path("totalTasks").asInt() > 0 && s.path("coverage").asDouble() >= .7) reliable.add(s); });
            if (reliable.size() >= 3 && reliable.getFirst().path("date").asText().equals(LocalDate.now().minusWeeks(4).toString())
                    && reliable.getLast().path("date").asText().equals(LocalDate.now().toString()))
                measured = (double) reliable.getLast().path("openTasks").asInt() - reliable.getFirst().path("openTasks").asInt();
        } else measured = active ? 1d : 0d;
        if (measured == null) outcome = "insufficient_data";
        else if ("open_task_count_change_over_28_days".equals(metric) ? measured <= 0 : measured == 0) outcome = "signal_reduced";
        else outcome = "still_active";
        Map<String,Object> metricResult = new LinkedHashMap<>();
        metricResult.put("metric", metric); metricResult.put("baseline", planned.path("baseline"));
        metricResult.put("target", planned.path("target")); metricResult.put("measuredValue", measured);
        metricResult.put("windowDays", planned.path("reviewWindowDays").asInt(14));
        metricResult.put("planningRunId",plan.get("id"));
        metricResult.put("evidence", "按当前项目任务状态/状态历史重新计算；不代表改进动作单独导致结果变化");
        String checkId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO efficiency_proposal_checks(id,project_id,proposal_id,metric_json,measured_value,outcome,checked_by,checked_at) "
            + "VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", checkId, projectId, proposalId, mapper.valueToTree(metricResult).toString(), measured, outcome, actor);
        return checks(projectId, proposalId).getFirst();
    }
    private List<Map<String,Object>> checks(String projectId, String proposalId) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (var row : jdbc.queryForList("SELECT id,metric_json,measured_value,outcome,checked_by,checked_at FROM efficiency_proposal_checks "
                + "WHERE project_id=? AND proposal_id=? ORDER BY checked_at DESC", projectId, proposalId)) {
            Map<String,Object> item = new LinkedHashMap<>(row); item.put("metric", json(text(item.remove("metric_json")))); result.add(item);
        }
        return result;
    }
    @Transactional
    public Map<String,Object> review(String projectId,String id,String decision,String reason,int actor) {
        access.requireOwner(projectId);
        if (!Set.of("approve","reject").contains(decision) || reason == null || reason.isBlank() || reason.length()>1000)
            throw ApiException.unprocessable("审核需填写理由，决定只能为approve/reject");
        String status = decision.equals("approve") ? "approved" : "rejected";
        if (jdbc.update("UPDATE efficiency_proposals SET status=?,reason=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP "
            + "WHERE project_id=? AND id=? AND status='pending'",status,reason,actor,projectId,id)==0)
            throw ApiException.conflict("提案已处理或不存在，请刷新");
        return proposal(projectId,id);
    }
    private JsonNode json(String value) { try { return mapper.readTree(value); } catch(Exception ex) { throw new IllegalStateException("Stored JSON invalid",ex); } }
    private static String text(Object o) { return o==null ? "" : o.toString(); }
    private static int integer(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static LocalDate date(Object o) { return o==null ? null : LocalDate.parse(o.toString().substring(0,10)); }
    private static LocalDateTime dateTime(Object o) {
        if (o == null) return null;
        String value = o.toString().replace(' ', 'T');
        return LocalDateTime.parse(value.length() > 19 ? value.substring(0, 19) : value);
    }
}
