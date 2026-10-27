package com.aicap.contract;

import com.aicap.llm.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;

import com.aicap.platform.MeetingAnalysisTrigger;
import com.aicap.platform.MeetingType;
import com.aicap.platform.PlatformToolCatalog;
import org.springframework.beans.factory.ObjectProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
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

    /**
     * 会议分析的触发面(可选)。catalog **按实现发布**触发型工具:没有实现就不发布,
     * 所以这条用例的期望集合也必须按同一口径算(见 PlatformToolCatalog.catalogJson)。
     */
    @Autowired
    private ObjectProvider<MeetingAnalysisTrigger> meetingTriggers;

    /** 本环境清单里**应该**出现的触发型工具 = 设计口径 ∩ 有实现的那些 */
    private Set<String> expectedTriggers() {
        Set<String> triggers = new LinkedHashSet<>(PlatformToolCatalog.TRIGGER_TOOLS);
        if (meetingTriggers.getIfAvailable() == null) {
            triggers.remove(PlatformToolCatalog.START_MEETING_ANALYSIS);
        }
        return triggers;
    }

    @Autowired
    private LlmProperties llmProperties;

    /** PLAT-27 要自己造会议与分析行(种子数据里没有会议),故直连测试库 */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 本类造出来的会议,逐条收走 —— 见 {@link #removeCreatedMeetings()} */
    private final List<String> createdMeetings = new ArrayList<>();

    /**
     * 本类造的会议必须自己删干净。
     *
     * <p>测试库是**跨类共享**的(同一张 {@code aicap_java_test},各类上下文只在启动时清一次),
     * 留下"会议 + pending 分析"会被别的类的全来源队列/统计用例当成真实提案计入。
     * 2026-10-19 CI 上 {@code ReviewQueueIndexContractTest} 多出 1 条就是这么来的。
     */
    @AfterEach
    void removeCreatedMeetings() {
        for (String id : createdMeetings) {
            for (String family : List.of("status", "planning", "review", "retro", "refinement")) {
                String analyses = "meeting_" + family + "_analyses";
                jdbc.update("DELETE FROM meeting_" + family + "_proposal_executions "
                        + "WHERE analysis_id IN (SELECT id FROM " + analyses + " WHERE meeting_id=?)", id);
                jdbc.update("DELETE FROM meeting_" + family + "_proposal_reviews "
                        + "WHERE analysis_id IN (SELECT id FROM " + analyses + " WHERE meeting_id=?)", id);
                jdbc.update("DELETE FROM " + analyses + " WHERE meeting_id=?", id);
            }
            jdbc.update("DELETE FROM meeting_assignment_analyses WHERE meeting_id=?", id);
            jdbc.update("DELETE FROM meeting_suggestion_records WHERE meeting_id=?", id);
            jdbc.update("DELETE FROM meeting_agent_runs WHERE meeting_id=?", id);
            jdbc.update("DELETE FROM meetings WHERE id=?", id);
        }
        createdMeetings.clear();
    }

    // ==================== 配置绑定 ====================

    /**
     * PLAT-17:{@code aicap.llm.agent-worker-enabled=false} 必须真的绑上。
     *
     * <p>这条不属于工具层契约,但只有这里能钉住它:本类已经用这个 key 起上下文,
     * 而本仓在这上面栽过一次——9 个测试类曾把它写成 {@code aicap.agent-worker-enabled}
     * (少了 {@code llm.}),于是<b>从来没绑定过</b>,worker 一直开着而用例照过
     * ({@code qa/测试用例设计_当前基线_v3.md:680})。现有断言都不查这个方向:
     * {@code AgentContractTest} 断的是 {@code true},而那正是默认值,绑不上也过。
     */
    @Test
    void plat17_workerToggle_actuallyBindsFromTheYmlKey() {
        assertFalse(llmProperties.isAgentWorkerEnabled(),
                "aicap.llm.agent-worker-enabled=false 没有绑到 LlmProperties —— 前缀或字段名被改过了");
    }

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

    /** PLAT-04:只读红线的新口径 —— 「读数据只读 + 触发型只建 run」(契约 §2.4，2026-10-11 拍板)。
     *
     *  <p>原先这里钉的是「所有工具 `read_only` 必须为 true、信封里不得出现 `read_only:false`」。
     *  S5′ 加了三枚触发型工具后口径放宽为：**除这三枚，任何工具都不得是写工具**；
     *  而这三枚只创建 run 落审批队列、不写业务表（它们各自有专门的用例）。
     */
    @Test
    void plat04_catalog_hasNoWriteToolExceptTheThreeTriggers() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        Set<String> triggers = expectedTriggers();
        Set<String> nonReadOnly = new LinkedHashSet<>();
        for (JsonNode tool : r.json().path("tools")) {
            String name = tool.path("name").asText();
            if (tool.path("read_only").asBoolean()) {
                assertFalse(triggers.contains(name),
                        "触发型工具不该被标成只读: " + name);
                continue;
            }
            assertTrue(PlatformToolCatalog.TRIGGER_TOOLS.contains(name),
                    "工具层出现计划外的写工具: " + name);
            nonReadOnly.add(name);
        }
        assertEquals(triggers, nonReadOnly,
                "触发型工具必须都标为非只读,且**没有实现的不发布**"
                        + "(期望 " + triggers + "): " + r.body());
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

    @Test
    void profileQueriesReceiveTheValidatedProjectId() {
        // 合同测试请求体只带 project_id；画像注册表内部使用 projectId。
        ApiResponse tasks = post(TOOLS + "query_tasks", token(USER_ADMIN), json(map("taskId", "T01")));
        assertEquals(200, tasks.status(), tasks.body());
        assertTrue(tasks.json().path("ok").asBoolean(), tasks.body());
        assertTrue(tasks.json().path("result").path("found").asBoolean(), tasks.body());

        ApiResponse activities = post(TOOLS + "query_activities", token(USER_ADMIN), json(map("userId", 4)));
        assertEquals(200, activities.status(), activities.body());
        assertTrue(activities.json().path("ok").asBoolean(), activities.body());
        assertTrue(activities.json().path("result").path("activities").isArray(), activities.body());
    }

    /**
     * PLAT-16:query_members **不得回密码哈希**。
     *
     * 原先两个分支都直接把 User 实体序列化出去(`selectList(null)` / `selectById(uid)`),
     * 而工具层只要求 Roles.writer() —— 任何 member 都能拉走全员 bcrypt 哈希做离线爆破。
     * 注意 PLAT-13 覆盖不到这条:它只断言 members 是数组,在泄露的情况下照样是绿的。
     * 两个分支必须都验 —— 只修列表分支等于只补了一半。
     */
    @Test
    void plat16_queryMembers_neverExposesPasswordHash() {
        ApiResponse list = post(TOOLS + "query_members", token(USER_ADMIN), null);
        assertEquals(200, list.status(), list.body());
        for (var member : list.json().path("result").path("members")) {
            assertFalse(member.has("passwordHash"),
                    "列表分支把 User 实体直接序列化出去了,泄露 bcrypt 哈希: " + member);
        }

        ApiResponse one = post(TOOLS + "query_members", token(USER_ADMIN), json(map("userId", 1)));
        assertEquals(200, one.status(), one.body());
        assertTrue(one.json().path("result").path("found").asBoolean(), one.body());
        assertFalse(one.json().path("result").path("member").has("passwordHash"),
                "单成员分支泄露 bcrypt 哈希: " + one.body());

        // 白名单投影不得把该暴露的字段一起丢掉(否则"修好"的方式是回个空对象)
        var member = one.json().path("result").path("member");
        assertFalse(member.path("displayName").asText().isEmpty(), "displayName 不该丢: " + one.body());
        assertFalse(member.path("role").asText().isEmpty(), "role 不该丢: " + one.body());
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

    // ==================== 触发型工具(S5′，契约 §2.4) ====================

    /** PLAT-18:触发型工具**只建 run**，且同域幂等防重(同一请求再触发返回同一条 run) */
    @Test
    void plat18_triggerTools_createRunThenDeduplicate() {
        // 请求文本带 uuid:测试库若在两次跑之间没被清空,也不会因为历史行而误判"已存在"
        String body = json(map("request", "把 US01 挪到第 3 个 Sprint " + UUID.randomUUID(),
                "project_id", firstProjectId()));

        ApiResponse first = post(TOOLS + "start_plan_draft", token(USER_ADMIN), body);
        assertEquals(200, first.status(), first.body());
        JsonNode firstResult = first.json().path("result");
        assertTrue(first.json().path("ok").asBoolean(), first.body());
        String runId = firstResult.path("run_id").asText();
        assertFalse(runId.isEmpty(), "必须回 run_id: " + first.body());
        assertEquals("queued", firstResult.path("status").asText(), first.body());
        assertFalse(firstResult.path("deduplicated").asBoolean(), first.body());

        ApiResponse second = post(TOOLS + "start_plan_draft", token(USER_ADMIN), body);
        assertEquals(200, second.status(), second.body());
        JsonNode secondResult = second.json().path("result");
        assertEquals(runId, secondResult.path("run_id").asText(),
                "同一请求重复触发必须复用既有 run,不能堆第二条: " + second.body());
        assertTrue(secondResult.path("deduplicated").asBoolean(),
                "复用既有 run 时必须标 deduplicated=true: " + second.body());
    }

    /** PLAT-19:触发型工具缺必填参数 → 200 + ok=false(invalid_arguments)，不是 4xx */
    @Test
    void plat19_triggerTools_missingArgsAreOkFalse() {
        ApiResponse noRequest = post(TOOLS + "start_plan_draft", token(USER_ADMIN), json(map()));
        assertEquals(200, noRequest.status(), noRequest.body());
        assertFalse(noRequest.json().path("ok").asBoolean(), noRequest.body());
        assertEquals("invalid_arguments", noRequest.json().path("error_code").asText(), noRequest.body());

        // 多项目之后 project_id 也是必填:少了它 run 会落到默认项目,确认时写的是别人的项目
        ApiResponse noProject = requestWithoutProject(HttpMethod.POST, TOOLS + "start_plan_draft", token(USER_ADMIN),
                json(map("request", "缺 project_id 的请求 " + UUID.randomUUID())));
        assertEquals(200, noProject.status(), noProject.body());
        assertFalse(noProject.json().path("ok").asBoolean(), noProject.body());
        assertEquals("invalid_arguments", noProject.json().path("error_code").asText(), noProject.body());

        ApiResponse noMeeting = post(TOOLS + "start_meeting_analysis", token(USER_ADMIN), json(map()));
        assertEquals(200, noMeeting.status(), noMeeting.body());
        assertFalse(noMeeting.json().path("ok").asBoolean(), noMeeting.body());
        assertEquals("invalid_arguments", noMeeting.json().path("error_code").asText(), noMeeting.body());
    }

    /** PLAT-20:触发型工具**也**受写入门槛约束(viewer 403 / 未登录 401) —— 它毕竟会建 run */
    @Test
    void plat20_triggerTools_requireWriterRole() {
        String body = json(map("request", "把 US02 挪到第 4 个 Sprint " + UUID.randomUUID()));
        assertStatus(post(TOOLS + "start_plan_draft", token(USER_VIEWER), body), 403);
        assertStatus(post(TOOLS + "start_plan_draft", null, body), 401);
    }

    /**
     * 多项目之后 planning 触发必须带 project_id(契约 §2.4):取调用者可见的第一个项目,
     * 而不是把"P001/P002"这类值写死在用例里 —— 种子项目的存在与否不该决定用例的成败。
     */
    private String firstProjectId() {
        ApiResponse response = get("/api/projects", token(USER_ADMIN));
        assertEquals(200, response.status(), response.body());
        JsonNode projects = response.json();
        assertTrue(projects.isArray() && !projects.isEmpty(), "测试库应至少有一个可见项目: " + response.body());
        String projectId = projects.get(0).path("id").asText();
        assertFalse(projectId.isEmpty(), response.body());
        return projectId;
    }

    // ==================== query_agent_runs(run 回查,契约 §2.4 第 17 枚) ====================

    /** PLAT-21:catalog 发布 query_agent_runs —— 只读、platform 域、schema 声明 project_id 必填 */
    @Test
    void plat21_queryAgentRuns_publishedAsReadOnlyPlatformTool() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        JsonNode target = null;
        for (JsonNode tool : r.json().path("tools")) {
            if ("query_agent_runs".equals(tool.path("name").asText())) {
                target = tool;
                break;
            }
        }
        assertTrue(target != null, "query_agent_runs 应在册: " + r.body());
        assertEquals("platform", target.path("agent").asText(), r.body());
        assertTrue(target.path("read_only").asBoolean(), "run 回查必须是只读工具: " + r.body());
        assertFalse(target.path("uses_retrieval").asBoolean(), r.body());
        assertTrue(target.path("args_schema").path("properties").has("project_id"),
                "schema 必须声明 project_id —— 编排层只给声明了它的工具兜底注入(Catalog.declares_project_id): "
                        + r.body());
    }

    /**
     * PLAT-22:触发一个 planning run 后,回查必须看到它,且状态与触发回执一致。
     * 本类 properties 里 {@code agent-worker-enabled=false},worker 不会把它领走 ——
     * 所以"queued"是确定值,不与后台执行赛跑。result_json 一律不整包回(草案可能很大)。
     */
    @Test
    void plat22_queryAgentRuns_findsDispatchedPlanningRun() {
        String projectId = firstProjectId();
        String request = "回查用例 " + UUID.randomUUID();
        ApiResponse created = post(TOOLS + "start_plan_draft", token(USER_ADMIN),
                json(map("request", request, "project_id", projectId)));
        assertEquals(200, created.status(), created.body());
        String runId = created.json().path("result").path("run_id").asText();
        assertFalse(runId.isEmpty(), created.body());

        ApiResponse r = post(TOOLS + "query_agent_runs", token(USER_ADMIN),
                json(map("project_id", projectId, "kind", "planning", "run_id", runId)));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("ok").asBoolean(), r.body());
        JsonNode runs = r.json().path("result").path("runs");
        assertEquals(1, runs.size(), "按 run_id 精查应恰好一行: " + r.body());
        assertEquals("planning", runs.get(0).path("kind").asText(), r.body());
        assertEquals(runId, runs.get(0).path("run_id").asText(), r.body());
        assertEquals("queued", runs.get(0).path("status").asText(), r.body());
        assertFalse(r.json().path("result").has("result_json"),
                "result_json 不得整包返回,只允许 has_result: " + r.body());
    }

    /** PLAT-23:参数不合法走 200 + ok=false(invalid_arguments),与触发型工具同口径 */
    @Test
    void plat23_queryAgentRuns_badArgsAreOkFalse() {
        ApiResponse badKind = post(TOOLS + "query_agent_runs", token(USER_ADMIN),
                json(map("project_id", firstProjectId(), "kind", "generation")));
        assertEquals(200, badKind.status(), badKind.body());
        assertFalse(badKind.json().path("ok").asBoolean(), badKind.body());
        assertEquals("invalid_arguments", badKind.json().path("error_code").asText(), badKind.body());

        // 多项目之后 project_id 就是查询作用域,缺它必须拒绝而不是回全量
        ApiResponse noProject = requestWithoutProject(HttpMethod.POST, TOOLS + "query_agent_runs",
                token(USER_ADMIN), json(map("kind", "planning")));
        assertEquals(200, noProject.status(), noProject.body());
        assertFalse(noProject.json().path("ok").asBoolean(), noProject.body());
        assertEquals("invalid_arguments", noProject.json().path("error_code").asText(), noProject.body());
    }

    /**
     * PLAT-24:回查按项目隔离 —— P1 的 run 在 P2 的回查里必须看不见;
     * 且与所有工具同门槛:viewer 403。
     */
    @Test
    void plat24_queryAgentRuns_scopedToProject_andWriterOnly() {
        String projectId = firstProjectId();
        ApiResponse created = post(TOOLS + "start_plan_draft", token(USER_ADMIN),
                json(map("request", "跨项目隔离用例 " + UUID.randomUUID(), "project_id", projectId)));
        String runId = created.json().path("result").path("run_id").asText();
        assertFalse(runId.isEmpty(), created.body());

        String otherProject = null;
        ApiResponse projects = get("/api/projects", token(USER_ADMIN));
        for (JsonNode p : projects.json()) {
            String id = p.path("id").asText();
            if (!id.isEmpty() && !id.equals(projectId)) {
                otherProject = id;
                break;
            }
        }
        assertTrue(otherProject != null,
                "种子应有第二个项目(DataSeeder 保证 P001/P002): " + projects.body());
        ApiResponse r = post(TOOLS + "query_agent_runs", token(USER_ADMIN),
                json(map("project_id", otherProject, "kind", "planning", "run_id", runId)));
        assertEquals(200, r.status(), r.body());
        assertEquals(0, r.json().path("result").path("runs").size(),
                "本项目的 run 不得出现在另一项目的回查里: " + r.body());

        assertStatus(post(TOOLS + "query_agent_runs", token(USER_VIEWER),
                json(map("project_id", projectId))), 403);
    }

    /**
     * PLAT-25:会议分析的**类型枚举**两处必须同源。
     *
     * <p>2026-10-15 起 {@code start_meeting_analysis} 必填 {@code meeting_type},
     * 而线上可选值在 catalog 里是手写的一份字面量({@code MEETING_TYPE_WIRE})、
     * 语义源是 {@link MeetingType} 枚举。两处一旦分叉,表现是"模型照 schema 传了值、
     * 平台却认不出"→ 稳定的 {@code invalid_arguments},且**不会有任何编译错误**。
     * 这条用例就是那份分叉的看门人(改一处忘另一处必红)。
     */
    @Test
    void plat25_meetingTypeEnum_matchesCatalogWireValues() {
        Set<String> fromEnum = new LinkedHashSet<>();
        for (MeetingType type : MeetingType.values()) {
            fromEnum.add(type.wire());
            // 解析必须认得自己的字面量,也认得枚举名(模型可能给 planning/retro 这种简称)
            assertEquals(type, MeetingType.parse(type.wire()), "枚举解不出自己的字面量: " + type.wire());
            assertEquals(type, MeetingType.parse(type.name()), "枚举解不出自己的名字: " + type.name());
        }
        assertEquals(new LinkedHashSet<>(PlatformToolCatalog.MEETING_TYPE_WIRE), fromEnum,
                "catalog 的 MEETING_TYPE_WIRE 与 MeetingType 枚举分叉了");
        assertEquals(5, fromEnum.size(), "五类会议,多了少了都是合约变更");
        assertEquals(null, MeetingType.parse("kickoff"), "认不出的类型必须回 null(由工具层拒为 invalid_arguments)");
        assertEquals(null, MeetingType.parse(null), "null 必须回 null,不是默认值");
    }

    /** PLAT-25b:枚举必须真的进了 schema —— 模型看不到的枚举等于不存在(与 project_id 那次同一个坑) */
    @Test
    void plat25b_meetingType_isDeclaredInToolSchema() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        JsonNode tool = null;
        for (JsonNode t : r.json().path("tools")) {
            if (PlatformToolCatalog.START_MEETING_ANALYSIS.equals(t.path("name").asText())) {
                tool = t;
                break;
            }
        }
        assertTrue(tool != null, "本环境应当发布 start_meeting_analysis(有实现): " + r.body());
        JsonNode properties = tool.path("args_schema").path("properties");
        assertEquals("string", properties.path("meeting_type").path("type").asText(),
                "meeting_type 必须在 schema 里声明: " + tool);
        Set<String> declared = new LinkedHashSet<>();
        properties.path("meeting_type").path("enum").forEach(v -> declared.add(v.asText()));
        assertEquals(new LinkedHashSet<>(PlatformToolCatalog.MEETING_TYPE_WIRE), declared,
                "schema 里的 enum 与线上字面量不一致: " + tool);
        Set<String> required = new LinkedHashSet<>();
        tool.path("args_schema").path("required").forEach(v -> required.add(v.asText()));
        assertTrue(required.contains("meeting_type"), "meeting_type 必须必填: " + tool);
        assertTrue(required.contains("meeting_id"), "meeting_id 必须必填: " + tool);
    }

    /**
     * PLAT-27:会议域的回查必须读**分析产物表**,不是退役的 {@code meeting_agent_runs}。
     *
     * <p>修的是真机实测的假话:主管被问"分析好了吗",它把旧表里一条 2026-09-13 的
     * 僵尸 run(状态永远 {@code awaiting_review})当成本次产物报了出来。根因是
     * {@code start_meeting_analysis} 已改成同步转发 ai-service、产物落
     * {@code meeting_*_analyses},而回查还读着那张平台早就不写的表。
     *
     * <p>两条断言把方向钉死:①新产物**看得见**且状态被译成队列口径;②旧表的行
     * **看不见**(它不该再被当成"会议智能体的进展")。
     */
    @Test
    void plat27_queryAgentRuns_readsMeetingAnalyses_notTheRetiredRunTable() {
        String projectId = firstProjectId();
        String meetingId = UUID.randomUUID().toString();
        String analysisId = UUID.randomUUID().toString();
        String legacyRunId = UUID.randomUUID().toString();
        int userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Integer.class, USER_ADMIN);

        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at,project_id) "
                        + "VALUES (?,?,?,?,NOW(),?)",
                meetingId, "回查口径用例", "站会原文", userId, projectId);
        createdMeetings.add(meetingId);
        jdbc.update("INSERT INTO meeting_status_analyses(id,meeting_id,submitted_by,client_request_id,status,"
                        + "transcript,result_json,snapshots_json,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,NOW())",
                analysisId, meetingId, userId, UUID.randomUUID().toString(), "pending",
                "站会原文", "{\"summary\":\"x\",\"proposed_actions\":[{\"id\":\"a\"}]}", "[]");
        // 同一场会议上再放一条**旧表**的行:它不该出现在回查里
        jdbc.update("INSERT INTO meeting_agent_runs(id,meeting_id,requested_by,status,attempt,model,"
                        + "prompt_version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,NOW(),NOW())",
                legacyRunId, meetingId, userId, "awaiting_review", 1, "legacy-model", "v0");

        ApiResponse r = post(TOOLS + "query_agent_runs", token(USER_ADMIN),
                json(map("project_id", projectId, "kind", "meeting")));
        assertEquals(200, r.status(), r.body());
        JsonNode runs = r.json().path("result").path("runs");
        assertTrue(runs.isArray() && runs.size() > 0, "应看得见刚落的分析产物: " + r.body());

        boolean sawAnalysis = false;
        for (JsonNode run : runs) {
            assertEquals("meeting", run.path("kind").asText(), r.body());
            assertFalse(legacyRunId.equals(run.path("run_id").asText()),
                    "退役的 meeting_agent_runs 不得再作为会议进展出现: " + run);
            if (analysisId.equals(run.path("run_id").asText())) {
                sawAnalysis = true;
                // 存储口径 pending → 队列口径 awaiting_review(有提案待人工复核)
                assertEquals("awaiting_review", run.path("status").asText(), run.toString());
                assertTrue(run.path("has_result").asBoolean(), run.toString());
                assertTrue(run.path("label").asText().contains("type: daily_scrum"),
                        "label 应带上会议类型,便于主管分辨: " + run);
                assertTrue(run.path("label").asText().contains("approval: pending"),
                        "label 应保留原始存储口径: " + run);
            }
        }
        assertTrue(sawAnalysis, "分析产物 " + analysisId + " 应在回查结果里: " + r.body());
    }

    /**
     * PLAT-28:会议列表只读工具 —— 主管解析"最近一场/标题里点名的那场"的数据来源。
     *
     * <p>此前"分析最近一场站会"只能被反复追问 {@code meeting_id}:规划相不调工具,而相对指代
     * 必须查一次列表才能落到具体 id。这里钉三件事:① 按 {@code created_at} 倒序
     * (解析"最近"依赖这个顺序,换序会让解析静默错位);② 只回元数据,**不得带回 transcript**;
     * ③ 参数越界走 {@code ok=false} 而不是 500。
     */
    @Test
    void plat28_listMeetings_listsMetadataNewestFirstWithoutTranscript() {
        String projectId = firstProjectId();
        int userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Integer.class, USER_ADMIN);
        String olderId = UUID.randomUUID().toString();
        String newerId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at,project_id) "
                        + "VALUES (?,?,?,?,DATE_SUB(NOW(), INTERVAL 2 DAY),?)",
                olderId, "列表用例-较早的会", "较早原文", userId, projectId);
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at,project_id) "
                        + "VALUES (?,?,?,?,NOW(),?)",
                newerId, "列表用例-最近的会", "最近原文", userId, projectId);
        createdMeetings.add(olderId);
        createdMeetings.add(newerId);

        ApiResponse r = post(TOOLS + "list_meetings", token(USER_ADMIN), json(map("project_id", projectId)));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("ok").asBoolean(), r.body());
        JsonNode items = r.json().path("result").path("items");
        assertTrue(items.isArray() && items.size() >= 2, "应列出刚插入的会议: " + r.body());
        assertEquals(newerId, items.get(0).path("id").asText(),
                "必须按 created_at 倒序（\"最近一场\"依赖此顺序）: " + r.body());
        for (JsonNode item : items) {
            assertTrue(item.has("id") && item.has("title") && item.has("created_at"), item.toString());
            assertFalse(item.has("transcript"), "会议列表不得带回转写正文: " + item);
        }
        assertFalse(r.body().contains("最近原文"), "转写正文不得出现在响应里: " + r.body());

        ApiResponse filtered = post(TOOLS + "list_meetings", token(USER_ADMIN),
                json(map("project_id", projectId, "keyword", "较早")));
        assertEquals(200, filtered.status(), filtered.body());
        JsonNode filteredItems = filtered.json().path("result").path("items");
        assertEquals(1, filteredItems.size(), "keyword 应按标题过滤: " + filtered.body());
        assertEquals(olderId, filteredItems.get(0).path("id").asText(), filtered.body());

        ApiResponse badLimit = post(TOOLS + "list_meetings", token(USER_ADMIN),
                json(map("project_id", projectId, "limit", 99)));
        assertEquals(200, badLimit.status(), badLimit.body());
        assertFalse(badLimit.json().path("ok").asBoolean(), "limit 越界应 ok=false: " + badLimit.body());
        assertEquals("invalid_tool_arguments", badLimit.json().path("error_code").asText(), badLimit.body());

        ApiResponse catalog = get(CATALOG, token(USER_ADMIN));
        JsonNode tool = null;
        for (JsonNode t : catalog.json().path("tools")) {
            if ("list_meetings".equals(t.path("name").asText())) tool = t;
        }
        assertTrue(tool != null, "catalog 应发布 list_meetings: " + catalog.body());
        assertTrue(tool.path("read_only").asBoolean(), "list_meetings 必须只读: " + tool);
        assertTrue(tool.path("args_schema").path("required").toString().contains("project_id"),
                "list_meetings 必须声明 project_id（否则兜底注入不会生效）: " + tool);
    }

    /**
     * PLAT-29:会议分析摘要工具 —— 主管能在对话框里转述结论。
     *
     * <p>派活工具只回 {@code analysis_id},用户问"结论是什么"时主管需要现读一次产物。
     * 这里钉三件事:① 摘要字段齐全、可直接转述;② **不得带回 transcript 与证据全文**
     * (提案正文属审批界面);③ 跨项目不可见(与会议域端点同口径的 404)。
     */
    @Test
    void plat29_getMeetingAnalysis_returnsDigestButNeverTranscript() {
        String projectId = firstProjectId();
        int userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Integer.class, USER_ADMIN);
        String meetingId = UUID.randomUUID().toString();
        String analysisId = UUID.randomUUID().toString();
        String transcript = "转写正文-不应出现在摘要里";
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at,project_id) "
                        + "VALUES (?,?,?,?,NOW(),?)",
                meetingId, "摘要用例-站会", transcript, userId, projectId);
        createdMeetings.add(meetingId);
        String resultJson = "{\"schema_version\":\"1.0\",\"meeting_id\":\"" + meetingId + "\","
                + "\"meeting_type\":\"daily_scrum\",\"summary\":\"今天完成了依赖校验的联调。\","
                + "\"proposed_actions\":[{\"proposal_id\":\"P1\",\"action\":\"update_story_status\","
                + "\"changes\":{\"story_id\":\"US01\",\"to_status\":2},\"reason\":\"站会确认完成\","
                + "\"evidence\":[{\"segment_id\":\"S1\",\"quote\":\"证据原文-不应外传\"}]}],"
                + "\"open_questions\":[\"阻塞项是否已解决？\"]}";
        jdbc.update("INSERT INTO meeting_status_analyses(id,meeting_id,submitted_by,client_request_id,status,"
                        + "transcript,result_json,snapshots_json,created_at) VALUES (?,?,?,?,?,?,?,?,NOW())",
                analysisId, meetingId, userId, UUID.randomUUID().toString(), "pending",
                transcript, resultJson, "[]");

        ApiResponse r = post(TOOLS + "get_meeting_analysis", token(USER_ADMIN),
                json(map("project_id", projectId, "analysis_id", analysisId)));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("ok").asBoolean(), r.body());
        JsonNode digest = r.json().path("result");
        assertEquals(analysisId, digest.path("analysis_id").asText(), r.body());
        assertEquals("daily_scrum", digest.path("kind").asText(), r.body());
        assertEquals(meetingId, digest.path("meeting_id").asText(), r.body());
        assertEquals("摘要用例-站会", digest.path("meeting_title").asText(), r.body());
        assertTrue(digest.path("summary").asText().contains("依赖校验"), "摘要应可转述: " + r.body());
        assertEquals(1, digest.path("proposal_count").asInt(), r.body());
        assertEquals(1, digest.path("proposals").size(), r.body());
        assertEquals("update_story_status", digest.path("proposals").get(0).path("action").asText(), r.body());
        assertEquals(1, digest.path("open_questions").size(), r.body());
        assertFalse(r.body().contains(transcript), "摘要不得带回会议转写正文: " + r.body());
        assertFalse(r.body().contains("证据原文"), "证据全文不得出现在摘要里: " + r.body());

        // 跨项目:分析属于本项目,调用方换一个项目读 → 404(不暴露"存在但无权查看")
        String otherProject = projectId.equals("P002") ? "P001" : "P002";
        ApiResponse cross = post(TOOLS + "get_meeting_analysis", token(USER_ADMIN),
                json(map("project_id", otherProject, "analysis_id", analysisId)));
        assertEquals(404, cross.status(), "跨项目读分析必须 404: " + cross.body());
    }
}
