package com.aicap.generation;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentProperties;
import com.aicap.agent.ModelClient;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.aicap.mapper.ProjectMemberMapper;
import com.aicap.entity.ProjectMember;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM-only planning step. It never writes business tables. */
@Component
@RequiredArgsConstructor
public class ProjectGenerationRunner {
    private static final Pattern SPRINT_SECTION = Pattern.compile("(?im)^\\s*Sprint\\s*(\\d+)\\s*(?:[：:]|$)");
    private static final Pattern SPRINT_ITEM = Pattern.compile("(?m)^\\s*(?:[-*•]|\\d+[.、)])\\s*(.+?)\\s*$");
    private static final Pattern EXPLICIT_OWNER = Pattern.compile("(?:负责人|owner(?:_id)?)\\s*[：:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_WEEKS = Pattern.compile("(?:W|第)\\s*(\\d+)\\s*(?:周)?\\s*(?:[-~至到]|—)\\s*(?:W|第)?\\s*(\\d+)\\s*(?:周)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_FENCE = Pattern.compile("(?is)^\\s*```(?:json)?\\s*\\r?\\n(.*?)\\r?\\n```\\s*$");
    private final UserMapper users;
    private final ProjectMemberMapper projectMembers;
    private final ObjectMapper mapper;

    public record TruncationInfo(String stage, String finishReason, int responseLength,
                                 boolean continuationTriggered, Boolean continuationSucceeded,
                                 Integer recoveredStoryCount, Integer mergedStoryCount,
                                 Integer continuationIndex, Integer recoveredTaskCount,
                                 Integer recoveredMilestoneCount, Integer mergedTaskCount,
                                 String remainingMissingItems) {
        public TruncationInfo(String stage, String finishReason, int responseLength,
                              boolean continuationTriggered, Boolean continuationSucceeded) {
            this(stage, finishReason, responseLength, continuationTriggered, continuationSucceeded,
                    null, null, null, null, null, null, null);
        }
        public TruncationInfo(String stage, String finishReason, int responseLength, boolean continuationTriggered) {
            this(stage, finishReason, responseLength, continuationTriggered, null, null, null,
                    null, null, null, null, null);
        }
    }

    public ObjectNode generate(String request, String mode, ModelClient client) throws AgentError { throw new AgentError("project_context_missing", "projectId 必填"); }

    public ObjectNode generate(String projectId, String request, String mode, ModelClient client) throws AgentError {
        return generate(projectId, request, mode, client, "");
    }

    /** Second-pass generation is deliberately feedback-driven and remains a draft-only operation. */
    public ObjectNode generate(String projectId, String request, String mode, ModelClient client, String completenessFeedback) throws AgentError {
        return generate(projectId, request, mode, client, completenessFeedback, ignored -> { });
    }

    ObjectNode generate(String projectId, String request, String mode, ModelClient client,
                        String completenessFeedback, Consumer<TruncationInfo> truncationReporter) throws AgentError {
        return generate(projectId, request, mode, client, completenessFeedback, truncationReporter, ignored -> { });
    }

    ObjectNode generate(String projectId, String request, String mode, ModelClient client,
                        String completenessFeedback, Consumer<TruncationInfo> truncationReporter,
                        Consumer<ResponseDiagnostic> responseReporter) throws AgentError {
        if ("detailed".equalsIgnoreCase(mode) && requiresStagedGeneration(request)) {
            return generateDetailedStages(projectId, request, client, completenessFeedback, truncationReporter, responseReporter);
        }
        boolean resilientSinglePass = "overview".equalsIgnoreCase(mode) || "excel".equalsIgnoreCase(mode);
        return generateSingle(projectId, request, mode, client, completenessFeedback,
                resilientSinglePass, truncationReporter);
    }

    ObjectNode generateDetailedTaskSupplement(String projectId, ObjectNode current,
                                              Map<String, Set<String>> stageGaps, int budget, ModelClient client,
                                              Consumer<TruncationInfo> truncationReporter,
                                              Consumer<ResponseDiagnostic> responseReporter) throws AgentError {
        ArrayNode gaps = mapper.createArrayNode();
        for (JsonNode story : current.path("stories"))
            if (stageGaps.containsKey(story.path("id").asText(""))) {
                ObjectNode gap = mapper.createObjectNode();
                gap.put("storyCode", story.path("id").asText(""));
                gap.put("storyTitle", story.path("title").asText(story.path("name").asText("")));
                gap.set("missingStages", mapper.valueToTree(stageGaps.get(story.path("id").asText(""))));
                gaps.add(gap);
            }
        String constraints = supplementPrompt(current, gaps, stageGaps, budget);
        String prompt = constraints + "\n输出严格 JSON，不要 Markdown。";
        ObjectNode attemptAudit = mapper.createObjectNode().put("supplementTriggered", true)
                .put("supplementAttemptCount", 1).put("supplementRetryCount", 0)
                .put("attempt1ParsedTasks", 0).put("attempt1ErrorCode", "")
                .put("attempt2ParsedTasks", 0).put("attempt2ErrorCode", "NOT_ATTEMPTED");
        ObjectNode result;
        try {
            result = completeStage(client, "tasks", prompt, truncationReporter, responseReporter);
            attemptAudit.put("attempt1ParsedTasks", result.path("tasks").size());
        } catch (AgentError first) {
            attemptAudit.put("attempt1ErrorCode", first.getCode());
            if (!"TASKS_STAGE_EMPTY".equals(first.getCode()))
                throw new DetailedSupplementFailure(first.getCode(), first.getMessage(), attemptAudit);
            attemptAudit.put("supplementAttemptCount", 2).put("supplementRetryCount", 1);
            String retryPrompt = constraints + """

                    上一次返回 tasks=[]，不符合要求。本次必须至少为每个仍缺失的 Story+stage 生成对应核心 Task；
                    只有当前批次 plannedStageCount=0 时才允许为空。不得扩大范围、不得生成其他集合。
                    """;
            try {
                result = completeStage(client, "tasks", retryPrompt, truncationReporter, responseReporter);
                attemptAudit.put("attempt2ParsedTasks", result.path("tasks").size()).put("attempt2ErrorCode", "");
            } catch (AgentError second) {
                attemptAudit.put("attempt2ErrorCode", second.getCode());
                throw new DetailedSupplementFailure(second.getCode(), second.getMessage(), attemptAudit);
            }
        }
        TaskStoryReferences.canonicalize(result, current.path("stories"));
        int generated = result.path("tasks").size();
        int rejected = DetailedTaskBudget.enforce(result, budget);
        for (String key : List.of("members", "sprints", "activities", "epics", "stories", "milestones"))
            result.set(key, mapper.createArrayNode());
        result.set("project", mapper.createObjectNode());
        result.set("uml", mapper.createObjectNode());
        ObjectNode audit = result.with("_task_generation_audit");
        attemptAudit.fields().forEachRemaining(field -> audit.set(field.getKey(), field.getValue().deepCopy()));
        audit.put("taskBudget", DetailedTaskBudget.forStories(current.path("stories").size()))
                .put("supplementGapCount", stageGaps.size()).put("supplementGeneratedTaskCount", generated)
                .put("generatedSupplementTaskCount", generated)
                .put("supplementAcceptedTaskCount", result.path("tasks").size())
                .put("acceptedSupplementTaskCount", result.path("tasks").size())
                .put("supplementRejectedByBudgetCount", rejected)
                .put("rejectedSupplementTaskCount", rejected)
                .put("supplementedStageTaskCount", result.path("tasks").size());
        return result;
    }

    private String supplementPrompt(ObjectNode current, ArrayNode gaps,
                                   Map<String, Set<String>> stageGaps, int budget) {
        ArrayNode compactStories = mapper.createArrayNode();
        for (JsonNode gap : gaps) {
            String code = gap.path("storyCode").asText("");
            JsonNode story = null;
            for (JsonNode candidate : current.path("stories"))
                if (code.equalsIgnoreCase(candidate.path("id").asText(candidate.path("code").asText("")))) { story = candidate; break; }
            ObjectNode row = mapper.createObjectNode().put("code", code)
                    .put("title", gap.path("storyTitle").asText(""));
            if (story != null) {
                String description = story.path("description").asText(story.path("acceptance").asText(""));
                if (description.length() > 220) description = description.substring(0, 220);
                row.put("description", description);
            }
            Set<String> present = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            ArrayNode existing = mapper.createArrayNode();
            for (JsonNode task : current.path("tasks")) {
                String refs = TaskStoryReferences.text(task);
                if (!java.util.Arrays.stream(refs.split("[,，;；\\s]+"))
                        .anyMatch(ref -> code.equalsIgnoreCase(ref.trim()))) continue;
                String text = task.path("name").asText(task.path("title").asText(""));
                Set<String> stages = promptImplementationStages(text);
                present.addAll(stages);
                existing.add(mapper.createObjectNode().put("code", task.path("id").asText(""))
                        .put("title", text).put("stages", String.join(",", stages)));
            }
            row.put("requiredStages", String.join(",", unionStages(present, stageGaps.getOrDefault(code, Set.of()))));
            row.put("presentStages", String.join(",", present));
            row.put("missingStages", String.join(",", stageGaps.getOrDefault(code, Set.of())));
            row.set("existingTasks", existing);
            compactStories.add(row);
        }
        return """
                你只负责补全 detailed 项目的 Task。只返回一个 JSON object，必须包含非空 tasks 数组和 dependencies 数组，禁止返回 project/stories/activities/epics/milestones/uml。
                本批最多 %d 个 Story，Task 总数不得超过 %d。只允许指定 Story code；每个 Story+missingStage 最多1个核心 Task（硬上限为 1），只补 missingStages。
                tasks=[] 不合格。禁止项目框架、统一异常框架、重复数据库初始化等非缺口任务。Task 字段使用 id,title（或name）,hours,owner_id,week_start,week_end,storyCode,depends_on,implementationStage；implementationStage 仅使用 backend/frontend/data/validation/test/documentation/configuration。
                示例：{"tasks":[{"id":"SUP-01","title":"活动详情后端接口","hours":8,"owner_id":1,"week_start":1,"week_end":1,"storyCode":"US09","depends_on":[],"implementationStage":"backend"}],"dependencies":[]}
                本批 Story 及缺口：%s
                """.formatted(gaps.size(), budget, compactStories);
    }

    private Set<String> unionStages(Set<String> present, Set<String> missing) {
        Set<String> result = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        result.addAll(present); result.addAll(missing); return result;
    }

    private Set<String> promptImplementationStages(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> stages = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (containsAny(value, "数据库", "数据表", "表结构", "schema", "dao", "repository")) stages.add("data");
        if (containsAny(value, "接口", "api", "后端", "service", "服务", "生成", "流转", "控制", "记录", "处理", "逻辑")) stages.add("backend");
        if (containsAny(value, "页面", "前端", "界面", "组件", "表单", "交互", "列表", "详情", "个人中心", "展示", "看板")) stages.add("frontend");
        if (containsAny(value, "校验", "验证", "权限", "越权", "规则", "限制", "状态流转", "异常拦截")) stages.add("validation");
        if (containsAny(value, "测试", "验收")) stages.add("test");
        return stages;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    static final class DetailedSupplementFailure extends AgentError {
        private final ObjectNode audit;

        DetailedSupplementFailure(String code, String message, ObjectNode audit) {
            super(code, message);
            this.audit = audit.deepCopy();
        }

        ObjectNode audit() { return audit.deepCopy(); }
    }

    private ObjectNode generateSingle(String projectId, String request, String mode, ModelClient client,
                                      String completenessFeedback, boolean resilient,
                                      Consumer<TruncationInfo> truncationReporter) throws AgentError {
        String prompt = """
                你是爱管理 Project Generator。只生成项目规划草案，不修改数据库。
                输出严格 JSON，不要 Markdown，字段必须包含 project,members,sprints,activities,epics,stories,tasks,dependencies,milestones,uml。
                activities 和 epics 必须根据当前项目的业务功能动态生成，优先生成 3-5 个项目专属功能域；禁止复用“进入与组织项目、梳理需求、安排与执行、观察与协同、复盘改进”等通用模板。
                epics 每项使用 id,title,activity；activities 每项使用 id,name。每个 Story 的 activity 必须指向它所属 Epic 的 activity 编号。
                stories 每项使用 id,title,description,acceptance,priority(Must/Should/Could),sprint,activity(1-5),owner_id(现有成员编号或 null)。
                tasks 每项使用 id,name,owner_id,hours,week_start,week_end,story_ref,depends_on。
                不要由模型随意决定项目规模：根据 Sprint 数、实际功能域、项目复杂度和成员数动态拆分。对于 3 Sprint 且包含多个功能域的普通课程级软件项目，Story 目标约 25 条、合理范围 24-30 条，少于 22 条必须补全；22-23 条只有在功能域和 Sprint 分布充分时才可保留。每个 Sprint 都应至少约占总 Story 的 25%%，但要按真实开发节奏组织基础能力、核心流程、高级功能、管理、测试和验收，不得机械平均。若项目确实简单，可低于该规模，但要在 project.complexity_note 中说明依据。
                Story 要覆盖真实功能域并补齐完整用户故事、验收条件、优先级、Sprint、Epic 和负责人建议；Task 要从 Story 拆为分析、数据、接口、页面、联调、测试、修复或上线等实际工作（按项目需要取舍），不要复制 Story 标题作为大任务。
                至少为 3 Sprint 项目规划 2-3 个与真实 Task 关联的 Milestone；核心 Story 必须有 Task，任务依赖应覆盖真实的设计→实现→联调→测试顺序，不能全部为空。
                每个 task 都必须显式输出 depends_on；推荐使用前置 Task ID 数组，例如 ["T01","T02"]，没有可靠前置任务时输出 []，不得省略该字段。
                同一 Story 内应按真实交付顺序判断依赖，优先考虑：数据库/接口基础 → 后端服务 → 前端接入 → 联调 → 测试。只有存在业务先后关系时才添加，禁止随机跨 Story、禁止自依赖、禁止循环依赖。
                顶层 dependencies 使用 {"from":"前置Task ID","to":"后置Task ID"}，并且必须与各 task 的 depends_on 一致；没有依赖时输出空数组。
                任务周次必须在 W1-W6，并且必须落在所属 Story 的 Sprint 周范围内；优先级高的故事尽量早安排，后置任务开始周不得早于前置任务结束周；按成员容量分散同周任务，只能使用给出的现有成员编号。
                如果用户明确给出 Sprint1、Sprint2、Sprint3，则只能生成这 3 个 Sprint，不要凭空增加 Sprint；project.total_sprints 必须与用户明确的 Sprint 数一致。
                如果用户在各 Sprint 下明确列出任务或 Story，stories[].sprint 必须严格采用该分组；不得把未列在 Sprint 1 的故事默认放入 Sprint 1。
                这是模式：%s。用户输入：%s
                现有成员：%s
                """.formatted(mode, request, membersJson(projectId));
        if (completenessFeedback != null && !completenessFeedback.isBlank()) {
            prompt = prompt + "\n这是对上一轮草案的完整度检查反馈。请保留用户明确内容，只补充缺失项，避免重复已有 Story/Task：\n" + completenessFeedback;
        }
        List<JsonNode> messages = new ArrayList<>();
        ObjectNode system = mapper.createObjectNode().put("role", "system").put("content", prompt);
        messages.add(system);
        ModelClient.Completion completion = resilient
                ? client.completeAllowIncomplete(messages, List.of(), true, AgentProperties.PROJECT_GENERATION_MAX_TOKENS)
                : client.complete(messages, List.of(), true);
        ObjectNode draft = completion.incomplete()
                ? completeSingleContinuation(client, mode, prompt, completion, truncationReporter)
                : parse(completion.message().path("content").asText("{}"));
        applyExplicitSprintDistribution(draft, request);
        applyExplicitTaskConstraints(draft, request);
        return draft;
    }

    public record ResponseDiagnostic(String stageName, String modelName, String finishReason,
                                     int responseLength, Map<String, Integer> usage,
                                     String responseSha256, boolean jsonCleaningApplied,
                                     String firstParseResult, int retryCount, String errorType,
                                     int attempt, String status,
                                     int parsedStories, int parsedTasks, int parsedActivities,
                                     int parsedEpics, int parsedMilestones, int continuationRound,
                                     Integer beforeMergeStories, Integer afterMergeStories,
                                     Integer beforeMergeTasks, Integer afterMergeTasks,
                                     int storiesStageAttemptCount, int storiesStageRetryCount,
                                     int firstParsedStories, String firstErrorCode,
                                     int retryParsedStories, String retryErrorCode,
                                     boolean retrySucceeded) { }

    record ParseResult(ObjectNode value, String errorType, boolean jsonCleaningApplied) {
        boolean success() { return value != null; }
    }

    private ObjectNode completeSingleContinuation(ModelClient client, String mode, String originalPrompt,
                                                  ModelClient.Completion first,
                                                  Consumer<TruncationInfo> reporter) throws AgentError {
        ObjectNode completed = recoverCompleteDraftItems(first.message().path("content").asText(""));
        for (int continuationIndex = 1; continuationIndex <= 2; continuationIndex++) {
            String retryPrompt = missingOnlyContinuationPrompt(mode, originalPrompt, completed);
            ModelClient.Completion retry = completePrompt(client, retryPrompt);
            ObjectNode recoveredThisRound = recoverCompleteDraftItems(retry.message().path("content").asText(""));
            if (!retry.incomplete()) {
                try {
                    recoveredThisRound = parse(retry.message().path("content").asText("{}"));
                } catch (AgentError ignored) {
                    // Keep any complete arrays recovered from a malformed tail.
                }
            }
            ObjectNode merged = mergeRecoveredDraft(completed, recoveredThisRound);
            boolean usable = hasUsableSkeleton(merged);
            reporter.accept(truncationInfo(mode, first, retry, continuationIndex, usable, completed, merged));
            completed = merged;
            if (!retry.incomplete()) return merged;
            if (continuationIndex == 2 && usable) return merged;
        }
        throw new AgentError("incomplete_response", "模型补全输出再次被截断或中断，且未恢复出可用项目骨架");
    }

    private String missingOnlyContinuationPrompt(String mode, String originalPrompt, ObjectNode completed) {
        String priorities = "excel".equalsIgnoreCase(mode)
                ? "只补导入草案缺失的字段和条目，保护已有 Story/Task。"
                : "优先补缺失 Task、Story-Task 关系、owner、hours、week、依赖和 Milestone；已有 Story 不得重复生成。";
        return "上一次 " + mode + " 模式输出被截断。只输出当前尚未出现的数组条目或缺失字段，不重新输出完整项目，不使用 Markdown。\n"
                + "已有条目摘要（不得重复 id/code/title/name）：" + completedDraftSummary(completed) + "\n"
                + priorities + "\n输出严格、紧凑 JSON；仅返回仍缺失的 members、sprints、activities、epics、stories、tasks、milestones、dependencies。\n"
                + "原请求规则：" + originalPrompt;
    }

    private boolean hasUsableSkeleton(ObjectNode draft) {
        return draft.path("stories").isArray() && draft.path("stories").size() > 0
                && (draft.path("tasks").isArray() && draft.path("tasks").size() > 0
                || draft.path("project").isObject());
    }

    private TruncationInfo truncationInfo(String mode, ModelClient.Completion first,
                                          ModelClient.Completion current, int index, boolean succeeded,
                                          ObjectNode recovered, ObjectNode merged) {
        String missing = missingItems(merged);
        return new TruncationInfo(mode, current.finishReason(), current.responseLength(), true, succeeded,
                merged.path("stories").size(), merged.path("stories").size(), index,
                recovered.path("tasks").size(), recovered.path("milestones").size(),
                merged.path("tasks").size(), missing);
    }

    private String missingItems(ObjectNode draft) {
        List<String> missing = new ArrayList<>();
        if (!draft.path("tasks").isArray() || draft.path("tasks").isEmpty()) missing.add("tasks");
        if (!draft.path("milestones").isArray() || draft.path("milestones").isEmpty()) missing.add("milestones");
        if (!draft.path("project").isObject()) missing.add("project");
        return String.join(",", missing);
    }

    private ObjectNode generateDetailedStages(String projectId, String request, ModelClient client,
                                              String completenessFeedback,
                                              Consumer<TruncationInfo> truncationReporter,
                                              Consumer<ResponseDiagnostic> responseReporter) throws AgentError {
        String feedback = completenessFeedback == null ? "" : completenessFeedback;
        String storyPrompt = """
                分阶段生成详细项目规划。当前是阶段1：只生成 project、members、sprints、activities、epics、stories 和 uml。
                Activity 必须包含稳定唯一 code（A1、A2……）、id、name、description；Epic.activity 和 Story.activity 统一引用 Activity.code，禁止使用数组下标、Activity 名称或混用数字 id。
                Story 应完整包含 id、title、description、acceptance、priority、sprint、activity（A1、A2……）、owner_id。
                用户明确要求约30条时生成28-32条，并保持 Sprint 均衡。tasks、dependencies、milestones 必须输出空数组。
                用户需求：%s
                完整度反馈：%s
                现有成员：%s
                输出严格 JSON，不要 Markdown。
                """.formatted(request, feedback, membersJson(projectId));
        ObjectNode storiesStage = completeStoriesStage(client, storyPrompt, truncationReporter, responseReporter);
        applyExplicitSprintDistribution(storiesStage, request);
        int taskBudget = DetailedTaskBudget.forStories(storiesStage.path("stories").size());
        int requestedSupplementBudget = supplementTaskBudget(completenessFeedback, taskBudget);

        String taskPrompt = """
                分阶段生成详细项目规划。当前是阶段2：根据给定 Story 只生成 tasks 和 dependencies。
                Task 包含 id、name、owner_id、hours、week_start、week_end、story_ref、depends_on。
                本阶段 Task 硬上限为 %d；通常每个 Story 只保留2-3个核心实现 Task，数据库/接口/页面/关键校验可按实际需要拆分，禁止为凑数重复生成同义 Task。
                生成前必须按每个 Story 的实现契约覆盖必要阶段：user-facing Story 至少覆盖 backend 和 frontend；规则/校验 Story 覆盖 backend 和 validation；data-only、test、documentation、configuration Story 只覆盖其契约阶段。不要为每个 Story 固定生成3个 Task，也不要伪造阶段标签，Task 的标题和实现内容必须真实体现对应阶段。
                负责人、工时、周次和依赖必须合理，所有引用只能使用给定 Story ID。project、members、sprints、activities、epics、stories、milestones、uml 输出空结构。
                Story 结构：%s
                补全约束：%s
                输出严格 JSON，不要 Markdown。
                """.formatted(requestedSupplementBudget, stageSummary(storiesStage, "stories"), feedback);
        ObjectNode tasksStage;
        if (!feedback.isBlank() && requestedSupplementBudget == 0) {
            tasksStage = mapper.createObjectNode();
            tasksStage.set("tasks", mapper.createArrayNode());
            tasksStage.set("dependencies", mapper.createArrayNode());
        } else {
            tasksStage = completeStage(client, "tasks", taskPrompt, truncationReporter, responseReporter);
        }
        TaskStoryReferences.canonicalize(tasksStage, storiesStage.path("stories"));
        int generatedTaskCount = tasksStage.path("tasks").size();
        int rejectedByBudget = DetailedTaskBudget.enforce(tasksStage, requestedSupplementBudget);

        String milestonePrompt = """
                分阶段生成详细项目规划。当前是阶段3：只生成2-3个 milestones，并关联真实 Task ID。
                每项包含 id、name、week、description、related_task_ids、status。其余数组输出空数组。
                Sprint：%s
                Task 摘要：%s
                输出严格 JSON，不要 Markdown。
                """.formatted(storiesStage.path("sprints"), stageSummary(tasksStage, "tasks"));
        ObjectNode milestonesStage = completeStage(client, "milestones", milestonePrompt, truncationReporter, responseReporter);
        ObjectNode merged = mergeStages(storiesStage, tasksStage, milestonesStage);
        ObjectNode taskAudit = merged.with("_task_generation_audit");
        taskAudit.put("taskBudget", taskBudget)
                .put("initialTaskCount", generatedTaskCount)
                .put("continuationMergedTaskCount", generatedTaskCount)
                .put("supplementGapCount", 0)
                .put("supplementGeneratedTaskCount", feedback.isBlank() ? 0 : generatedTaskCount)
                .put("supplementAcceptedTaskCount", feedback.isBlank() ? 0 : merged.path("tasks").size())
                .put("supplementRejectedByBudgetCount", feedback.isBlank() ? 0 : rejectedByBudget)
                .put("semanticDuplicateMergedCount", 0)
                .put("finalTaskCount", merged.path("tasks").size())
                .put("crossStageReferenceResolvedCount", sourceAwareReferenceCount(merged))
                .put("crossStageReferenceUnresolvedCount", unresolvedReferenceCount(merged));
        responseReporter.accept(mergeDiagnostic(storiesStage, tasksStage, merged));
        applyExplicitTaskConstraints(merged, request);
        return merged;
    }

    private ObjectNode completeStoriesStage(ModelClient client, String storyPrompt,
                                            Consumer<TruncationInfo> truncationReporter,
                                            Consumer<ResponseDiagnostic> responseReporter) throws AgentError {
        try {
            ObjectNode result = completeStage(client, "stories", storyPrompt, truncationReporter, responseReporter);
            int parsedStories = entityCount(result, "stories");
            ObjectNode audit = storiesStageAudit(1, 0, parsedStories, "", 0, "NOT_ATTEMPTED", false);
            result.set("_stories_stage_audit", audit);
            responseReporter.accept(storiesStageAuditDiagnostic(client, "STORIES_STAGE_ACCEPTED", audit));
            return result;
        } catch (AgentError first) {
            if (!"STORIES_STAGE_EMPTY".equals(first.getCode())) throw first;
            ObjectNode retryingAudit = storiesStageAudit(1, 1, 0, first.getCode(), 0, "NOT_ATTEMPTED", false);
            responseReporter.accept(storiesStageAuditDiagnostic(client, "STORIES_STAGE_SEMANTIC_RETRYING", retryingAudit));
            String retryPrompt = storyPrompt + """

                    上一次 stories 阶段虽然返回了可解析 JSON，但 stories 字段缺失、类型错误或数组为空。
                    仅重新生成当前 stories 阶段；不得生成 tasks、dependencies 或 milestones，也不得进入补全流程。
                    必须返回一个合法 JSON object，且必须包含非空 stories 数组。
                    同时完整保留本阶段 project、members、sprints、activities、epics、stories、uml 的详细模式契约；
                    activities、epics 和 project 基础规划不得省略或缩减业务需求。tasks、dependencies、milestones 必须为空数组。
                    这是唯一一次语义重试。输出严格 JSON，不要 Markdown。
                    """;
            try {
                ObjectNode result = retryStoriesStageOnce(client, retryPrompt, truncationReporter, responseReporter);
                int parsedStories = entityCount(result, "stories");
                ObjectNode audit = storiesStageAudit(2, 1, 0, first.getCode(), parsedStories, "", true);
                result.set("_stories_stage_audit", audit);
                responseReporter.accept(storiesStageAuditDiagnostic(client, "STORIES_STAGE_RETRY_SUCCEEDED", audit));
                return result;
            } catch (AgentError retry) {
                ObjectNode audit = storiesStageAudit(2, 1, 0, first.getCode(), 0, retry.getCode(), false);
                responseReporter.accept(storiesStageAuditDiagnostic(client, "STORIES_STAGE_RETRY_FAILED", audit));
                throw retry;
            }
        }
    }

    private ObjectNode retryStoriesStageOnce(ModelClient client, String prompt,
                                             Consumer<TruncationInfo> truncationReporter,
                                             Consumer<ResponseDiagnostic> responseReporter) throws AgentError {
        ModelClient.Completion completion = completePrompt(client, prompt);
        String content = completion.message().path("content").asText("");
        if (completion.incomplete()) {
            ParseResult incomplete = new ParseResult(null, "TRUNCATED_OR_INCOMPLETE", false);
            responseReporter.accept(diagnostic("stories", client, completion, content, incomplete,
                    incomplete.errorType(), 1, 2, "STORIES_STAGE_RETRY_INCOMPLETE", 0, null));
            truncationReporter.accept(new TruncationInfo("stories", completion.finishReason(),
                    completion.responseLength(), false));
            throw new AgentError("incomplete_response", "stories 阶段唯一一次重试响应被截断");
        }
        ParseResult parsed = parseContent(content);
        responseReporter.accept(diagnostic("stories", client, completion, content, parsed,
                parsed.errorType(), 1, 2, parsed.success() ? "STORIES_STAGE_RETRY_PARSED" : "STORIES_STAGE_RETRY_PARSE_FAILED",
                0, parsed.value()));
        if (!parsed.success()) throw parseError(parsed.errorType());
        validateStageOwnedEntities("stories", parsed.value());
        return parsed.value();
    }

    private ObjectNode storiesStageAudit(int attempts, int retries, int firstParsed, String firstError,
                                         int retryParsed, String retryError, boolean succeeded) {
        return mapper.createObjectNode().put("storiesStageAttemptCount", attempts)
                .put("storiesStageRetryCount", retries).put("firstParsedStories", firstParsed)
                .put("firstErrorCode", firstError == null ? "" : firstError)
                .put("retryParsedStories", retryParsed)
                .put("retryErrorCode", retryError == null ? "" : retryError)
                .put("retrySucceeded", succeeded);
    }

    private ResponseDiagnostic storiesStageAuditDiagnostic(ModelClient client, String status, ObjectNode audit) {
        return new ResponseDiagnostic("stories", safeModelName(client), "", 0, Map.of(), "", false,
                "SEMANTIC_VALIDATION", audit.path("storiesStageRetryCount").asInt(),
                audit.path("retryErrorCode").asText(audit.path("firstErrorCode").asText("")),
                audit.path("storiesStageAttemptCount").asInt(), status,
                audit.path("retryParsedStories").asInt(audit.path("firstParsedStories").asInt()),
                0, 0, 0, 0, 0, null, null, null, null,
                audit.path("storiesStageAttemptCount").asInt(), audit.path("storiesStageRetryCount").asInt(),
                audit.path("firstParsedStories").asInt(), audit.path("firstErrorCode").asText(""),
                audit.path("retryParsedStories").asInt(), audit.path("retryErrorCode").asText(""),
                audit.path("retrySucceeded").asBoolean(false));
    }

    private ObjectNode completeStage(ModelClient client, String stage, String prompt,
                                     Consumer<TruncationInfo> reporter,
                                     Consumer<ResponseDiagnostic> responseReporter) throws AgentError {
        responseReporter.accept(diagnostic(stage, client, null, "", null,
                "NOT_ATTEMPTED", 0, 0, "STARTED", 0, null));
        ModelClient.Completion first = completePrompt(client, prompt);
        String firstContent = first.message().path("content").asText("");
        if (!first.incomplete()) {
            ParseResult firstParse = parseContent(firstContent);
            if (firstParse.success()) {
                responseReporter.accept(diagnostic(stage, client, first, firstContent, firstParse,
                        firstParse.errorType(), 0, 1, "PARSED", 0, firstParse.value()));
                validateStageOwnedEntities(stage, firstParse.value());
                annotateTaskProvenance(firstParse.value(), stage, 0);
                resolveLocalTaskReferences(firstParse.value(), stage, 0);
                return firstParse.value();
            }
            responseReporter.accept(diagnostic(stage, client, first, firstContent, firstParse,
                    firstParse.errorType(), 1, 1, "PARSE_FAILED_RETRYING", 0, firstParse.value()));

            ModelClient.Completion repaired = completePrompt(client, jsonRepairPrompt(stage, firstParse.errorType()));
            String repairedContent = repaired.message().path("content").asText("");
            if (repaired.incomplete()) {
                ParseResult incomplete = new ParseResult(null, "TRUNCATED_OR_INCOMPLETE", false);
                responseReporter.accept(diagnostic(stage, client, repaired, repairedContent, incomplete,
                        firstParse.errorType(), 1, 2, "RETRY_INCOMPLETE", 0, null));
                reporter.accept(new TruncationInfo(stage, repaired.finishReason(), repaired.responseLength(), false));
                throw new AgentError("incomplete_response", "模型修复响应被截断，项目规划未能完整生成");
            }
            ParseResult repairedParse = parseContent(repairedContent);
            responseReporter.accept(diagnostic(stage, client, repaired, repairedContent, repairedParse,
                    firstParse.errorType(), 1, 2, repairedParse.success() ? "RETRY_SUCCEEDED" : "RETRY_EXHAUSTED",
                    0, repairedParse.value()));
            if (repairedParse.success()) {
                validateStageOwnedEntities(stage, repairedParse.value());
                annotateTaskProvenance(repairedParse.value(), stage, 0);
                resolveLocalTaskReferences(repairedParse.value(), stage, 0);
                return repairedParse.value();
            }
            throw new AgentError("retry_exhausted", "模型连续两次未返回合法项目规划 JSON（RETRY_EXHAUSTED）");
        }

        responseReporter.accept(diagnostic(stage, client, first, firstContent,
                new ParseResult(null, "TRUNCATED_OR_INCOMPLETE", false),
                "TRUNCATED_OR_INCOMPLETE", 0, 1, "CONTINUATION_REQUIRED", 0, null));
        reporter.accept(new TruncationInfo(stage, first.finishReason(), first.responseLength(), true));
        ArrayNode completedItems = extractCompleteStageItems(firstContent, stage);
        annotateTaskProvenance(completedItems, stage, 0);
        if ("tasks".equals(stage)) resolveLocalTaskReferences(completedItems, stage, 0);
        String retryPrompt = """
                上一次%s阶段因输出长度被截断。只补充当前阶段尚未完整返回的条目，不重新生成已完成条目，不生成其他阶段，不使用 Markdown。
                已完整接收的条目摘要如下；不得重复这些 id 或 title/name：%s
                输出一个严格、紧凑的 JSON 对象，%s 数组只包含缺失条目。结构字段要求：%s
                tasks 阶段的 dependencies 可以包含完整依赖集合，便于最终校验。
                原阶段要求：%s
                """.formatted(stage, completedItemSummary(completedItems), stage,
                continuationStructureInstruction(stage), prompt);
        ModelClient.Completion retry = completePrompt(client, retryPrompt);
        String continuationContent = retry.message().path("content").asText("");
        if (retry.incomplete()) {
            responseReporter.accept(diagnostic(stage, client, retry, continuationContent,
                    new ParseResult(null, "TRUNCATED_OR_INCOMPLETE", false), "TRUNCATED_OR_INCOMPLETE",
                    0, 2, "CONTINUATION_INCOMPLETE", 1, null));
            reporter.accept(new TruncationInfo(stage, retry.finishReason(), retry.responseLength(), false));
            throw new AgentError("incomplete_response", "模型输出再次被截断或中断，项目规划未能完整生成");
        }
        ParseResult continuationParse = parseContent(continuationContent);
        if (!continuationParse.success()) {
            responseReporter.accept(diagnostic(stage, client, retry, continuationContent, continuationParse,
                    continuationParse.errorType(), 0, 2, "CONTINUATION_PARSE_FAILED", 1, null));
        }
        if (!continuationParse.success()) throw parseError(continuationParse.errorType());
        ObjectNode remaining = continuationParse.value();
        annotateTaskProvenance(remaining, stage, 1);
        resolveLocalTaskReferences(remaining, stage, 1);
        if ("tasks".equals(stage)) namespaceContinuationTasks(completedItems, remaining, 1);
        ObjectNode combined = remaining.deepCopy();
        combined.set(stage, completedItems.deepCopy());
        mergeUnique(combined, remaining, stage);
        responseReporter.accept(diagnostic(stage, client, retry, continuationContent, continuationParse,
                continuationParse.errorType(), 0, 2, "CONTINUATION_PARSED", 1, combined));
        validateStageOwnedEntities(stage, combined);
        return combined;
    }

    private void validateStageOwnedEntities(String stage, ObjectNode draft) throws AgentError {
        String key = switch (stage) {
            case "stories" -> "stories";
            case "tasks" -> "tasks";
            case "milestones" -> "milestones";
            default -> null;
        };
        if (key == null) return;
        JsonNode rows = draft.get(key);
        if (rows != null && rows.isArray() && java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .anyMatch(JsonNode::isObject)) return;
        String code = stage.toUpperCase(Locale.ROOT) + "_STAGE_EMPTY";
        String reason = rows == null ? "缺少 " + key + " 字段"
                : !rows.isArray() ? key + " 字段不是数组" : key + " 数组为空";
        throw new AgentError(code, stage + " 阶段生成失败：" + reason + "（" + code + "）");
    }

    private ModelClient.Completion completePrompt(ModelClient client, String prompt) throws AgentError {
        List<JsonNode> messages = new ArrayList<>();
        messages.add(mapper.createObjectNode().put("role", "system").put("content", prompt));
        return client.completeAllowIncomplete(messages, List.of(), true, AgentProperties.PROJECT_GENERATION_MAX_TOKENS);
    }

    private boolean requiresStagedGeneration(String request) {
        String value = request == null ? "" : request;
        return value.length() >= 2000 || Pattern.compile("(?i)(?:约|至少|不少于)?\\s*(?:2[8-9]|3[0-2])\\s*(?:个|条)?\\s*(?:story|用户故事)").matcher(value).find();
    }

    private ArrayNode extractCompleteStageItems(String partial, String stage) {
        ArrayNode completed = mapper.createArrayNode();
        if (partial == null || partial.isBlank()) return completed;
        try (JsonParser parser = mapper.getFactory().createParser(partial)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME || !stage.equals(parser.currentName())) continue;
                if (parser.nextToken() != JsonToken.START_ARRAY) return completed;
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode item = mapper.readTree(parser);
                    if (item != null && item.isObject()) completed.add(item);
                }
                return completed;
            }
        } catch (Exception ignored) {
            // A truncated JSON tail is expected; only fully parsed array items are retained.
        }
        return completed;
    }

    private String completedItemSummary(ArrayNode completedItems) {
        ArrayNode summary = mapper.createArrayNode();
        for (JsonNode item : completedItems) {
            summary.add(mapper.createObjectNode()
                    .put("id", item.path("id").asText(""))
                    .put("title", item.path("title").asText(item.path("name").asText(""))));
        }
        return summary.toString();
    }

    private void annotateTaskProvenance(ObjectNode draft, String stage, int continuationRound) {
        if (!draft.path("tasks").isArray()) return;
        annotateTaskProvenance((ArrayNode) draft.path("tasks"), stage, continuationRound);
    }

    private void annotateTaskProvenance(ArrayNode tasks, String stage, int continuationRound) {
        if (!"tasks".equalsIgnoreCase(stage)) return;
        for (JsonNode node : tasks) {
            if (!(node instanceof ObjectNode task)) continue;
            task.put("model_task_code", task.path("model_task_code").asText(task.path("id").asText("")))
                    .put("generation_stage", stage)
                    .put("generation_continuation_round", continuationRound)
                    .put("generation_identity", stage + "#" + continuationRound + "#"
                            + task.path("model_task_code").asText(task.path("id").asText("")));
            ArrayNode sources = task.withArray("generation_sources");
            ObjectNode source = mapper.createObjectNode()
                    .put("stage", stage)
                    .put("continuationRound", continuationRound)
                    .put("modelTaskCode", task.path("model_task_code").asText())
                    .put("identity", task.path("generation_identity").asText());
            boolean exists = false;
            for (JsonNode item : sources) if (item.path("identity").asText().equals(source.path("identity").asText())) { exists = true; break; }
            if (!exists) sources.add(source);
        }
    }

    private int supplementTaskBudget(String feedback, int normalBudget) {
        if (feedback == null || feedback.isBlank()) return normalBudget;
        Matcher matcher = Pattern.compile("TASK_SUPPLEMENT_BUDGET=(\\d+)").matcher(feedback);
        return matcher.find() ? Math.min(normalBudget, Integer.parseInt(matcher.group(1))) : normalBudget;
    }

    private void resolveLocalTaskReferences(ObjectNode draft, String stage, int round) {
        if (!draft.path("tasks").isArray()) return;
        TaskIdentityReferences.resolve((ArrayNode) draft.path("tasks"),
                draft.path("milestones").isArray() ? (ArrayNode) draft.path("milestones") : mapper.createArrayNode(),
                draft.path("dependencies").isArray() ? (ArrayNode) draft.path("dependencies") : mapper.createArrayNode(), true);
    }

    private void resolveLocalTaskReferences(ArrayNode tasks, String stage, int round) {
        TaskIdentityReferences.resolve(tasks, mapper.createArrayNode(), mapper.createArrayNode(), true);
    }

    private Map<String, List<String>> localTaskIdentities(ArrayNode tasks) {
        Map<String, List<String>> identities = new LinkedHashMap<>();
        for (JsonNode task : tasks) {
            String identity = task.path("generation_identity").asText("");
            for (String code : List.of(task.path("model_task_code").asText(""), task.path("id").asText(""))) {
                code = code.trim().toUpperCase(Locale.ROOT);
                if (!code.isBlank() && !identity.isBlank()) {
                    List<String> values = identities.computeIfAbsent(code, ignored -> new ArrayList<>());
                    if (!values.contains(identity)) values.add(identity);
                }
            }
        }
        return identities;
    }

    private void resolveIdentityField(ObjectNode row, String publicField, String identityField,
                                      Map<String, List<String>> identities) {
        String code = row.path(publicField).asText("").trim().toUpperCase(Locale.ROOT);
        List<String> candidates = identities.get(code);
        if (candidates != null && candidates.size() == 1) row.put(identityField, candidates.get(0));
        else if (!code.isBlank()) row.put("unresolved_" + publicField + "_reference", code);
    }

    private void namespaceContinuationTasks(ArrayNode completedTasks, ObjectNode remaining, int round) {
        int max = 0;
        for (JsonNode task : completedTasks) {
            Matcher matcher = Pattern.compile("(?i)^T(\\d+)$").matcher(task.path("id").asText(""));
            if (matcher.matches()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        Map<String,String> aliases = new LinkedHashMap<>();
        for (JsonNode node : remaining.path("tasks")) {
            ObjectNode task = (ObjectNode) node;
            String modelCode = task.path("id").asText("").trim().toUpperCase(Locale.ROOT);
            String finalCode = String.format("T%02d", ++max);
            if (!modelCode.isBlank() && aliases.putIfAbsent(modelCode, finalCode) != null)
                task.withArray("unresolved_cross_stage_references").add(modelCode);
            task.put("model_task_code", modelCode).put("id", finalCode)
                    .put("generation_stage", "tasks").put("generation_continuation_round", round)
                    .put("generation_identity", "tasks#" + round + "#" + modelCode);
        }
        for (JsonNode node : remaining.path("tasks")) rewriteContinuationRefs((ObjectNode) node, "depends_on", aliases);
        for (JsonNode edgeNode : remaining.path("dependencies")) {
            if (!(edgeNode instanceof ObjectNode edge)) continue;
            for (String field : List.of("from", "to")) {
                String ref = edge.path(field).asText("").trim().toUpperCase(Locale.ROOT);
                if (aliases.containsKey(ref)) edge.put(field, aliases.get(ref));
                else if (!ref.isBlank()) edge.put("unresolved_" + field + "_reference", ref).put(field, "");
            }
        }
    }

    private void rewriteContinuationRefs(ObjectNode task, String field, Map<String,String> aliases) {
        LinkedHashSet<String> rewritten = new LinkedHashSet<>();
        for (String ref : task.path(field).asText("").split("[,，;；\\s]+")) {
            if (ref.isBlank()) continue;
            String canonical = ref.trim().toUpperCase(Locale.ROOT);
            if (aliases.containsKey(canonical)) rewritten.add(aliases.get(canonical));
            else task.withArray("unresolved_cross_stage_references").add(canonical);
        }
        task.put(field, String.join(",", rewritten));
    }

    private String continuationStructureInstruction(String stage) {
        return switch (stage) {
            case "stories" -> "完整输出 project、members、sprints、activities、epics 和 uml；tasks、dependencies、milestones 为空数组。";
            case "tasks" -> "完整输出 dependencies；除 tasks 外的其他实体数组为空，project 和 uml 为空对象。";
            case "milestones" -> "除 milestones 外的实体数组为空，project 和 uml 为空对象。";
            default -> "保留原阶段合并所需的最小结构。";
        };
    }

    private ObjectNode recoverCompleteDraftItems(String partial) {
        ObjectNode recovered = mapper.createObjectNode();
        for (String key : List.of("members", "sprints", "activities", "epics", "stories", "tasks", "milestones")) {
            recovered.set(key, extractCompleteStageItems(partial, key));
        }
        return recovered;
    }

    private String completedDraftSummary(ObjectNode completed) {
        ObjectNode summary = mapper.createObjectNode();
        for (String key : List.of("members", "sprints", "activities", "epics", "stories", "tasks", "milestones")) {
            ArrayNode rows = mapper.createArrayNode();
            for (JsonNode item : completed.path(key)) {
                rows.add(mapper.createObjectNode()
                        .put("id", item.path("id").asText(item.path("code").asText("")))
                        .put("title", item.path("title").asText(item.path("name").asText(""))));
            }
            summary.set(key, rows);
        }
        return summary.toString();
    }

    private ObjectNode mergeRecoveredDraft(ObjectNode completed, ObjectNode remaining) {
        ObjectNode merged = remaining.deepCopy();
        for (String key : List.of("members", "sprints", "activities", "epics", "stories", "tasks", "milestones")) {
            ArrayNode missing = remaining.path(key).isArray()
                    ? (ArrayNode) remaining.path(key).deepCopy() : mapper.createArrayNode();
            merged.set(key, completed.path(key).isArray()
                    ? completed.path(key).deepCopy() : mapper.createArrayNode());
            ObjectNode source = mapper.createObjectNode().set(key, missing);
            mergeUnique(merged, source, key);
        }
        if (!merged.path("dependencies").isArray()) merged.set("dependencies", mapper.createArrayNode());
        if (!merged.path("project").isObject()) merged.set("project", mapper.createObjectNode());
        if (!merged.path("uml").isObject()) merged.set("uml", mapper.createObjectNode());
        return merged;
    }

    private String stageSummary(ObjectNode stage, String key) {
        ObjectNode summary = mapper.createObjectNode();
        summary.set("project", stage.path("project").deepCopy());
        summary.set("sprints", stage.path("sprints").deepCopy());
        summary.set(key, stage.path(key).deepCopy());
        return summary.toString();
    }

    private ObjectNode mergeStages(ObjectNode storiesStage, ObjectNode tasksStage, ObjectNode milestonesStage) {
        // Stage ownership is deliberate: later local stage drafts must never replace
        // project planning collections owned by the stories stage.
        ObjectNode merged = storiesStage.deepCopy();
        mergeUnique(merged, tasksStage, "tasks");
        mergeUnique(merged, milestonesStage, "milestones");
        if (tasksStage.path("dependencies").isArray()) merged.set("dependencies", tasksStage.path("dependencies").deepCopy());
        Map<String, List<String>> identities = localTaskIdentities((ArrayNode) merged.withArray("tasks"));
        for (JsonNode node : merged.withArray("milestones")) {
            if (!(node instanceof ObjectNode milestone)) continue;
            ArrayNode refs = mapper.createArrayNode();
            for (String code : referenceValues(milestone.path("related_task_ids"))) {
                List<String> candidates = identities.get(code.toUpperCase(Locale.ROOT));
                if (candidates != null && candidates.size() == 1) refs.add(candidates.get(0));
                else milestone.withArray("unresolved_cross_stage_references").add(code);
            }
            milestone.set("generation_related_task_refs", refs);
        }
        for (String key : List.of("members", "sprints", "activities", "epics", "stories", "tasks", "dependencies", "milestones"))
            if (!merged.path(key).isArray()) merged.set(key, mapper.createArrayNode());
        if (!merged.path("project").isObject()) merged.set("project", mapper.createObjectNode());
        if (!merged.path("uml").isObject()) merged.set("uml", mapper.createObjectNode());
        return merged;
    }

    private List<String> referenceValues(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isNull()) return result;
        if (value.isArray()) value.forEach(node -> { if (!node.asText("").isBlank()) result.add(node.asText().trim()); });
        else for (String ref : value.asText("").split("[,，;；\\s]+")) if (!ref.isBlank()) result.add(ref.trim());
        return result;
    }

    private int sourceAwareReferenceCount(JsonNode draft) {
        int count = 0;
        for (JsonNode task : draft.path("tasks")) count += task.path("generation_dependency_refs").size();
        for (JsonNode milestone : draft.path("milestones")) count += milestone.path("generation_related_task_refs").size();
        for (JsonNode edge : draft.path("dependencies")) {
            if (edge.hasNonNull("generation_from_ref")) count++;
            if (edge.hasNonNull("generation_to_ref")) count++;
        }
        return count;
    }

    private int unresolvedReferenceCount(JsonNode draft) {
        int count = 0;
        for (JsonNode task : draft.path("tasks")) count += task.path("unresolved_cross_stage_references").size();
        for (JsonNode milestone : draft.path("milestones")) count += milestone.path("unresolved_cross_stage_references").size();
        for (JsonNode edge : draft.path("dependencies")) {
            if (edge.hasNonNull("unresolved_from_reference")) count++;
            if (edge.hasNonNull("unresolved_to_reference")) count++;
        }
        return count;
    }

    private void mergeUnique(ObjectNode target, ObjectNode source, String key) {
        ArrayNode rows = target.withArray(key);
        Set<String> ids = new LinkedHashSet<>(), titles = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            ids.add(row.path("id").asText(row.path("code").asText("")).trim().toLowerCase());
            titles.add(row.path("title").asText(row.path("name").asText("")).trim().toLowerCase());
        }
        if (source.path(key).isArray()) for (JsonNode row : source.path(key)) {
            String id = row.path("id").asText(row.path("code").asText("")).trim().toLowerCase();
            String title = row.path("title").asText(row.path("name").asText("")).trim().toLowerCase();
            if ((!id.isBlank() && ids.contains(id)) || (!title.isBlank() && titles.contains(title))) {
                if ("tasks".equals(key)) mergeRepeatedTaskDependencies(rows, row, id, title);
                continue;
            }
            rows.add(row.deepCopy());
            if (!id.isBlank()) ids.add(id);
            if (!title.isBlank()) titles.add(title);
        }
    }

    private void mergeRepeatedTaskDependencies(ArrayNode rows, JsonNode incoming, String id, String title) {
        for (JsonNode row : rows) {
            String existingId = row.path("id").asText(row.path("code").asText("")).trim().toLowerCase();
            String existingTitle = row.path("title").asText(row.path("name").asText("")).trim().toLowerCase();
            if ((!id.isBlank() && id.equals(existingId)) || (!title.isBlank() && title.equals(existingTitle))) {
                LinkedHashSet<String> dependencies = new LinkedHashSet<>();
                addDependencies(row.path("depends_on"), dependencies);
                addDependencies(incoming.path("depends_on"), dependencies);
                ((ObjectNode) row).put("depends_on", String.join(",", dependencies));
                return;
            }
        }
    }

    private void addDependencies(JsonNode value, Set<String> target) {
        if (value == null || value.isNull()) return;
        if (value.isArray()) { value.forEach(item -> addDependencies(item, target)); return; }
        for (String item : value.asText("").split("[,，;；\\s]+")) {
            String code = item.trim().toUpperCase();
            if (!code.isBlank()) target.add(code);
        }
    }

    /** Explicit Sprint sections in the user request are authoritative over model-assigned values. */
    void applyExplicitSprintDistribution(ObjectNode draft, String request) {
        if (!draft.path("stories").isArray() || draft.path("stories").isEmpty()) return;
        Map<Integer, List<String>> explicit = sprintSections(request, 6);
        if (explicit.isEmpty()) return;

        List<JsonNode> unmatchedStories = new ArrayList<>();
        for (JsonNode story : draft.path("stories")) unmatchedStories.add(story);
        for (Map.Entry<Integer, List<String>> entry : explicit.entrySet()) {
            for (String item : entry.getValue()) {
                String normalizedItem = normalizeSprintItem(item);
                if (normalizedItem.length() < 2) continue;
                JsonNode match = null;
                int bestScore = 0;
                for (JsonNode story : unmatchedStories) {
                    String title = normalizeSprintItem(story.path("title").asText(""));
                    int score = sprintTitleScore(title, normalizedItem);
                    if (score > bestScore) {
                        bestScore = score;
                        match = story;
                    }
                }
                if (match != null && bestScore >= 80) {
                    ((ObjectNode) match).put("sprint", entry.getKey());
                    // This marker survives the merge/normalize boundary so the
                    // final quality pass never moves a Sprint explicitly named
                    // by the user in the natural-language request.
                    ((ObjectNode) match).put("_user_sprint_explicit", true);
                    unmatchedStories.remove(match);
                }
            }
        }
    }

    /** Lock only task arrangements unambiguously stated on a single user-authored line. */
    void applyExplicitTaskConstraints(ObjectNode draft, String request) {
        if (!draft.path("tasks").isArray() || request == null) return;
        for (String line : request.split("\\R")) {
            Matcher owner = EXPLICIT_OWNER.matcher(line);
            Matcher weeks = EXPLICIT_WEEKS.matcher(line);
            boolean hasOwner = owner.find(), hasWeeks = weeks.find();
            if (!hasOwner && !hasWeeks) continue;
            JsonNode match = null;
            for (JsonNode task : draft.path("tasks")) {
                String id = task.path("id").asText(task.path("code").asText(""));
                String name = task.path("name").asText(task.path("title").asText(""));
                boolean idMatch = !id.isBlank() && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(id) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE).matcher(line).find();
                if (!idMatch && (name.length() < 4 || !line.contains(name))) continue;
                if (match != null) { match = null; break; }
                match = task;
            }
            if (!(match instanceof ObjectNode task)) continue;
            if (hasOwner) {
                int id = Integer.parseInt(owner.group(1));
                if (id > 0) { task.put("owner_id", id); task.put("_user_owner_explicit", true); }
            }
            if (hasWeeks) {
                int start = Integer.parseInt(weeks.group(1)), end = Integer.parseInt(weeks.group(2));
                if (start >= 1 && start <= end && end <= 6) {
                    task.put("week_start", start); task.put("week_end", end);
                    task.put("_user_schedule_explicit", true);
                }
            }
        }
    }

    private Map<Integer, List<String>> sprintSections(String request, int sprintCount) {
        Map<Integer, List<String>> sections = new java.util.LinkedHashMap<>();
        String source = request == null ? "" : request;
        Matcher matcher = SPRINT_SECTION.matcher(source);
        List<Integer> sprints = new ArrayList<>();
        List<Integer> headingEnds = new ArrayList<>();
        List<Integer> headingStarts = new ArrayList<>();
        while (matcher.find()) {
            int sprint = Integer.parseInt(matcher.group(1));
            if (sprint < 1 || sprint > sprintCount) continue;
            sprints.add(sprint);
            headingStarts.add(matcher.start());
            headingEnds.add(matcher.end());
        }
        for (int index = 0; index < sprints.size(); index++) {
            int end = index + 1 < headingStarts.size() ? headingStarts.get(index + 1) : source.length();
            Matcher items = SPRINT_ITEM.matcher(source.substring(headingEnds.get(index), end));
            List<String> values = sections.computeIfAbsent(sprints.get(index), ignored -> new ArrayList<>());
            while (items.find()) values.add(items.group(1).split("[\u2014\u2013]{1,2}", 2)[0].trim());
        }
        return sections;
    }

    private String normalizeSprintItem(String value) {
        String normalized = String.valueOf(value).toLowerCase().replaceAll("[\\p{P}\\p{Z}\\s]+", "");
        normalized = normalized.replace("和", "与");
        return normalized.replaceAll("(开发|实现)$", "");
    }

    private int sprintTitleScore(String title, String item) {
        if (title.isEmpty() || item.isEmpty()) return 0;
        if (title.equals(item)) return 100;
        if (title.contains(item) || item.contains(title)) {
            return 90 + Math.min(title.length(), item.length()) * 10 / Math.max(title.length(), item.length());
        }
        int commonPrefix = 0;
        while (commonPrefix < title.length() && commonPrefix < item.length()
                && title.charAt(commonPrefix) == item.charAt(commonPrefix)) commonPrefix++;
        return commonPrefix * 200 / (title.length() + item.length());
    }

    private String membersJson(String projectId) { try { if (projectId != null && projectId.startsWith("NEW-")) return mapper.writeValueAsString(users.selectList(new QueryWrapper<User>().orderByAsc("id")).stream().map(u -> java.util.Map.of("id",u.getId(),"name",u.getDisplayName(),"role",u.getRole(),"capacity_hours",u.getCapacityHours())).toList()); return mapper.writeValueAsString(projectMembers.selectList(new QueryWrapper<ProjectMember>().eq("project_id", projectId).orderByAsc("user_id")).stream().map(link -> users.selectById(link.getUserId())).filter(java.util.Objects::nonNull).map(u -> java.util.Map.of("id",u.getId(),"name",u.getDisplayName(),"role",u.getRole(),"capacity_hours",u.getCapacityHours())).toList()); } catch (Exception e) { return "[]"; } }
    private ObjectNode parse(String text) throws AgentError {
        try {
            JsonNode node = mapper.readTree(text);
            if (node instanceof ObjectNode object) return object;
        } catch (Exception ignored) { }
        throw new AgentError("invalid_response", "模型没有返回合法项目规划 JSON");
    }

    ParseResult parseContent(String content) {
        if (content == null || content.isBlank()) return new ParseResult(null, "EMPTY_CONTENT", false);
        JsonNode direct = readJson(content);
        if (direct != null) return objectResult(direct, false, "DIRECT_JSON");

        boolean cleaned = false;
        Matcher fence = JSON_FENCE.matcher(content);
        if (fence.matches()) {
            String unfenced = fence.group(1);
            JsonNode fencedJson = readJson(unfenced);
            cleaned = true;
            if (fencedJson != null) return objectResult(fencedJson, true, "MARKDOWN_FENCE_REMOVED");

            String object = extractFirstJsonObject(unfenced);
            if (object != null) {
                JsonNode extracted = readJson(object);
                if (extracted != null) return objectResult(extracted, true, "JSON_OBJECT_EXTRACTED");
            }
            return new ParseResult(null, hasObjectStart(unfenced) ? "INVALID_JSON_SYNTAX" : "NO_JSON_OBJECT_FOUND", true);
        }

        String object = extractFirstJsonObject(content);
        if (object == null) return new ParseResult(null,
                hasObjectStart(content) ? "INVALID_JSON_SYNTAX" : "NO_JSON_OBJECT_FOUND", false);
        JsonNode extracted = readJson(object);
        if (extracted != null) return objectResult(extracted, cleaned || !object.equals(content), "JSON_OBJECT_EXTRACTED");
        return new ParseResult(null, "INVALID_JSON_SYNTAX", cleaned || !object.equals(content));
    }

    private ParseResult objectResult(JsonNode node, boolean cleaned, String parseResult) {
        if (node instanceof ObjectNode object) return new ParseResult(object, parseResult, cleaned);
        return new ParseResult(null, "NON_OBJECT_TOP_LEVEL", cleaned);
    }

    private JsonNode readJson(String text) {
        try { return mapper.readTree(text); }
        catch (Exception ignored) { return null; }
    }

    private boolean hasObjectStart(String text) { return text != null && text.indexOf('{') >= 0; }

    private String extractFirstJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return null;
    }

    private AgentError parseError(String type) {
        return new AgentError("invalid_response", "模型响应格式错误（" + type + "）");
    }

    private String jsonRepairPrompt(String stage, String errorType) {
        return "上一响应无法解析为合法 JSON 对象（错误类型：" + errorType + "）。请按当前阶段重新输出：" + stage
                + "。只返回一个合法 JSON 对象，不要 Markdown 或说明文字，不要省略字段，不要输出数组作为顶层；"
                + "保持当前阶段业务目标一致。JSON Schema/结构要求：" + continuationStructureInstruction(stage);
    }

    private String safeModelName(ModelClient client) {
        try {
            String model = client.effectiveModelName();
            return model == null || model.isBlank() ? "unknown" : model;
        } catch (RuntimeException ignored) { return "unknown"; }
    }

    private ResponseDiagnostic diagnostic(String stage, ModelClient client, ModelClient.Completion completion,
                                          String content, ParseResult result, String firstParseResult,
                                          int retries, int attempt, String status,
                                          int continuationRound, ObjectNode parsed) {
        String error = result == null || result.success() ? "" : result.errorType();
        if ("RETRY_EXHAUSTED".equals(status)) error = "RETRY_EXHAUSTED";
        return new ResponseDiagnostic(stage, safeModelName(client),
                completion == null ? "" : completion.finishReason(),
                completion == null ? 0 : completion.responseLength(),
                completion == null ? Map.of() : completion.usage(), sha256(content),
                result != null && result.jsonCleaningApplied(), firstParseResult, retries, error, attempt, status,
                entityCount(parsed, "stories"), entityCount(parsed, "tasks"), entityCount(parsed, "activities"),
                entityCount(parsed, "epics"), entityCount(parsed, "milestones"), continuationRound,
                null, null, null, null,
                0, 0, 0, "", 0, "", false);
    }

    private ResponseDiagnostic mergeDiagnostic(ObjectNode storiesStage, ObjectNode tasksStage, ObjectNode merged) {
        return new ResponseDiagnostic("staged_merge", "internal", "", 0, Map.of(), "", false,
                "NOT_APPLICABLE", 0, "", 0, "MERGED",
                entityCount(merged, "stories"), entityCount(merged, "tasks"), entityCount(merged, "activities"),
                entityCount(merged, "epics"), entityCount(merged, "milestones"), 0,
                entityCount(storiesStage, "stories"), entityCount(merged, "stories"),
                entityCount(tasksStage, "tasks"), entityCount(merged, "tasks"),
                0, 0, 0, "", 0, "", false);
    }

    private int entityCount(ObjectNode draft, String key) {
        return draft != null && draft.path(key).isArray() ? draft.path(key).size() : 0;
    }

    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
