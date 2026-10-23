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
        ObjectNode result = mapper.createObjectNode().put("projectId", projectId).put("generatedAt", LocalDateTime.now().toString())
            .put("scope", "current_project").put("engine", "rules_fallback").put("projectWeeks", weeks);
        result.set("inputSummary", mapper.valueToTree(Map.of("tasks", tasks.size(), "members", members.size(), "coreDependencies", edges.size(),
            "activityRecords", activities.size(), "statusLogs", logs.size())));
        List<Map<String,Object>> bottlenecks = analyze(tasks,members,activities,today);
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
        result.put("summary", "基于当前项目证据识别 " + result.path("bottlenecks").size() + " 项待复核瓶颈");
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
        var out = new LinkedHashMap<>(rows.getFirst()); out.put("proposal",json(text(out.remove("proposal_json")))); return out;
    }
    public List<Map<String,Object>> proposalsForAnalysis(String projectId,String analysisId) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (var row : jdbc.queryForList("SELECT id,analysis_id,recommendation_id,status,submitted_by,created_at,reviewed_by,reviewed_at,reason,proposal_json "
            + "FROM efficiency_proposals WHERE project_id=? AND analysis_id=? ORDER BY created_at DESC",projectId,analysisId)) {
            Map<String,Object> copy = new LinkedHashMap<>(row);
            copy.put("proposal",json(text(copy.remove("proposal_json"))));
            result.add(copy);
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
}
