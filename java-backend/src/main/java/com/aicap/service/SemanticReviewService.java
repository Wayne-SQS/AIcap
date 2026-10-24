package com.aicap.service;

import com.aicap.common.AgentError;
import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * US22 语义评审：对 README 或测试代码做「读者能不能照着做」的语义判断。
 *
 * <p>与 {@link RepoQualityScorer} 的分工：
 * <ul>
 *   <li>Scorer 是静态规则 —— 断言几个、章节齐不齐，<b>可复算但不理解内容</b>；</li>
 *   <li>本服务调 LLM —— 判断「这段说明读者能不能照做」、<b>但不保证可复算</b>。</li>
 * </ul>
 *
 * <p>为了让 LLM 输出可核查而非空泛，本服务做了四件事：
 * <ol>
 *   <li><b>锚定标尺</b>：提示词给出 5 档分值与判据，禁止只给一个分数；</li>
 *   <li><b>强制引用</b>：每条优点/问题必须引用原文片段，解析后逐条<b>回原文校验</b>，
 *       找不到的标 verified=false（模型编造引用会被暴露）；</li>
 *   <li><b>只评可否执行</b>：明确要求不评文笔、不评技术选型，只判「读者能否照做/判断」；</li>
 *   <li><b>输入缓存</b>：同一段文本哈希命中缓存，同内容同结果，避免同一份 README 两次评分不一致。</li>
 * </ol>
 *
 * <p>未配置密钥 / 调用失败一律降级 available=false + reason，不编造分数。
 */
@Service
public class SemanticReviewService {
    private static final Logger log = LoggerFactory.getLogger(SemanticReviewService.class);

    private static final int MAX_INPUT_CHARS = 12_000;
    private static final long CACHE_TTL_MS = 2 * 60 * 60_000L;

    private final ModelClient model;
    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Map<String, Object> value, long at) {}

    public SemanticReviewService(ModelClient model, LlmProperties props) {
        this.model = model;
        this.props = props;
    }

    /** scope: readme | tests */
    public Map<String, Object> review(String scope, String text, String context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        if (text == null || text.isBlank()) {
            out.put("available", false);
            out.put("reason", "没有可评审的文本（仓库里未取到 " + ("readme".equals(scope) ? "README" : "测试文件") + " 正文）");
            return out;
        }
        String trimmed = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        boolean truncated = text.length() > MAX_INPUT_CHARS;
        String key = scope + "|" + sha(trimmed);
        CacheEntry hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < CACHE_TTL_MS) {
            Map<String, Object> cached = new LinkedHashMap<>(hit.value());
            cached.put("cached", true);
            return cached;
        }
        if (!props.settingsReady()) {
            out.put("available", false);
            out.put("reason", "未配置 LLM 密钥（AICAP_LLM_API_KEY），语义评审需要模型");
            return out;
        }
        try {
            var messages = new ArrayList<JsonNode>();
            messages.add(msg("system", systemPrompt(scope)));
            messages.add(msg("user", userPrompt(scope, trimmed, context, truncated)));
            ModelClient.Completion completion = model.complete(messages, List.of(), true);
            String content = completion.message() == null ? "" : completion.message().path("content").asText("");
            Map<String, Object> parsed = parse(content, trimmed);
            parsed.put("available", true);
            parsed.put("model", model.effectiveModelName());
            parsed.put("input_chars", text.length());
            parsed.put("input_truncated", truncated);
            parsed.put("cached", false);
            parsed.put("limitations", "语义评审由模型判断，结果可能波动；每条问题都附原文引用供人工复核，"
                    + "未在原文中找到的引用会标 verified=false。只评「读者能否照做」，不评文笔与技术选型。");
            cache.put(key, new CacheEntry(parsed, now));
            return parsed;
        } catch (AgentError e) {
            log.warn("[semantic] 评审失败 scope={} code={} msg={}", scope, e.getMessage(), e.getMessage());
            out.put("available", false);
            out.put("reason", "not_configured".equals(e.getMessage()) ? "未配置 LLM 密钥" : "模型调用失败：" + e.getMessage());
            return out;
        } catch (Exception e) {
            log.warn("[semantic] 评审异常 scope={}", scope, e);
            out.put("available", false);
            out.put("reason", "评审异常：" + e.getClass().getSimpleName());
            return out;
        }
    }

    // ---------- 提示词 ----------

    private String systemPrompt(String scope) {
        String target = "readme".equals(scope) ? "项目 README（使用/开发说明文档）" : "测试代码";
        String question = "readme".equals(scope)
                ? "一个第一次接触这个项目的人，只看这份 README，能不能把项目跑起来、知道怎么用？"
                : "这些测试能不能真正发现回归——断言是否验证了行为，而不是只跑通？";
        return """
                你是严格的软件质量评审者，只评审%s。核心问题：%s

                评分标尺（必须按此档位给分，不许给"看起来合理"的中间值）：
                90-100 可直接交付：信息完整、可照做、无歧义；
                70-89  能用但缺关键信息（例如没说怎么启动、配置项含义不清）；
                50-69  有框架但内容空泛，读者仍要靠猜；
                30-49  碎片化，方向对但无法执行；
                0-29   误导、与常见实践矛盾，或几乎没有实质信息。

                硬性要求：
                1. 每条优点/问题都必须引用原文片段（quote 必须逐字出现在给定文本里，不要改写）；
                2. 找不到原文依据的判断一律不要写，宁可少写；
                3. 只评「读者能否照做 / 测试能否发现回归」，不评文笔、不评技术选型、不评价项目本身好坏；
                4. 另外列出「读者真正需要但文本里没有的东西」（missing）；
                5. 拿不准就在 confidence 写 low，不要编。

                只输出 JSON，不要解释：
                {"score":0-100,"verdict":"一句话结论","reader_can_act":true/false,
                 "strengths":[{"quote":"原文片段","why":"为什么算优点"}],
                 "issues":[{"quote":"原文片段","problem":"问题","suggestion":"怎么改"}],
                 "missing":["缺失项"],"confidence":"high|medium|low"}
                """.formatted(target, question);
    }

    private String userPrompt(String scope, String text, String context, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        if (context != null && !context.isBlank()) sb.append("项目背景：").append(context).append("\n\n");
        sb.append("待评审").append("readme".equals(scope) ? "README" : "测试代码").append("：\n");
        sb.append("-----\n").append(text).append("\n-----");
        if (truncated) sb.append("\n（文本过长，已截断到前 ").append(MAX_INPUT_CHARS).append(" 字符）");
        return sb.toString();
    }

    private JsonNode msg(String role, String content) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        return n;
    }

    // ---------- 解析与校验 ----------

    Map<String, Object> parse(String content, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (Exception e) {
            m.put("parse_error", true);
            m.put("raw_preview", content.length() > 300 ? content.substring(0, 300) : content);
            m.put("score", null);
            m.put("verdict", "模型返回不是合法 JSON，本次评审作废");
            m.put("strengths", List.of());
            m.put("issues", List.of());
            m.put("missing", List.of());
            m.put("confidence", "low");
            return m;
        }
        int score = root.path("score").asInt(-1);
        m.put("score", score < 0 ? null : Math.min(100, score));
        m.put("grade", score < 0 ? null : semanticGrade(score));
        m.put("verdict", root.path("verdict").asText(""));
        m.put("reader_can_act", root.path("reader_can_act").asBoolean(false));
        m.put("strengths", checkQuotes(root.path("strengths"), source, "why"));
        m.put("issues", checkQuotes(root.path("issues"), source, "problem"));
        List<String> missing = new ArrayList<>();
        for (JsonNode n : root.path("missing")) missing.add(n.asText(""));
        m.put("missing", missing);
        m.put("confidence", root.path("confidence").asText("low"));
        // 统计编造引用的比例，作为可信度提示
        long unverified = ((List<?>) m.get("issues")).stream()
                .filter(o -> Boolean.FALSE.equals(((Map<?, ?>) o).get("verified"))).count();
        m.put("unverified_quotes", unverified);
        return m;
    }

    /** 每条必须带 quote，且 quote 能在原文里找到；找不到就标 verified=false 供人工识别 */
    private List<Map<String, Object>> checkQuotes(JsonNode arr, String source, String textField) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        String flat = flat(source);
        for (JsonNode n : arr) {
            String quote = n.path("quote").asText("").trim();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("quote", quote);
            item.put(textField, n.path(textField).asText(""));
            if ("why".equals(textField)) item.put("suggestion", n.path("suggestion").asText(""));
            item.put("verified", !quote.isEmpty() && flat.contains(flat(quote)));
            if (item.get("verified").equals(true) || !quote.isEmpty()) out.add(item);
        }
        return out;
    }

    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String semanticGrade(int score) {
        if (score >= 85) return "优";
        if (score >= 70) return "良";
        if (score >= 55) return "中";
        return "差";
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
