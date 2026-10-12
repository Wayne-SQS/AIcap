package com.aicap.platform;

import com.aicap.agent.AgentTools;
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
 * <p>为什么要有这一层,而不是让编排层直接读 {@link AgentTools} / {@link ProfileToolRegistry}:
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

    public static final String PLATFORM_VERSION = "1.0";

    /**
     * 平台能力限制声明。主管在规划时必须把它们带进 prompt ——
     * 否则模型会基于"系统里应该有"的假设去规划一个查不到数据的步骤。
     */
    public static final List<String> LIMITATIONS = List.of(
            "未配置当前 Sprint 日期",
            "任务无状态字段;工时是计划值,不能据此计算完成度或过载",
            "所有工具只读;写操作一律经审批队列,编排层没有写通道");

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

    private final AgentTools meetingTools;
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
}
