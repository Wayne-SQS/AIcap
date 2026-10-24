package com.aicap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * US22 内容质量留痕:把「仓库静态诊断」与「模型语义评审」的结论落库,
 * 并转成与其它缺口同构的 finding,使结论可定位、可人工确认、可回流改进任务。
 *
 * 只有评估可用且样本充足、或模型确实给出评分时才落库/出缺口;
 * 降级(不可用、样本不足、模型未配置)一律视为「未知」,不推断质量。
 */
@Service
public class QualityContentService {
    /** 代码静态维度缺口(按维度聚合,source_code 形如 CQ:test_density)。 */
    public static final String CODE_KIND = "code_quality_dimension_gap";
    /** README 读者无法照做。 */
    public static final String README_KIND = "readme_not_actionable";
    /** 测试代码不足以证明行为被验证。 */
    public static final String TESTS_KIND = "tests_not_effective";
    public static final Set<String> KINDS = Set.of(CODE_KIND, README_KIND, TESTS_KIND);

    /** 只有 blocking/high/medium 才值得人工确认建任务;low 留给页面展示。 */
    private static final Set<String> ACTIONABLE_SEVERITIES = Set.of("blocking", "high", "medium");
    private static final Map<String, String> CATEGORIES = Map.of(
            "test_density", "测试质量",
            "test_quality", "测试质量",
            "documentation", "文档质量",
            "doc_quality", "文档质量");
    /** 语义评审低于该分或「读者无法照做」时出缺口。 */
    private static final double ACTIONABLE_SCORE = 70.0;
    /** quality_improvements.source_code 为 varchar(20),超长标识不落缺口。 */
    private static final int SOURCE_CODE_MAX = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public QualityContentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 代码质量评估成功后留档;不可用或样本不足(不给总分)时不落库,避免把降级结果当成结论。 */
    @Transactional
    public void saveCodeSnapshot(String projectId, Map<String, Object> payload) {
        if (payload == null || !Boolean.TRUE.equals(payload.get("available"))
                || !Boolean.TRUE.equals(payload.get("sufficient"))) return;
        jdbc.update("INSERT INTO quality_code_snapshots(project_id,repo,branch,total,grade,risk_level,"
                        + "payload_json,created_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                projectId, text(payload.get("repo")), text(payload.get("branch")),
                number(payload.get("total")), text(payload.get("grade")), text(payload.get("risk_level")),
                json(payload));
    }

    /** 最近一次有效的代码质量结论;没有则返回 null。 */
    public Map<String, Object> latestCodeSnapshot(String projectId) {
        var rows = jdbc.queryForList("SELECT payload_json,created_at FROM quality_code_snapshots "
                + "WHERE project_id=? ORDER BY id DESC LIMIT 1", projectId);
        if (rows.isEmpty()) return null;
        Map<String, Object> payload = parse(String.valueOf(rows.get(0).get("payload_json")));
        if (payload == null) return null;
        payload.put("as_of", String.valueOf(rows.get(0).get("created_at")));
        payload.put("persisted", true);
        return payload;
    }

    /**
     * 语义评审成功后留档。content_sha 只记输入指纹,不存正文,用于判断结论与哪次输入对应。
     */
    @Transactional
    public void saveSemanticReview(String projectId, String source, Map<String, Object> payload, String text) {
        if (payload == null || !Boolean.TRUE.equals(payload.get("available"))) return;
        Object rawScore = payload.get("score");
        if (!(rawScore instanceof Number score)) return;
        jdbc.update("INSERT INTO quality_semantic_reviews(project_id,scope,source,score,grade,"
                        + "reader_can_act,payload_json,content_sha,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                projectId, text(payload.get("scope")), source == null ? null : source,
                score.intValue(), text(payload.get("grade")),
                Boolean.TRUE.equals(payload.get("reader_can_act")) ? 1 : 0,
                json(payload), sha(text));
    }

    /** 每个 scope 最新一次语义评审,key 为 scope(readme/tests)。 */
    public Map<String, Map<String, Object>> latestSemanticReviews(String projectId) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT scope,source,payload_json,created_at "
                + "FROM quality_semantic_reviews WHERE project_id=? ORDER BY id DESC", projectId)) {
            String scope = text(row.get("scope"));
            if (latest.containsKey(scope)) continue;
            Map<String, Object> payload = parse(String.valueOf(row.get("payload_json")));
            if (payload == null) continue;
            payload.put("as_of", String.valueOf(row.get("created_at")));
            if (row.get("source") != null) payload.putIfAbsent("source", String.valueOf(row.get("source")));
            payload.put("persisted", true);
            latest.put(scope, payload);
        }
        return latest;
    }

    /** 把已留档的代码质量维度缺口与语义评审结论转成可确认的 finding。 */
    public List<Map<String, Object>> findings(String projectId) {
        var findings = new ArrayList<Map<String, Object>>();
        Map<String, Object> snapshot = latestCodeSnapshot(projectId);
        if (snapshot != null) addCodeFindings(snapshot, findings);
        for (var review : latestSemanticReviews(projectId).values()) addSemanticFinding(review, findings);
        return findings;
    }

    private void addCodeFindings(Map<String, Object> snapshot, List<Map<String, Object>> out) {
        Object rawFindings = snapshot.get("findings");
        if (!(rawFindings instanceof List<?> items)) return;
        Map<String, String> names = dimensionNames(snapshot.get("dimensions"));
        String repo = text(snapshot.get("repo"));
        String branch = text(snapshot.get("branch"));
        String grade = text(snapshot.get("grade"));
        int total = number(snapshot.get("total"));
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) continue;
            String severity = text(item.get("severity"));
            if (!ACTIONABLE_SEVERITIES.contains(severity)) continue;
            String dimension = text(item.get("category"));
            String sourceCode = "CQ:" + dimension;
            if (sourceCode.length() > SOURCE_CODE_MAX) continue;
            String name = names.getOrDefault(dimension, dimension);
            String evidence = evidenceText(item.get("evidence"));
            String detail = text(item.get("title")) + "（严重度 " + severityLabel(severity) + "）"
                    + "；影响：" + text(item.get("impact")) + "。"
                    + (evidence.isBlank() ? "" : "证据：" + evidence + "。");
            double recoverable = item.get("recoverable_points") instanceof Number n ? n.doubleValue() : 0;
            String suggestion = text(item.get("action"))
                    + (recoverable > 0 ? "；修复后约可回收 " + trim(recoverable) + " 分" : "")
                    + "。评分基于抽样静态规则，需人工复核后确认。";
            out.add(finding(CODE_KIND, CATEGORIES.getOrDefault(dimension, "代码质量"), sourceCode,
                    name + (repo.isBlank() ? "" : " · " + repo),
                    "仓库 " + repo + (branch.isBlank() ? "" : "（" + branch + "）")
                            + " · " + name + "（总分 " + total + (grade.isBlank() ? "" : " / " + grade) + "）",
                    detail, suggestion));
        }
    }

    private void addSemanticFinding(Map<String, Object> review, List<Map<String, Object>> out) {
        if (!Boolean.TRUE.equals(review.get("available"))) return;
        if (!(review.get("score") instanceof Number score)) return;
        boolean actionable = Boolean.TRUE.equals(review.get("reader_can_act"));
        if (actionable && score.doubleValue() >= ACTIONABLE_SCORE) return;
        boolean readme = !"tests".equals(text(review.get("scope")));
        String kind = readme ? README_KIND : TESTS_KIND;
        String sourceCode = readme ? "SEM:readme" : "SEM:tests";
        List<Map<String, Object>> issues = maps(review.get("issues"));
        List<String> missing = strings(review.get("missing"));
        long unverified = review.get("unverified_quotes") instanceof Number n ? n.longValue() : 0;

        StringBuilder detail = new StringBuilder(text(review.get("verdict")));
        if (!actionable) detail.append("读者无法照做");
        if (score.doubleValue() < ACTIONABLE_SCORE) {
            detail.append(detail.length() > 0 && detail.charAt(detail.length() - 1) != '。' ? "；" : "")
                    .append("语义评审仅 ").append(score.intValue()).append(" 分");
        }
        detail.append("（").append(readme ? "README" : "测试代码").append("）");
        if (unverified > 0) detail.append("；模型引用有 ").append(unverified).append(" 条未能在原文核实，需谨慎采信");
        for (var issue : issues.stream().limit(2).toList()) {
            if (!text(issue.get("problem")).isBlank()) detail.append("；问题：").append(text(issue.get("problem")));
        }

        StringBuilder suggestion = new StringBuilder();
        if (!missing.isEmpty()) {
            suggestion.append("补齐以下正文后重跑语义评审：")
                    .append(String.join("、", missing.stream().limit(5).toList())).append("。");
        } else {
            suggestion.append(readme ? "按引用位置补齐环境、命令与验收说明，使读者照做即可复现。"
                    : "按引用位置补充断言与边界用例，使覆盖行为可被验证。");
        }
        suggestion.append("本条由模型判断，引用已回原文校验，需人工复核后确认。");

        String source = text(review.get("source"));
        out.add(finding(kind, readme ? "文档质量" : "测试质量", sourceCode,
                (readme ? "README 可操作性" : "测试代码有效性") + (source.isBlank() ? "" : " · " + source),
                (readme ? "README" : "测试代码") + (source.isBlank() ? "" : " · " + source)
                        + " · 语义评审 " + score.intValue() + " 分",
                detail.toString(), suggestion.toString()));
    }

    private Map<String, Object> finding(String kind, String category, String code, String name,
                                        String location, String detail, String suggestion) {
        var item = new LinkedHashMap<String, Object>();
        item.put("kind", kind);
        item.put("category", category);
        item.put("source_code", code);
        item.put("source_name", name);
        item.put("location", location);
        item.put("detail", detail);
        item.put("suggestion", suggestion);
        return item;
    }

    /** dimension key → 展示名;某维度没有 name 时退回 key。 */
    private Map<String, String> dimensionNames(Object raw) {
        Map<String, String> names = new LinkedHashMap<>();
        if (raw instanceof List<?> dims) {
            for (Object d : dims) {
                if (d instanceof Map<?, ?> m) names.put(text(m.get("key")), text(m.get("name")));
            }
        }
        return names;
    }

    private String evidenceText(Object raw) {
        List<Map<String, Object>> items = maps(raw);
        List<String> parts = new ArrayList<>();
        for (var item : items.stream().limit(2).toList()) {
            String path = text(item.get("path"));
            if (path.isBlank()) continue;
            Object line = item.get("line");
            parts.add(line instanceof Number n && n.intValue() > 0 ? path + ":" + n.intValue() : path);
        }
        return String.join("、", parts);
    }

    private static String severityLabel(String severity) {
        return switch (severity) {
            case "blocking" -> "阻断";
            case "high" -> "高";
            case "medium" -> "中";
            default -> "低";
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(QualityContentService::text).filter(s -> !s.isBlank()).toList();
    }

    private Map<String, Object> parse(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(json, Map.class);
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String sha(String text) {
        if (text == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
