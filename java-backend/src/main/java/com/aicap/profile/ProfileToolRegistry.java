package com.aicap.profile;

import com.aicap.entity.ProfileSnapshot;
import com.aicap.mapper.ActivityRecordMapper;
import com.aicap.mapper.DifficultyAssessmentMapper;
import com.aicap.mapper.MemberProfileMapper;
import com.aicap.mapper.ProfileCorrectionMapper;
import com.aicap.mapper.ProfileSnapshotMapper;
import com.aicap.mapper.SuggestionMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 画像智能体专属工具注册表(独立于会议智能体的 AgentTools)。
 * LLM 通过 {name, argsJson} 形式调用;每个工具带 JSON schema 描述,注入 system prompt 供模型选择。
 * 数据不足时工具返回明确的"空结果"结构,由运行循环决定是否追问/换工具——而不是像规则引擎那样直接放弃。
 */
@Component
public class ProfileToolRegistry {

    /** 工具定义:schema(给 LLM 看的说明) + 执行器(argsJson → resultJson) */
    public record Tool(String name, String description, String argsSchema,
                       Function<String, String> executor) {
    }

    /** 一次工具调用的事件(进 AgentRun.steps 做可观测) */
    public record ToolCall(String tool, String argsJson, String resultJson, boolean ok, String error) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final TaskMapper taskMapper;
    private final ActivityRecordMapper activityMapper;
    private final UserMapper userMapper;
    private final MemberProfileMapper memberProfileMapper;
    private final DifficultyAssessmentMapper difficultyMapper;
    private final ProfileSnapshotMapper snapshotMapper;
    private final ProfileCorrectionMapper correctionMapper;
    private final SuggestionMapper suggestionMapper;

    public ProfileToolRegistry(ObjectMapper objectMapper,
                               TaskMapper taskMapper,
                               ActivityRecordMapper activityMapper,
                               UserMapper userMapper,
                               MemberProfileMapper memberProfileMapper,
                               DifficultyAssessmentMapper difficultyMapper,
                               ProfileSnapshotMapper snapshotMapper,
                               ProfileCorrectionMapper correctionMapper,
                               SuggestionMapper suggestionMapper) {
        this.objectMapper = objectMapper;
        this.taskMapper = taskMapper;
        this.activityMapper = activityMapper;
        this.userMapper = userMapper;
        this.memberProfileMapper = memberProfileMapper;
        this.difficultyMapper = difficultyMapper;
        this.snapshotMapper = snapshotMapper;
        this.correctionMapper = correctionMapper;
        this.suggestionMapper = suggestionMapper;
        registerTools();
    }

    private void registerTools() {
        // 1. 查任务(单个/全部)
        tools.put("query_tasks", new Tool("query_tasks",
                "查询任务列表。可按 taskId 精确查询,或不带参数查全部任务(含名称/状态/工时/依赖/负责人/计划周)",
                "{\"taskId\":\"string,可选,如 T01\"}",
                args -> {
                    String taskId = strArg(args, "taskId");
                    if (taskId != null) {
                        var t = taskMapper.selectById(taskId);
                        return toJson(t == null ? Map.of("found", false, "taskId", taskId)
                                : Map.of("found", true, "task", t));
                    }
                    return toJson(Map.of("tasks", taskMapper.selectList(null)));
                }));

        // 2. 查活动事实
        tools.put("query_activities", new Tool("query_activities",
                "查询成员活动事实(commit/pr/review/bugfix/task_done/note)。可按 userId 与起止日期过滤;发现'7天无活动'等疑点时应先调用本工具核实",
                "{\"userId\":\"int,可选\",\"start\":\"yyyy-MM-dd,可选\",\"end\":\"yyyy-MM-dd,可选\"}",
                args -> {
                    var qw = new QueryWrapper<com.aicap.entity.ActivityRecord>();
                    Integer uid = intArg(args, "userId");
                    if (uid != null) qw.eq("user_id", uid);
                    LocalDate start = dateArg(args, "start");
                    if (start != null) qw.ge("happened_at", start.atStartOfDay());
                    LocalDate end = dateArg(args, "end");
                    if (end != null) qw.le("happened_at", end.atTime(23, 59, 59));
                    qw.orderByDesc("happened_at").last("LIMIT 200");
                    List<com.aicap.entity.ActivityRecord> list = activityMapper.selectList(qw);
                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("count", list.size());
                    res.put("activities", list);
                    // M6 修复:达到上限时明示截断,避免模型基于不完整数据静默下结论
                    if (list.size() >= 200) res.put("truncated", true);
                    return toJson(res);
                }));

        // 3. 查成员(含容量/角色)
        tools.put("query_members", new Tool("query_members",
                "查询成员列表(userId/显示名/角色/容量小时)或单个成员",
                "{\"userId\":\"int,可选\"}",
                args -> {
                    Integer uid = intArg(args, "userId");
                    if (uid != null) {
                        var u = userMapper.selectById(uid);
                        return toJson(u == null ? Map.of("found", false) : Map.of("found", true, "member", u));
                    }
                    return toJson(Map.of("members", userMapper.selectList(null)));
                }));

        // 4. 查成员技术画像
        tools.put("query_member_profile", new Tool("query_member_profile",
                "查询成员技术画像(tech_stack/熟练度),用于判断该成员适合什么难度与类型的任务",
                "{\"userId\":\"int,必填\"}",
                args -> {
                    Integer uid = intArg(args, "userId");
                    if (uid == null) return err("userId 必填");
                    var p = memberProfileMapper.selectOne(
                            new QueryWrapper<com.aicap.entity.MemberProfile>().eq("user_id", uid).last("LIMIT 1"));
                    return toJson(Map.of("found", p != null, "profile", p == null ? Map.of() : p));
                }));

        // 5. 查已有难度评估(含人工修正——评估同类任务前必须先看,避免推翻人工结论)
        tools.put("query_difficulty_history", new Tool("query_difficulty_history",
                "查询任务的历史难度评估(含人工修正记录)。人工修正(manual)永远优先于 AI 结论",
                "{\"taskId\":\"string,可选\"}",
                args -> {
                    var qw = new QueryWrapper<com.aicap.entity.DifficultyAssessment>();
                    String taskId = strArg(args, "taskId");
                    if (taskId != null) qw.eq("task_id", taskId);
                    qw.orderByDesc("id").last("LIMIT 100");
                    return toJson(Map.of("assessments", difficultyMapper.selectList(qw)));
                }));

        // 6. 查历史画像快照(记忆检索:评估前检索该成员最近的画像)
        tools.put("query_profile_snapshots", new Tool("query_profile_snapshots",
                "检索成员的历史画像快照(按时间倒序,最近在前)。评估前应检索,形成能力变化轨迹的记忆",
                "{\"userId\":\"int,必填\",\"limit\":\"int,可选,默认5\"}",
                args -> {
                    Integer uid = intArg(args, "userId");
                    if (uid == null) return err("userId 必填");
                    int limit = intArg(args, "limit") == null ? 5 : intArg(args, "limit");
                    var qw = new QueryWrapper<ProfileSnapshot>().eq("user_id", uid).orderByDesc("id").last("LIMIT " + limit);
                    return toJson(Map.of("snapshots", snapshotMapper.selectList(qw)));
                }));

        // 7. 查成员纠正记录(成员对画像的纠正是事实的一部分)
        tools.put("query_corrections", new Tool("query_corrections",
                "查询成员对 AI 画像的纠正记录。纠正代表成员确认的事实,必须尊重",
                "{\"userId\":\"int,可选\"}",
                args -> {
                    var qw = new QueryWrapper<com.aicap.entity.ProfileCorrection>();
                    Integer uid = intArg(args, "userId");
                    if (uid != null) qw.eq("user_id", uid);
                    qw.orderByDesc("id").last("LIMIT 20");
                    return toJson(Map.of("corrections", correctionMapper.selectList(qw)));
                }));

        // 8. 查 GitHub 真实活动(US34 同步后的 commit/PR/Review/Issue;验收标准 2:发现"7 天无活动"时应先查 Git 核实再下结论)
        tools.put("query_git_commits", new Tool("query_git_commits",
                "查询 GitHub 同步来的真实代码活动(commit/pr/review/note,source=github)。发现成员'长期无活动'时,必须先调用本工具核实是否真有 Git 提交,而不是直接下'停滞'结论。数据由 GitHub 同步任务写入;未接入/未同步时返回空并说明",
                "{\"userId\":\"int,可选\",\"start\":\"yyyy-MM-dd,可选\",\"end\":\"yyyy-MM-dd,可选\"}",
                args -> {
                    var qw = new QueryWrapper<com.aicap.entity.ActivityRecord>()
                            .eq("source", "github");
                    Integer uid = intArg(args, "userId");
                    if (uid != null) qw.eq("user_id", uid);
                    LocalDate start = dateArg(args, "start");
                    if (start != null) qw.ge("happened_at", start.atStartOfDay());
                    LocalDate end = dateArg(args, "end");
                    if (end != null) qw.le("happened_at", end.atTime(23, 59, 59));
                    qw.orderByDesc("happened_at").last("LIMIT 200");
                    List<com.aicap.entity.ActivityRecord> list = activityMapper.selectList(qw);
                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("github_sync_available", true);
                    res.put("count", list.size());
                    res.put("activities", list);
                    if (list.size() >= 200) res.put("truncated", true);
                    if (list.isEmpty()) {
                        res.put("note", "范围内无 GitHub 同步活动;可能是仓库未配置/未同步,或确实无提交(需结合 query_activities 的手动记录判断)");
                    }
                    return toJson(res);
                }));
        // 注意:H6 修复——不再注册 write_snapshot 工具。
        // 画像快照由 runAnalysis 统一落库(saveSnapshot,带正确的 range 与 engine 来源),
        // 若让 LLM 在分析循环中自主写快照,会产生与正式快照重复/时间轴错乱的数据,污染画像趋势。
        // 8 个工具(查询类)对成员分析已足够;快照写入是系统权威操作,不属于模型可自由调用的工具。
    }

    /** 工具 schema 列表(注入 system prompt) */
    public List<Map<String, String>> schemas() {
        List<Map<String, String>> out = new ArrayList<>();
        tools.forEach((name, t) -> out.add(Map.of(
                "name", name, "description", t.description(), "args", t.argsSchema())));
        return out;
    }

    /** 执行一次工具调用;任何异常转成 ok=false 的结果返回给模型(而不是炸掉整个循环) */
    public ToolCall invoke(String name, String argsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return new ToolCall(name, argsJson, toJson(Map.of("error", "未知工具: " + name)), false, "unknown_tool");
        }
        try {
            String result = tool.executor().apply(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return new ToolCall(name, argsJson, result, true, null);
        } catch (Exception e) {
            return new ToolCall(name, argsJson, toJson(Map.of("error", e.getMessage() == null ? "工具执行失败" : e.getMessage())), false, "exception");
        }
    }

    // ---------- 参数解析工具 ----------

    private String strArg(String argsJson, String key) {
        try {
            return objectMapper.readTree(argsJson).path(key).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intArg(String argsJson, String key) {
        try {
            var n = objectMapper.readTree(argsJson).path(key);
            return n.isMissingNode() || n.isNull() ? null : n.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate dateArg(String argsJson, String key) {
        String s = strArg(argsJson, key);
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Object arg(String argsJson, String key) {
        try {
            return objectMapper.readTree(argsJson).path(key);
        } catch (Exception e) {
            return null;
        }
    }

    private String err(String msg) {
        return toJson(Map.of("error", msg));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }
}
