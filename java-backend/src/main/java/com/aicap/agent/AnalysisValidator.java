package com.aicap.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 会议转写切分 + 模型输出严格校验(对齐 FastAPI meeting_agent/runner.py + schemas.py)。
 * 模型产出一律视为不可信输入:结构/字段/证据逐字引用/片段全覆盖校验通过前不落任何建议。
 */
@Component
public class AnalysisValidator {

    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[。！？!?])|[\\r\\n]+");

    private final ObjectMapper mapper;

    public AnalysisValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Segment(String id, String text) {
    }

    // ---------- 切分(与 runner.segments_for 逐行一致) ----------

    public List<Segment> segmentsFor(String transcript) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = SENTENCE_SPLIT.split(transcript == null ? "" : transcript, -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            for (int i = 0; i < line.length(); i += 2000) {
                int end = Math.min(i + 2000, line.length());
                segments.add(new Segment("seg-" + (segments.size() + 1), line.substring(i, end)));
            }
        }
        return segments;
    }

    // ---------- 严格校验 ----------

    /** 校验通过则返回规范化后的分析对象(顺序与 pydantic model_dump 一致;字符串已 trim) */
    public ObjectNode validate(String content, List<Segment> segments) throws AgentError {
        final JsonNode root;
        try {
            root = mapper.readTree(content == null ? "" : content);
        } catch (Exception e) {
            throw invalidOutput();
        }
        if (root == null || !root.isObject()) throw invalidOutput();
        Map<String, String> byId = new HashMap<>();
        for (Segment s : segments) byId.put(s.id, s.text);

        ObjectNode analysis = mapper.createObjectNode();
        // 必填键缺失 → pydantic ValidationError → invalid_output
        analysis.put("summary", rootText(root, "summary", 1, 2000));
        analysis.set("decisions", rootArray(root, "decisions", 20, node -> fact(node)));
        analysis.set("action_items", rootArray(root, "action_items", 20, node -> actionItem(node)));
        analysis.set("coordination_items", optionalArray(root, "coordination_items", 20, node -> actionItem(node)));
        analysis.set("status_constraints", optionalArray(root, "status_constraints", 20, node -> fact(node)));
        analysis.set("source_notes", optionalArray(root, "source_notes", 100, node -> fact(node)));
        analysis.set("risks", rootArray(root, "risks", 20, node -> fact(node)));

        ArrayNode questions = mapper.createArrayNode();
        if (root.has("unresolved_questions")) {
            JsonNode q = root.get("unresolved_questions");
            if (!q.isArray()) throw invalidOutput();
            for (JsonNode item : q) {
                if (!item.isTextual()) throw invalidOutput();
                questions.add(item.asText());
            }
        } else {
            throw invalidOutput();
        }
        analysis.set("unresolved_questions", questions);
        analysis.set("proposals", rootArray(root, "proposals", 10, node -> proposal(node)));

        // 证据逐字核对 + 覆盖核对(与 validate_analysis 一致)
        List<JsonNode> all = new ArrayList<>();
        addAll(analysis.get("decisions"), all);
        addAll(analysis.get("action_items"), all);
        addAll(analysis.get("coordination_items"), all);
        addAll(analysis.get("status_constraints"), all);
        addAll(analysis.get("source_notes"), all);
        addAll(analysis.get("risks"), all);
        addAll(analysis.get("proposals"), all);
        for (JsonNode item : all) {
            Evidence ev = readEvidence(item.path("evidence"));
            String segText = byId.get(ev.segmentId());
            if (segText == null || !segText.contains(ev.quote())) {
                throw new AgentError("invalid_evidence", "模型引用无法在原文片段中核对，未创建建议");
            }
        }
        for (JsonNode item : chain(analysis.get("action_items"), analysis.get("coordination_items"))) {
            String quote = item.path("evidence").path("quote").asText("");
            for (String key : List.of("owner_mention", "deadline_text")) {
                JsonNode mentionNode = item.path(key);
                if (!mentionNode.isNull() && !mentionNode.isMissingNode()) {
                    String mention = mentionNode.asText();
                    if (mention.isEmpty() || !quote.contains(mention)) {
                        throw new AgentError("invented_assignment", "负责人或日期缺少原文依据，未创建建议");
                    }
                }
            }
        }
        Set<String> covered = new LinkedHashSet<>();
        for (JsonNode item : all) {
            covered.add(item.path("evidence").path("segment_id").asText());
        }
        List<String> missing = new ArrayList<>();
        for (String sid : byId.keySet()) {
            if (!covered.contains(sid)) missing.add(sid);
        }
        if (!missing.isEmpty()) {
            throw new AgentError("incomplete_analysis",
                    "会议片段未被分析：" + String.join(", ", missing) + "；未创建建议");
        }
        return analysis;
    }

    private record Evidence(String segmentId, String quote) {
    }

    private Evidence readEvidence(JsonNode node) throws AgentError {
        if (node == null || !node.isObject()) throw invalidOutput();
        Iterator<String> names = node.fieldNames();
        Set<String> seen = new LinkedHashSet<>();
        while (names.hasNext()) seen.add(names.next());
        if (!seen.equals(Set.of("segment_id", "quote"))) throw invalidOutput();
        String segmentId = text(node, "segment_id", 1, 30);
        String quote = text(node, "quote", 1, 2000);
        return new Evidence(segmentId, quote);
    }

    private ObjectNode fact(JsonNode node) throws AgentError {
        if (node == null || !node.isObject()) throw invalidOutput();
        checkKeys(node, "text", "evidence");
        ObjectNode out = mapper.createObjectNode();
        out.put("text", text(node, "text", 1, 1000));
        out.set("evidence", evidenceNode(node));
        return out;
    }

    private ObjectNode actionItem(JsonNode node) throws AgentError {
        if (node == null || !node.isObject()) throw invalidOutput();
        checkKeys(node, "description", "owner_mention", "deadline_text", "evidence");
        ObjectNode out = mapper.createObjectNode();
        out.put("description", text(node, "description", 1, 1000));
        JsonNode owner = node.path("owner_mention");
        if (owner.isNull() || owner.isMissingNode()) {
            out.putNull("owner_mention");
        } else {
            if (!owner.isTextual()) throw invalidOutput();
            String v = owner.asText().trim();
            if (v.length() > 100) throw invalidOutput();
            out.put("owner_mention", v);
        }
        JsonNode deadline = node.path("deadline_text");
        if (deadline.isNull() || deadline.isMissingNode()) {
            out.putNull("deadline_text");
        } else {
            if (!deadline.isTextual()) throw invalidOutput();
            String v = deadline.asText().trim();
            if (v.length() > 100) throw invalidOutput();
            out.put("deadline_text", v);
        }
        out.set("evidence", evidenceNode(node));
        return out;
    }

    private ObjectNode proposal(JsonNode node) throws AgentError {
        if (node == null || !node.isObject()) throw invalidOutput();
        checkKeys(node, "action", "title", "description", "note", "evidence");
        String action = node.path("action").isTextual() ? node.path("action").asText().trim() : null;
        if (!"pool.create".equals(action)) throw invalidOutput();
        ObjectNode out = mapper.createObjectNode();
        out.put("action", "pool.create");
        out.put("title", text(node, "title", 1, 200));
        JsonNode description = node.path("description");
        out.put("description", description.isMissingNode() || description.isNull() ? ""
                : text(node, "description", 0, 3000));
        JsonNode note = node.path("note");
        out.put("note", note.isMissingNode() || note.isNull() ? "" : text(node, "note", 0, 1000));
        out.set("evidence", evidenceNode(node));
        return out;
    }

    private ObjectNode evidenceNode(JsonNode node) throws AgentError {
        Evidence ev = readEvidence(node.path("evidence"));
        ObjectNode out = mapper.createObjectNode();
        out.put("segment_id", ev.segmentId());
        out.put("quote", ev.quote());
        return out;
    }

    private void checkKeys(JsonNode node, String... allowed) throws AgentError {
        Set<String> allowedSet = Set.of(allowed);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowedSet.contains(names.next())) throw invalidOutput();
        }
    }

    private String rootText(JsonNode root, String key, int min, int max) throws AgentError {
        if (!root.has(key)) throw invalidOutput();
        return textNode(root.get(key), min, max);
    }

    /** optional 数组字段(pydantic default_factory=[] → 缺键视为空数组) */
    private ArrayNode optionalArray(JsonNode root, String key, int max, ItemParser parser) throws AgentError {
        if (!root.has(key)) return mapper.createArrayNode();
        return array(root.get(key), key, max, parser);
    }

    private ArrayNode rootArray(JsonNode root, String key, int max, ItemParser parser) throws AgentError {
        if (!root.has(key)) throw invalidOutput();
        return array(root.get(key), key, max, parser);
    }

    private ArrayNode array(JsonNode node, String key, int max, ItemParser parser) throws AgentError {
        if (!node.isArray()) throw invalidOutput();
        if (node.size() > max) throw invalidOutput();
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode item : node) {
            out.add(parser.parse(item));
        }
        return out;
    }

    private interface ItemParser {
        ObjectNode parse(JsonNode node) throws AgentError;
    }

    private String text(JsonNode node, String key, int min, int max) throws AgentError {
        return textNode(node.path(key), min, max);
    }

    private String textNode(JsonNode v, int min, int max) throws AgentError {
        if (!v.isTextual()) throw invalidOutput();
        String s = v.asText().trim();
        if (s.length() < min || s.length() > max) throw invalidOutput();
        return s;
    }

    private void addAll(JsonNode arr, List<JsonNode> into) {
        if (arr.isArray()) arr.forEach(into::add);
    }

    private List<JsonNode> chain(JsonNode... arrays) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode a : arrays) addAll(a, list);
        return list;
    }

    private AgentError invalidOutput() {
        return new AgentError("invalid_output", "模型结果不符合结构约定，未创建建议");
    }
}
