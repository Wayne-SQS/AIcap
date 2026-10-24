package com.aicap.service;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义评审的可核查性：模型说什么不能照单全收。
 * 关键断言 —— 编造的引用必须被标 verified=false，非 JSON 不能当有效结论。
 */
class SemanticReviewServiceTest {
    private final ObjectMapper om = new ObjectMapper();

    private LlmProperties ready() {
        LlmProperties p = new LlmProperties();
        p.setApiKey("test-key");
        p.setBaseUrl("https://api.deepseek.com");
        p.setModel("deepseek-flash");
        return p;
    }

    private ModelClient clientReturning(String json) throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode message = om.createObjectNode();
        message.put("role", "assistant");
        message.put("content", json);
        when(client.complete(any(), any(), anyBoolean()))
                .thenReturn(new ModelClient.Completion(message, Map.of()));
        when(client.effectiveModelName()).thenReturn("deepseek-flash");
        return client;
    }

    private static final String README = """
            # 订单服务

            ## 安装
            ```
            mvn -DskipTests package
            ```

            ## 配置
            | 变量 | 说明 |
            | DB_HOST | 数据库地址 |
            """;

    @Test
    void keepsRealQuotesAndFlagsFabricatedOnes() throws Exception {
        String json = """
                {"score":62,"verdict":"框架完整但缺运行与示例","reader_can_act":false,
                 "strengths":[{"quote":"mvn -DskipTests package","why":"给出了明确的构建命令"}],
                 "issues":[{"quote":"## 配置","problem":"只有配置表头，没说明取值","suggestion":"补每个配置项的示例值"},
                           {"quote":"我们支持一万种用法","problem":"原文没有这句","suggestion":"x"}],
                 "missing":["运行方式","使用示例"],"confidence":"medium"}
                """;
        var service = new SemanticReviewService(clientReturning(json), ready());
        Map<String, Object> r = service.review("readme", README, "仓库 demo");

        assertEquals(true, r.get("available"));
        assertEquals(62, r.get("score"));
        assertEquals("中", r.get("grade"));
        assertEquals(false, r.get("reader_can_act"));
        assertEquals(2, r.get("missing") instanceof List<?> l ? l.size() : -1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strengths = (List<Map<String, Object>>) r.get("strengths");
        assertTrue((Boolean) strengths.get(0).get("verified"), "真实引用应标 verified=true");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) r.get("issues");
        assertEquals(2, issues.size());
        assertTrue((Boolean) issues.get(0).get("verified"));
        assertFalse((Boolean) issues.get(1).get("verified"), "编造的引用必须标 verified=false");
        assertEquals(1L, r.get("unverified_quotes"), "应统计出 1 条未核实引用");
    }

    @Test
    void nonJsonResponseIsRejectedInsteadOfScored() {
        // 模型没按 JSON 返回时，不能从中凑一个分数
        String raw = "这份 README 整体还可以，大概 75 分吧。";
        ModelClient client;
        try {
            client = clientReturning(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var service = new SemanticReviewService(client, ready());
        Map<String, Object> r = service.review("readme", README, null);
        assertEquals(true, r.get("parse_error"));
        assertNull(r.get("score"), "解析失败不能给分");
        assertEquals("low", r.get("confidence"));
    }

    @Test
    void degradesWithoutLlmConfiguration() {
        LlmProperties none = new LlmProperties();   // apiKey 为空
        var service = new SemanticReviewService(mock(ModelClient.class), none);
        Map<String, Object> r = service.review("readme", README, null);
        assertEquals(false, r.get("available"));
        assertTrue(String.valueOf(r.get("reason")).contains("未配置"));
        assertNull(r.get("score"));
    }

    @Test
    void emptyTextIsRefused() {
        var service = new SemanticReviewService(mock(ModelClient.class), ready());
        Map<String, Object> r = service.review("readme", "  ", null);
        assertEquals(false, r.get("available"));
        assertNotNull(r.get("reason"));
    }

    @Test
    void sameInputHitsCache() throws Exception {
        String json = """
                {"score":70,"verdict":"ok","reader_can_act":true,"strengths":[],
                 "issues":[],"missing":[],"confidence":"high"}
                """;
        var service = new SemanticReviewService(clientReturning(json), ready());
        Map<String, Object> first = service.review("readme", README, null);
        Map<String, Object> second = service.review("readme", README, null);
        assertEquals(false, first.get("cached"));
        assertEquals(true, second.get("cached"), "同一文本应命中缓存，保证同一输入同一结果");
        assertEquals(70, second.get("score"));
    }
}
