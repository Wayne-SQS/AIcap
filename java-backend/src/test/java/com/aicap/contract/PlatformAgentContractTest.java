package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台工具层契约(S3):{@code GET /api/agents/catalog} 与
 * {@code POST /api/agents/tools/{toolName}}。
 *
 * <p>这一层是编排层(独立 Python 进程)与 Java 平台之间<b>唯一</b>的调用面,
 * 所以它的契约一旦走样,两边的分叉不会被任何编译错误拦下 —— 只能靠这里钉住:
 *
 * <ul>
 *   <li><b>清单只含元数据</b>(规划 §3.2:能力清单与数据分开,是为了它可被审计)。
 *       断言方式是"清单项的字段名必须全在白名单内",而不是找几个业务值的字符串
 *       —— 后者会被工具描述里出现的 ID 例子误伤(如 {@code query_tasks} 的 schema
 *       里就写着"如 T01"),那样的用例会在改文案时假红。</li>
 *   <li><b>读清单不设门槛、调工具设门槛</b>:viewer 能读 catalog 但不能调工具。</li>
 *   <li><b>业务失败是 200 + ok=false</b>,协议失败才用状态码 —— 这两类混淆会让
 *       编排层把"参数写错"当成"服务挂了",从而放弃自我纠正。</li>
 *   <li><b>信封字段 snake_case</b>:Jackson 未配全局命名策略,record 不加
 *       {@code @JsonProperty} 就会吐 camelCase,与其余接口分叉。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        // 显式留空:semantic_search 在未配置 embedding 时必须走"如实说明未配置"分支而不发外部请求。
        // 本机若恰好设了 AICAP_EMBEDDING_API_KEY,PLAT-11 的断言就失去意义,所以不能只靠默认值
        "aicap.rag.embedding.api-key=",
        "aicap.rag.embedding.model="
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformAgentContractTest extends ContractTestSupport {

    private static final String CATALOG = "/api/agents/catalog";
    private static final String TOOLS = "/api/agents/tools/";

    /** 清单项允许出现的字段;多一个都算把业务数据漏进了能力声明 */
    private static final Set<String> ALLOWED_TOOL_FIELDS = Set.of(
            "name", "agent", "description", "args_schema", "args_hint", "read_only", "uses_retrieval");

    // ==================== 清单 ====================

    /** PLAT-01:未登录不可读清单 */
    @Test
    void plat01_catalog_withoutToken_401() {
        assertStatus(get(CATALOG, null), 401);
    }

    /** PLAT-02:清单是能力声明,四个角色都能读(含 viewer) */
    @Test
    void plat02_catalog_readableByEveryRoleIncludingViewer() {
        for (String who : List.of(USER_ADMIN, USER_OWNER, USER_MEMBER, USER_VIEWER)) {
            ApiResponse r = get(CATALOG, token(who));
            assertEquals(200, r.status(), who + " 应可读能力清单: " + r.body());
        }
    }

    /** PLAT-03:清单只含元数据 —— 任何业务字段的出现都是把"能力清单"变成了"数据接口" */
    @Test
    void plat03_catalog_carriesMetadataOnly() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());

        JsonNode tools = r.json().path("tools");
        assertTrue(tools.isArray() && tools.size() >= 14, "工具数应 ≥14,实际: " + tools.size());
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            assertFalse(name.isEmpty(), "工具缺 name: " + tool);
            assertFalse(tool.path("agent").asText().isEmpty(), "工具缺 agent: " + tool);
            assertFalse(tool.path("description").asText().isEmpty(), "工具缺 description: " + tool);
            tool.fieldNames().forEachRemaining(field ->
                    assertTrue(ALLOWED_TOOL_FIELDS.contains(field),
                            "工具 " + name + " 出现了非元数据字段: " + field));
        }
    }

    /** PLAT-04:工具层不得暴露任何写通道(编排层没有写库能力,这是结构性保证) */
    @Test
    void plat04_catalog_exposesReadOnlyToolsOnly() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        for (JsonNode tool : r.json().path("tools")) {
            assertTrue(tool.path("read_only").asBoolean(),
                    "工具层出现写工具: " + tool.path("name").asText());
        }
        assertFalse(r.body().contains("\"read_only\":false"), "信封里出现写工具: " + r.body());
    }

    /** PLAT-05:能力限制必须随清单下发 —— 主管规划时要带着它们,不能让人以为数据是全的 */
    @Test
    void plat05_catalog_declaresLimitations() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        JsonNode limits = r.json().path("limitations");
        assertTrue(limits.isArray() && limits.size() > 0, "必须声明能力限制: " + r.body());
    }

    /** PLAT-06:三个来源都在册、无重名、每个工具都有参数说明(catalog 与分派表不得分叉) */
    @Test
    void plat06_catalog_coversThreeSourcesWithoutDuplicates() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        Set<String> names = new LinkedHashSet<>();
        Set<String> agents = new LinkedHashSet<>();
        for (JsonNode tool : r.json().path("tools")) {
            String name = tool.path("name").asText();
            assertTrue(names.add(name), "工具名重复: " + name);
            agents.add(tool.path("agent").asText());
            assertTrue(tool.path("args_schema").isObject() || tool.path("args_hint").isTextual(),
                    "工具必须给出参数说明: " + name);
        }
        assertTrue(agents.containsAll(Set.of("meeting", "profile", "platform")),
                "三个来源都应在册,实际: " + agents);
        assertTrue(names.contains("semantic_search"), "平台自有工具应在册: " + names);
    }

    // ==================== 调用门槛 ====================

    /** PLAT-07:调工具要写权限;viewer → 403(与"触发 AI 分析"同一门槛) */
    @Test
    void plat07_invokeTool_viewer_403() {
        assertStatus(post(TOOLS + "search_stories", token(USER_VIEWER), json(map("keyword", "权限"))), 403);
    }

    /** PLAT-08:未登录调工具 → 401 */
    @Test
    void plat08_invokeTool_withoutToken_401() {
        assertStatus(post(TOOLS + "search_stories", null, json(map("keyword", "权限"))), 401);
    }

    /** PLAT-09:未知工具 → 404(契约写错要在开发期炸出来,不能混进 ok=false) */
    @Test
    void plat09_unknownTool_404() {
        assertStatus(post(TOOLS + "no_such_tool", token(USER_ADMIN), null), 404);
    }

    // ==================== 信封语义 ====================

    /** PLAT-10:参数不合法 → 200 + ok=false(模型要能读到错误并自我纠正,不能变 HTTP 异常) */
    @Test
    void plat10_invalidArguments_200_okFalse() {
        ApiResponse r = post(TOOLS + "search_stories", token(USER_ADMIN), json(map("wrong_field", "x")));
        assertEquals(200, r.status(), "业务失败必须是 200: " + r.body());
        assertFalse(r.json().path("ok").asBoolean(), "应 ok=false: " + r.body());
        assertEquals("invalid_tool_arguments", r.json().path("error_code").asText(), r.body());
        assertFalse(r.json().path("error_message").asText().isEmpty(), "应给出可读原因: " + r.body());
    }

    /** PLAT-11:信封字段是 snake_case —— 契约文档写 latency_ms,回 camelCase 就会让编排层取到 null */
    @Test
    void plat11_envelope_usesSnakeCase() {
        ApiResponse r = post(TOOLS + "search_stories", token(USER_ADMIN), json(map("keyword", "登录")));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().has("latency_ms"), "应回 latency_ms: " + r.body());
        assertTrue(r.json().has("limitations"), "应回 limitations: " + r.body());
        assertFalse(r.body().contains("\"latencyMs\""), "不得输出 camelCase: " + r.body());
    }

    // ==================== 两个注册表的映射 ====================

    /** PLAT-12:会议域工具取真实数据(items 是数组) */
    @Test
    void plat12_meetingTool_returnsRealData() {
        ApiResponse r = post(TOOLS + "search_stories", token(USER_ADMIN), json(map("keyword", "登录")));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("ok").asBoolean(), "应 ok=true: " + r.body());
        assertTrue(r.json().path("result").path("items").isArray(), "应回 items: " + r.body());
    }

    /** PLAT-13:画像域工具经统一端点包装后仍如实回数据(两个注册表形态不同,映射不得丢字段) */
    @Test
    void plat13_profileTool_wrappedFaithfully() {
        ApiResponse r = post(TOOLS + "query_members", token(USER_ADMIN), null);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("ok").asBoolean(), "应 ok=true: " + r.body());
        assertTrue(r.json().path("result").path("members").isArray(), "应回 members: " + r.body());
    }

    // ==================== semantic_search ====================

    /** PLAT-14:未配置 embedding 时如实说明,而不是发外部请求或报 500 */
    @Test
    void plat14_semanticSearch_unconfigured_reportsHonestly() {
        ApiResponse r = post(TOOLS + "semantic_search", token(USER_ADMIN), json(map("q", "权限模块")));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("ok").asBoolean(), r.body());
        assertFalse(r.json().path("retrieval").path("configured").asBoolean(),
                "未配置 embedding 时 configured 必须为 false: " + r.body());
        assertFalse(r.json().path("retrieval").path("message").asText().isEmpty(),
                "必须说明为什么没有结果: " + r.body());
    }

    /** PLAT-15:semantic_search 查询词越界 → ok=false,不抛 500 */
    @Test
    void plat15_semanticSearch_blankQuery_okFalse() {
        ApiResponse r = post(TOOLS + "semantic_search", token(USER_ADMIN), json(map("q", "")));
        assertEquals(200, r.status(), r.body());
        assertFalse(r.json().path("ok").asBoolean(), r.body());
        assertEquals("invalid_tool_arguments", r.json().path("error_code").asText(), r.body());
    }
}
