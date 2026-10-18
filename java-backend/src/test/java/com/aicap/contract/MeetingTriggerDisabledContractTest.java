package com.aicap.contract;

import com.aicap.platform.MeetingAnalysisTrigger;
import com.aicap.platform.PlatformToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAT-26:参会智能体的触发面**关掉**时的契约。
 *
 * <p>为什么值得单独一个类(而不是并进 {@code PlatformAgentContractTest}):那类的
 * {@code @SpringBootTest(properties=...)} 里没有 {@code aicap.meeting-agent.enabled},
 * 于是 {@code MeetingAnalysisTriggerImpl} 是注册的 —— 而"注册了"意味着任何一次
 * {@code start_meeting_analysis} 调用都会真的去连本机 8090。契约测试的纪律是
 * <b>零外部依赖</b>(外部模型一律 fixture),所以"关掉"这条分支只能在自己的上下文里测,
 * 不能在共享上下文里顺手调一次工具。
 *
 * <p>本类断言的是「按实现发布」这条机制仍然成立:实现不注册 → catalog 不发这枚工具、
 * 且 limitations 如实声明它不可用。这条曾经救过一次场——工具在而实现不在时,
 * 主管会稳定吃到 {@code agent_tool_unavailable}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "aicap.meeting-agent.enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        "aicap.rag.embedding.api-key=",
        "aicap.rag.embedding.model="
})
class MeetingTriggerDisabledContractTest extends ContractTestSupport {

    private static final String CATALOG = "/api/agents/catalog";

    @Autowired
    private ObjectProvider<MeetingAnalysisTrigger> meetingTriggers;

    /** 关掉之后不该有任何实现 Bean */
    @Test
    void plat26a_disabled_noImplementationBean() {
        assertTrue(meetingTriggers.getIfAvailable() == null,
                "aicap.meeting-agent.enabled=false 时不该注册 MeetingAnalysisTrigger 实现");
    }

    /** 关掉之后 catalog 不发布那枚工具,并如实声明不可用 */
    @Test
    void plat26b_disabled_toolNotPublished_andDeclaredUnavailable() {
        ApiResponse r = get(CATALOG, token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());

        for (JsonNode tool : r.json().path("tools")) {
            assertFalse(PlatformToolCatalog.START_MEETING_ANALYSIS.equals(tool.path("name").asText()),
                    "没有实现就不该发布这枚工具: " + tool);
        }

        boolean declared = false;
        for (JsonNode limit : r.json().path("limitations")) {
            if (limit.asText().contains("会议分析触发当前不可用")) {
                declared = true;
            }
        }
        assertTrue(declared, "关掉时必须如实声明,不能让主管以为能派: " + r.body());
    }
}
