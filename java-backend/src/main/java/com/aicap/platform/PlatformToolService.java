package com.aicap.platform;

import com.aicap.common.AgentError;
import com.aicap.common.ApiException;
import com.aicap.entity.PlanningAgentRun;
import com.aicap.entity.ProfileAgentRun;
import com.aicap.entity.User;
import com.aicap.planning.PlanningAgentJobs;
import com.aicap.profile.ProfileAgentJobs;
import com.aicap.profile.ProfileToolRegistry;
import com.aicap.service.ProfileAgentService;
import com.aicap.rag.Hit;
import com.aicap.rag.RagProperties;
import com.aicap.rag.RetrievalContext;
import com.aicap.rag.RetrievalService;
import com.aicap.service.ProjectAccessService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 平台工具调用的统一入口(S3)。
 *
 * <p>设计要点(见 {@code docs/主管智能体_落地方案_v1.0.md} §2):
 *
 * <ol>
 *   <li><b>一个端点调所有工具</b>,而不是一个工具一个 RESTful 资源 ——
 *       两个注册表的调用签名本来都是 {@code (name, args) → result},统一端点零转换;
 *       新增工具不需要改跨进程契约。</li>
 *   <li><b>业务失败用 200 + ok=false 表达</b>,不用 4xx/5xx。工具失败(参数不合法、
 *       上游超时)是编排层要<b>喂回给模型</b>的一等观察结果,若按 HTTP 错误返回,
 *       上游客户端会走异常分支,把「把错误文本交给模型自我纠正」这条路径丢掉。
 *       协议级错误(未登录/越权/工具不存在)仍然走标准状态码。</li>
 *   <li><b>权限只来自 JWT</b>:入参里的 userId / owner_id 一律是<b>查询目标</b>,
 *       不是调用者身份 —— 工具接口不提供 asUserId / onBehalfOf(规划 §3.2)。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformToolService {

    /** 查询词上限:与 {@code KnowledgeController#MAX_QUERY_CHARS} 同值,两个入口对"什么算合法查询"必须给同一个答案 */
    private static final int MAX_QUERY_CHARS = 500;
    /**
     * 单次工具返回里所有 chunk 正文的<b>合计</b>字符预算。
     *
     * <p>单条上限挡不住总量:top 最多 50,50 × 600 = 3 万字符 ≈ 2 万 token,一次
     * 工具调用就能把模型的上下文窗口吃掉一大半,而 DeepSeek 当前档位是推理模型
     * (慢 + 按 token 计费)。按默认 finalTopK=5 × 600 取 3000,即"满额 5 条不打折,
     * 超额则后面的只给引用、不给正文"。
     */
    private static final int MAX_TOTAL_CONTENT_CHARS = 3000;

    /**
     * 统一响应信封。字段恒输出(含 null),与项目其余接口一致。
     *
     * <p>字段名显式写成 snake_case:Jackson 没配全局命名策略,record 字段会原样输出成
     * camelCase,而本项目对外 JSON 一律 snake_case(其余接口靠手写 Map 或
     * {@code @JsonProperty} 达成)。不标注就会分叉 —— 契约文档写的是
     * {@code latency_ms},实际却回 {@code latencyMs},编排层按文档取字段会拿到 null。
     */
    public record ToolOutcome(String tool,
                              boolean ok,
                              JsonNode result,
                              JsonNode retrieval,
                              List<String> limitations,
                              @JsonProperty("latency_ms") long latencyMs,
                              @JsonProperty("error_code") String errorCode,
                              @JsonProperty("error_message") String errorMessage) {
    }

    private final PlatformToolCatalog catalog;
    private final MeetingTools meetingTools;
    private final ProfileToolRegistry profileTools;
    private final RetrievalService retrievalService;
    private final RagProperties props;
    private final ObjectMapper mapper;
    private final ProjectAccessService projectAccess;

    /**
     * 会议分析的触发面(S5′ 触发型工具)。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入:{@code agent/} 退役后这个 Bean 会消失,
     * 那时该工具应如实报"不可用",而不是让整个平台工具层装配失败 —— 这正是
     * {@link MeetingAnalysisTrigger} 存在的理由(平台层不得编译期依赖 {@code agent/})。
     */
    private final ObjectProvider<MeetingAnalysisTrigger> meetingTriggers;

    /** 规划草案的触发面。planning 不是 agent 包,可以直接依赖 */
    private final PlanningAgentJobs planningJobs;

    /**
     * 画像分析的触发面。与 planning 同形:只建 run,执行交给 {@code ProfileWorkerTask}。
     * 画像域此前缺后台消费者,第三枚触发工具才一直"暂缓"。
     */
    private final ProfileAgentJobs profileJobs;

    /** 入队画像 run 用;它内部的限流与范围校验失败要转成 ok=false,故直接依赖服务 */
    private final ProfileAgentService profileService;

    /**
     * 调用一个工具。
     *
     * @param caller 已经过 {@code Roles.writer()} 校验的调用者;工具内部还会再查一次库,
     *               因为在 LLM 长循环里用户角色可能在调用途中被改掉(既有约定,不改)
     * @throws ApiException 工具名不存在(404)
     */
    public ToolOutcome invoke(String toolName, JsonNode args, User caller) {
        PlatformToolCatalog.ToolSpec spec = catalog.find(toolName);
        if (spec == null) {
            throw ApiException.notFound("未知工具: " + toolName);
        }
        long started = System.currentTimeMillis();
        JsonNode safeArgs = args == null || args.isNull() ? mapper.createObjectNode() : args;
        try {
            String projectId = projectContext(safeArgs);
            return switch (spec.agent()) {
                case PlatformToolCatalog.AGENT_MEETING ->
                        success(toolName, meetingTools.execute(toolName, safeArgs, caller.getId(), requireProject(projectId)),
                                null, started);
                case PlatformToolCatalog.AGENT_PROFILE -> invokeProfile(toolName, safeArgs, requireProject(projectId), started);
                case PlatformToolCatalog.AGENT_PLATFORM -> invokePlatform(toolName, safeArgs, caller, requireProject(projectId), started);
                default -> throw ApiException.notFound("未知工具: " + toolName);
            };
        } catch (AgentError e) {
            // MeetingTools 的参数校验与权限复核都抛 AgentError;对编排层是"可纠正的观察",
            // 不是服务的错 —— 所以走 ok=false 而不是 5xx
            return failure(toolName, e.getCode(), e.getMessage(), started);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("platform tool {} failed: {}", toolName, e.toString());
            return failure(toolName, "internal_error", "工具执行失败,请稍后重试", started);
        }
    }

    private ToolOutcome invokeProfile(String toolName, JsonNode args, String projectId, long started) {
        ProfileToolRegistry.ToolCall call = profileTools.invoke(toolName, write(args), projectId);
        if (!call.ok()) {
            return failure(toolName, call.error() == null ? "tool_failed" : call.error(),
                    readable(call.resultJson()), started);
        }
        return success(toolName, read(call.resultJson()), null, started);
    }

    private String projectContext(JsonNode args) {
        String projectId = args.path("project_id").asText("").trim();
        return projectId.isEmpty() ? args.path("projectId").asText("").trim() : projectId;
    }

    private String requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) return projectId;
        String scoped = ProjectAccessService.normalize(projectId);
        projectAccess.requireMember(scoped);
        return scoped;
    }

    private ToolOutcome invokePlatform(String toolName, JsonNode args, User caller, String projectId, long started) {
        if (PlatformToolCatalog.SEMANTIC_SEARCH.equals(toolName)) {
            return semanticSearch(args, caller, started);
        }
        if (PlatformToolCatalog.START_MEETING_ANALYSIS.equals(toolName)) {
            return startMeetingAnalysis(args, caller, started);
        }
        if (PlatformToolCatalog.START_PLAN_DRAFT.equals(toolName)) {
            return startPlanDraft(args, caller, projectId, started);
        }
        if (PlatformToolCatalog.START_PROFILE_ANALYSIS.equals(toolName)) {
            return startProfileAnalysis(args, caller, projectId, started);
        }
        throw ApiException.notFound("未知工具: " + toolName);
    }

    /**
     * 触发一次会议分析(S5′ 触发型工具,契约 §2.4)。**只创建 run**,不写任何业务表。
     *
     * <p>幂等口径:底层 {@code meeting_agent_runs} 有 {@code UNIQUE KEY meeting_id} ——
     * 一个会议只会有一条 run,所以"已存在"就返回它并标 {@code deduplicated=true}
     * (不论它是 queued/running 还是已终局;要重跑终局的 run 走既有 retry 端点)。
     */
    private ToolOutcome startMeetingAnalysis(JsonNode args, User caller, long started) {
        String tool = PlatformToolCatalog.START_MEETING_ANALYSIS;
        String meetingId = args.path("meeting_id").asText("").trim();
        if (meetingId.isEmpty()) {
            return failure(tool, "invalid_arguments", "meeting_id 必填", started);
        }
        MeetingAnalysisTrigger trigger = meetingTriggers.getIfAvailable();
        if (trigger == null) {
            return failure(tool, "agent_tool_unavailable",
                    "会议智能体未启用,无法触发分析", started);
        }
        MeetingAnalysisTrigger.Triggered triggered = trigger.trigger(meetingId, caller.getId());
        return success(tool, triggerJson(triggered.runId(), triggered.status(), triggered.deduplicated()),
                null, started);
    }

    /**
     * 生成一份规划草案(S5′ 触发型工具,契约 §2.4)。**只创建 run**;
     * 草案仍需人工 confirm 才会写业务数据(见 planning 的 confirm 流程)。
     *
     * <p>幂等口径:{@code planning_agent_runs} 没有唯一键,故按"同一 {@code request_text}
     * 已有未完成 run"判定(含 {@code waiting_confirmation}:草案还在等人确认时再触发无意义)。
     */
    private ToolOutcome startPlanDraft(JsonNode args, User caller, String projectId, long started) {
        String tool = PlatformToolCatalog.START_PLAN_DRAFT;
        String request = args.path("request").asText("").trim();
        if (request.isEmpty()) {
            return failure(tool, "invalid_arguments", "request 必填", started);
        }
        if (request.length() > 2000) {
            return failure(tool, "invalid_arguments", "request 过长(上限 2000 字符)", started);
        }
        /* 多项目之后 planning run 必须落在某个项目上(project_id 就是队列的作用域),
           少了它 run 会挂到默认项目,确认时写的也是别人的项目。 */
        if (projectId == null || projectId.isBlank()) {
            return failure(tool, "invalid_arguments", "project_id 必填", started);
        }
        PlanningAgentRun existing = planningJobs.findUnfinishedByRequest(projectId, request);
        if (existing != null) {
            return success(tool, triggerJson(existing.getId(), existing.getStatus(), true), null, started);
        }
        PlanningAgentRun run = planningJobs.queue(projectId, request, caller.getId());
        return success(tool, triggerJson(run.getId(), run.getStatus(), false), null, started);
    }

    /**
     * 触发一次成员画像分析(S5′ 触发型工具,契约 §2.4)。**只创建 run**:
     * 真正的 6 次 LLM 调用由 {@code ProfileWorkerTask} 在后台跑 —— 同步跑会吃穿
     * 编排层的有界循环预算,这正是这枚工具此前"暂缓"的原因。
     *
     * <p>幂等口径:{@code profile_agent_runs} 没有唯一键,故按"同项目同范围(含同一
     * 目标成员)已有未完成 run"判定;未完成含 queued/running。
     */
    private ToolOutcome startProfileAnalysis(JsonNode args, User caller, String projectId, long started) {
        String tool = PlatformToolCatalog.START_PROFILE_ANALYSIS;
        if (projectId == null || projectId.isBlank()) {
            return failure(tool, "invalid_arguments", "project_id 必填", started);
        }
        LocalDate start = dateArg(args, "start");
        LocalDate end = dateArg(args, "end");
        if (start == null || end == null) {
            return failure(tool, "invalid_arguments", "start/end 必填,格式 yyyy-MM-dd", started);
        }
        Integer onlyUserId = args.path("user_id").isNumber() ? args.path("user_id").asInt() : null;

        ProfileAgentRun existing = profileJobs.findUnfinished(projectId, start, end, onlyUserId);
        if (existing != null) {
            return success(tool, triggerJson(existing.getId(), existing.getStatus(), true), null, started);
        }
        try {
            ProfileAgentRun run = profileService.enqueue(projectId, start, end, caller, onlyUserId);
            return success(tool, triggerJson(run.getId(), run.getStatus(), false), null, started);
        } catch (ApiException e) {
            // 范围不合法(400)与限流(422)都是"这次调用的参数问题",不是服务故障:
            // 按业务失败回给模型让它改参数重试,而不是让编排层走异常分支。
            return failure(tool, "invalid_arguments", e.getMessage(), started);
        }
    }

    /** 触发型工具的统一回执(契约 §2.4):run_id + status + 是否复用了既有 run */
    private JsonNode triggerJson(String runId, String status, boolean deduplicated) {
        ObjectNode node = mapper.createObjectNode();
        node.put("run_id", runId);
        node.put("status", status);
        node.put("deduplicated", deduplicated);
        return node;
    }

    /**
     * 语义检索。复用 {@code KnowledgeController#retrieve} 背后的同一个
     * {@link RetrievalService},并透传调用者的 {@link RetrievalContext}。
     *
     * <p>为什么要复用而不是新写一个:收紧 ACL 时,接口层、检索层、主管三者必须<b>一起</b>生效。
     * 只有一个检索入口时,"漏掉一个入口"这件事在结构上就不可能发生(规划 §3.2)。
     */
    private ToolOutcome semanticSearch(JsonNode args, User caller, long started) {
        String query = args.path("q").asText("").trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            return failure(PlatformToolCatalog.SEMANTIC_SEARCH, "invalid_tool_arguments",
                    "参数 q 长度需在 1-" + MAX_QUERY_CHARS + " 字符之间", started);
        }
        RagProperties.Retrieval cfg = props.getRetrieval();
        int top = clamp(args.path("top").asInt(cfg.getFinalTopK()), cfg.getFinalTopK());

        RetrievalContext ctx = new RetrievalContext(caller.getId(), caller.getRole());
        RetrievalService.RetrievalResult r = retrievalService.retrieve(query, ctx,
                cfg.getRoutesTopK(), cfg.getFuseTopK(), top);

        ObjectNode retrieval = mapper.createObjectNode();
        retrieval.put("query", r.query());
        retrieval.put("configured", r.configured());
        retrieval.put("vector_store", r.vectorStore());
        retrieval.put("reranker", r.reranker());
        putOrNull(retrieval, "degraded", r.degraded());
        ObjectNode routes = mapper.createObjectNode();
        for (RetrievalService.RouteResult route : r.routes()) {
            routes.put(route.name(), route.hits().size());
        }
        retrieval.set("routes", routes);
        retrieval.set("fused", refs(r.fused()));
        retrieval.set("final", refs(r.finalHits()));
        retrieval.put("latency_ms", r.latencyMs());
        putOrNull(retrieval, "message", r.message());

        ObjectNode result = mapper.createObjectNode();
        result.put("configured", r.configured());
        // 单条上限取精排那一份配置(rerank.max-content-chars),不再在工具类里另立一个 600:
        // 两个同值的常量一定会各自漂移,而"进 prompt 的正文有多长"应当只有一处口径。
        int perItem = Math.max(1, cfg.getRerank().getMaxContentChars());
        ArrayNode items = mapper.createArrayNode();
        int budget = MAX_TOTAL_CONTENT_CHARS;
        for (Hit hit : r.finalHits()) {
            ObjectNode item = mapper.createObjectNode();
            item.put("chunk_id", hit.id());
            item.put("source_type", hit.sourceType());
            item.put("source_id", hit.sourceId());
            item.put("score", round(hit.score()));
            // 预算耗尽后仍保留这条(让模型知道还有召回),但正文留空并标注原因 ——
            // 直接截断到半句话会让模型把它当成完整事实引用。
            if (budget <= 0) {
                item.put("content", "");
                item.put("content_omitted", true);
            } else {
                item.put("content", truncate(hit.rawContent(), perItem));
                budget -= perItem;
            }
            items.add(item);
        }
        result.set("items", items);
        putOrNull(result, "message", r.message());

        return new ToolOutcome(PlatformToolCatalog.SEMANTIC_SEARCH, true, result, retrieval,
                PlatformToolCatalog.LIMITATIONS, System.currentTimeMillis() - started, null, null);
    }

    // ------------------------------------------------------------------
    // 组装辅助
    // ------------------------------------------------------------------

    private ToolOutcome success(String tool, JsonNode result, JsonNode retrieval, long started) {
        return new ToolOutcome(tool, true, result, retrieval,
                PlatformToolCatalog.LIMITATIONS, System.currentTimeMillis() - started, null, null);
    }

    private ToolOutcome failure(String tool, String code, String message, long started) {
        ObjectNode result = mapper.createObjectNode();
        result.put("error", message);
        return new ToolOutcome(tool, false, result, null,
                PlatformToolCatalog.LIMITATIONS, System.currentTimeMillis() - started, code, message);
    }

    /** 只留 id 与得分,不带原文 —— 中间结果进的是调试面板,不该把大段正文塞进事件流 */
    private ArrayNode refs(List<Hit> hits) {
        ArrayNode arr = mapper.createArrayNode();
        for (Hit hit : hits) {
            ObjectNode node = mapper.createObjectNode();
            node.put("chunk_id", hit.id());
            node.put("score", round(hit.score()));
            node.put("stage", hit.stage());
            arr.add(node);
        }
        return arr;
    }

    /** 解析 yyyy-MM-dd;解析不了返回 null,由调用方统一报 invalid_arguments */
    private static LocalDate dateArg(JsonNode args, String field) {
        String raw = args.path(field).asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            // 注册表返回非 JSON 时不抛:对调用方来说它仍是一条可读的观察
            return mapper.getNodeFactory().textNode(json);
        }
    }

    private String readable(String json) {
        JsonNode node = read(json);
        String text = node.path("error").asText("");
        return text.isEmpty() ? "工具执行失败" : text;
    }

    private static void putOrNull(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static int clamp(int value, int fallback) {
        int v = value <= 0 ? fallback : value;
        return Math.min(Math.max(v, 1), 50);
    }

    /**
     * 截断到 {@code max} 字符。
     *
     * <p>末尾补 {@code …(截断)} 而不是默默切掉:正文进的是模型的上下文,若模型不知道
     * 自己看到的是半截,就会把这半截当成完整事实引用 —— 那正是"证据校验"要拦的东西。
     */
    private static String truncate(String content, int max) {
        if (content == null) {
            return "";
        }
        return content.length() <= max ? content : content.substring(0, max) + "…(截断)";
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
