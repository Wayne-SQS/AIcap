package com.aicap.platform;

import com.aicap.profile.ProfileToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台工具清单(S3 工具层)。
 *
 * <p>为什么要有这一层,而不是让编排层直接读 {@link MeetingTools} / {@link ProfileToolRegistry}:
 * 那两个注册表是<b>进程内</b>的 Java 对象,编排层(独立 Python 进程)看不见。
 * 本类把它们的能力<b>声明</b>成一份可下发的元数据,编排层据此决定调什么工具,
 * 加工具只改 Java,Python 侧零改动。
 *
 * <p>刻意<b>不</b>改动两个注册表的任何公开签名(项目约定):这里只读它们已经公开的
 * {@code toolDefinitions()} / {@code schemas()},把两种不同的描述形态归一成 {@link ToolSpec}。
 *
 * <p>清单里<b>只有元数据、没有一个业务字段</b> —— 这是刻意的。{@code /catalog} 对任何登录用户
 * 开放,若它顺手带上故事标题之类的内容,就等于开了一个绕过 ACL 的读口子。
 * 要数据必须走 {@code /tools/{name}},那条路径会判角色、且检索结果按调用者过滤。
 */
@Component
@RequiredArgsConstructor
public class PlatformToolCatalog {

    public static final String AGENT_MEETING = "meeting";
    public static final String AGENT_PROFILE = "profile";
    public static final String AGENT_PLATFORM = "platform";

    public static final String SEMANTIC_SEARCH = "semantic_search";

    /**
     * S5′ 触发型工具(契约 §2.4):只创建 run 落审批队列,**不写业务表**。
     *
     * <p>第三枚 {@code start_profile_analysis} **暂缓**:画像域没有后台消费者、
     * {@code runAnalysis} 是同步的,兑现不了"只建 run 就返回",会吃穿编排层的有界循环预算。
     * 缺口与两条出路已记进契约 §2.4,待拍板后再加。
     */
    public static final String START_MEETING_ANALYSIS = "start_meeting_analysis";
    public static final String START_PLAN_DRAFT = "start_plan_draft";

    /** 触发型工具的名字集合:PLAT-04 与分派共用一处,避免两处清单分叉 */
    public static final List<String> TRIGGER_TOOLS =
            List.of(START_MEETING_ANALYSIS, START_PLAN_DRAFT);

    public static final String PLATFORM_VERSION = "1.0";

    /**
     * 平台能力限制声明。主管在规划时必须把它们带进 prompt ——
     * 否则模型会基于"系统里应该有"的假设去规划一个查不到数据的步骤。
     */
    public static final List<String> LIMITATIONS = List.of(
            "未配置当前 Sprint 日期",
            "任务无状态字段;工时是计划值,不能据此计算完成度或过载",
            "读数据只读;触发型工具(start_*)只创建 run 落入审批队列,不写业务数据");

    /**
     * 一个工具的能力声明。
     *
     * @param argsSchema    结构化 JSON Schema;来源为 null 时退化为 {@code argsHint}
     * @param argsHint      自由文本参数说明(画像智能体的注册表就是这个形态,不强行改造它)
     * @param usesRetrieval 调用结果是否额外带 retrieval 块(只有走 RAG 的工具才带)
     */
    public record ToolSpec(String name,
                           String agent,
                           String description,
                           JsonNode argsSchema,
                           String argsHint,
                           boolean readOnly,
                           boolean usesRetrieval) {
    }

    private final MeetingTools meetingTools;
    private final ProfileToolRegistry profileTools;
    private final ObjectMapper mapper;

    /**
     * 归一化后的工具清单。
     *
     * <p>每次现算,不做缓存:两个来源都明确"每次单独构建,防调用方修改污染",
     * 缓存会把这份防护绕过去(缓存对象被下游改一次,后续所有调用者都拿到脏数据)。
     * 本方法只在 {@code /catalog} 与工具分派时调用,频率极低,现算的代价可以忽略。
     */
    public List<ToolSpec> specs() {
        List<ToolSpec> out = new ArrayList<>();
        for (JsonNode def : meetingTools.toolDefinitions()) {
            JsonNode fn = def.path("function");
            out.add(new ToolSpec(
                    fn.path("name").asText(),
                    AGENT_MEETING,
                    fn.path("description").asText(),
                    fn.path("parameters"),
                    null,
                    true,
                    false));
        }
        for (Map<String, String> s : profileTools.schemas()) {
            out.add(new ToolSpec(
                    s.get("name"),
                    AGENT_PROFILE,
                    s.get("description"),
                    null,
                    s.get("args"),
                    true,
                    false));
        }
        out.add(new ToolSpec(
                SEMANTIC_SEARCH,
                AGENT_PLATFORM,
                "跨故事/需求池/任务/会议/文档的语义检索,结果已按调用者角色过滤。"
                        + "用于回答「需求里是怎么写的」这类问题,而不是「有哪些需求」。",
                semanticSearchSchema(),
                null,
                true,
                true));
        out.add(new ToolSpec(
                START_MEETING_ANALYSIS,
                AGENT_PLATFORM,
                "触发一次会议分析(产出进会议建议审批队列,需人工确认)。只创建 run,不修改任何业务数据。",
                objectSchema(Map.of("meeting_id", strField("Meeting id", 1, 36)), "meeting_id"),
                null,
                false,
                false));
        out.add(new ToolSpec(
                START_PLAN_DRAFT,
                AGENT_PLATFORM,
                "为一句规划请求生成结构化修改草案(草案仍需人工确认后才执行)。只创建 run,不修改任何业务数据。",
                objectSchema(Map.of("request", strField("Planning request", 1, 2000)), "request"),
                null,
                false,
                false));
        return List.copyOf(out);
    }

    /** 找不到返回 null,由调用方决定给 404 还是别的处理 */
    public ToolSpec find(String name) {
        for (ToolSpec spec : specs()) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /** {@code GET /api/agents/catalog} 的响应体 */
    public Map<String, Object> catalogJson() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec spec : specs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", spec.name());
            item.put("agent", spec.agent());
            item.put("description", spec.description());
            item.put("args_schema", spec.argsSchema());
            item.put("args_hint", spec.argsHint());
            item.put("read_only", spec.readOnly());
            item.put("uses_retrieval", spec.usesRetrieval());
            tools.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_version", PLATFORM_VERSION);
        out.put("tools", tools);
        out.put("limitations", LIMITATIONS);
        return out;
    }

    private JsonNode semanticSearchSchema() {
        ObjectNode props = mapper.createObjectNode();
        ObjectNode q = mapper.createObjectNode();
        q.put("type", "string");
        q.put("minLength", 1);
        q.put("maxLength", 500);
        q.put("title", "Query");
        props.set("q", q);
        ObjectNode top = mapper.createObjectNode();
        top.put("type", "integer");
        top.put("minimum", 1);
        top.put("maximum", 50);
        top.put("title", "Top");
        props.set("top", top);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("q");
        schema.set("required", required);
        return schema;
    }

    // ------------------------------------------------------------------
    // 触发型工具(§2.4)的参数 schema
    // ------------------------------------------------------------------

    private JsonNode objectSchema(Map<String, JsonNode> props, String... required) {
        ObjectNode properties = mapper.createObjectNode();
        props.forEach(properties::set);
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = mapper.createArrayNode();
        for (String field : required) {
            req.add(field);
        }
        schema.set("required", req);
        return schema;
    }

    private JsonNode strField(String title, int minLength, int maxLength) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("minLength", minLength);
        node.put("maxLength", maxLength);
        node.put("title", title);
        return node;
    }
}
