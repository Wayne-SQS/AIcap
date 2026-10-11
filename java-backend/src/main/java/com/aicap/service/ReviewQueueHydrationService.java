package com.aicap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 审核队列「一页」的详情批量补全。
 *
 * <p>队列索引只返回引用(source / meetingId / analysisId / proposalId)。客户端原先按引用回源,
 * 每个分析组要打 1-3 次详情端点 —— 一页 50 条会打出上百次 HTTP 请求。
 * 本服务在一次请求内用固定条数(每个来源族 3 条)的查询把整页详情补齐。
 *
 * <p><b>形状契约</b>:输出的 JSON 与它替代的那些详情端点**逐字段一致** ——
 * 分析对象对齐 {@code XxxAnalysisService.out},提案数组对齐 {@code XxxProposalReviewService.list},
 * 执行数组对齐 {@code XxxProposalExecutionService.list},分配建议对齐
 * {@code AssignmentSuggestionService.out}。客户端因此不需要改任何字段读取或排序逻辑。
 *
 * <p>本服务刻意不依赖任何业务 Service(只用 JdbcTemplate),所以不存在与
 * {@code XxxProposalReviewService -> ReviewQueueShadowIndexService -> ReviewQueueIndexService}
 * 这条链的循环依赖。
 */
@Service
@RequiredArgsConstructor
public class ReviewQueueHydrationService {
    /** 队列索引里的 source 键。注意 daily 一族落的是 meeting_status_* 表。 */
    private static final List<String> SOURCE_KEYS = List.of("daily", "planning", "review", "retro", "refinement");
    private static final Map<String, String> TABLE_PREFIX = Map.of(
            "daily", "status", "planning", "planning", "review", "review",
            "retro", "retro", "refinement", "refinement");
    /** 与各分析 Service 的 analysis() 抛出的 notFound 文案保持一致。 */
    private static final Map<String, String> NOT_FOUND = Map.of(
            "status", "状态分析记录不存在",
            "planning", "规划分析记录不存在",
            "review", "评审分析记录不存在",
            "retro", "复盘分析记录不存在",
            "refinement", "需求细化分析记录不存在");
    /** 执行表里「目标对象 id」列名按来源族不同(retro 落行动项)。 */
    private static final Map<String, String> EXECUTION_TARGET_COLUMN = Map.of(
            "status", "story_id", "planning", "story_id", "review", "story_id",
            "retro", "action_item_id", "refinement", "story_id");
    /** 执行表里按整数输出的列,顺序与各 XxxProposalExecutionService.out 一致。 */
    private static final Map<String, List<String>> EXECUTION_NUMBER_COLUMNS = Map.of(
            "status", List.of("previous_status", "new_status", "story_log_id", "executed_by"),
            "planning", List.of("previous_sprint", "new_sprint", "story_log_id", "executed_by"),
            "review", List.of("previous_status", "new_status", "story_log_id", "executed_by"),
            "retro", List.of("action_item_log_id", "executed_by"),
            "refinement", List.of("story_log_id", "executed_by"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 与前端的分组键一致:{@code source:meetingId(空串代替 null):analysisId}。 */
    public static String key(String source, String meetingId, String analysisId) {
        return source + ":" + (meetingId == null ? "" : meetingId) + ":" + analysisId;
    }

    /**
     * 补全一页引用。返回 {前端分组键 -> 详情}。
     * 详情对象按来源族分别是
     * {analysis, reviewState, executions} / {assignment} / {error}。
     */
    @Transactional(readOnly = true)
    public Map<String, JsonNode> hydrate(List<ReviewQueueIndexService.Item> items) {
        Map<String, JsonNode> details = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) return details;
        Map<String, List<String>> idsBySource = new LinkedHashMap<>();
        for (var item : items) {
            List<String> ids = idsBySource.computeIfAbsent(item.source(), k -> new ArrayList<>());
            if (!ids.contains(item.analysisId())) ids.add(item.analysisId());
        }
        Map<String, Map<String, JsonNode>> resolved = new LinkedHashMap<>();
        for (String sourceKey : SOURCE_KEYS) {
            List<String> ids = idsBySource.get(sourceKey);
            if (ids != null) {
                String prefix = TABLE_PREFIX.get(sourceKey);
                resolved.put(sourceKey, safely(() -> familyDetails(prefix, ids), ids));
            }
        }
        List<String> assignmentIds = idsBySource.get("assignment");
        if (assignmentIds != null)
            resolved.put("assignment", safely(() -> assignmentDetails(assignmentIds), assignmentIds));
        // general 仍由客户端逐条读 /api/suggestions/{id}(其载荷是 MeetingDtos.SuggestionOut,每次仅 1 个请求)。
        for (var item : items) {
            Map<String, JsonNode> perSource = resolved.get(item.source());
            JsonNode detail = perSource == null ? null : perSource.get(item.analysisId());
            if (detail == null) detail = mapper.createObjectNode().put("error", "审核详情缺失，请刷新队列");
            details.put(key(item.source(), item.meetingId(), item.analysisId()), detail);
        }
        return details;
    }

    /** 单个来源族读取失败时,只让该族的引用退回客户端原有的逐条报错语义,不影响整页。 */
    private Map<String, JsonNode> safely(Supplier<Map<String, JsonNode>> loader, List<String> ids) {
        try {
            return loader.get();
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "审核详情读取失败" : ex.getMessage();
            Map<String, JsonNode> failed = new LinkedHashMap<>();
            for (String id : ids) failed.put(id, mapper.createObjectNode().put("error", message));
            return failed;
        }
    }

    private Map<String, JsonNode> familyDetails(String family, List<String> ids) {
        String in = placeholders(ids);
        Object[] args = ids.toArray();
        Map<String, Map<String, Object>> analyses = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT id,meeting_id,client_request_id,status,submitted_by,created_at," +
                "result_json,snapshots_json,transcript FROM meeting_" + family + "_analyses WHERE id IN (" + in + ")", args))
            analyses.put(row.get("id").toString(), row);
        Map<String, List<Map<String, Object>>> reviews = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT * FROM meeting_" + family + "_proposal_reviews WHERE analysis_id IN (" + in +
                ") ORDER BY proposal_index", args))
            reviews.computeIfAbsent(row.get("analysis_id").toString(), k -> new ArrayList<>()).add(row);
        Map<String, List<Map<String, Object>>> executions = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT * FROM meeting_" + family + "_proposal_executions WHERE analysis_id IN (" + in +
                ") ORDER BY proposal_index", args))
            executions.computeIfAbsent(row.get("analysis_id").toString(), k -> new ArrayList<>()).add(row);

        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String id : ids) {
            Map<String, Object> analysis = analyses.get(id);
            if (analysis == null) {
                out.put(id, mapper.createObjectNode().put("error", NOT_FOUND.get(family)));
                continue;
            }
            List<Map<String, Object>> saved = reviews.getOrDefault(id, List.of());
            Map<Integer, Map<String, Object>> byIndex = new HashMap<>();
            for (var row : saved) byIndex.put(((Number) row.get("proposal_index")).intValue(), row);
            JsonNode proposed = json(analysis.get("result_json")).path("proposed_actions");
            ArrayNode proposals = mapper.createArrayNode();
            for (int i = 0; i < proposed.size(); i++) {
                if (byIndex.containsKey(i)) proposals.add(proposalOut(family, byIndex.get(i)));
                else {
                    ObjectNode pending = proposals.addObject();
                    pending.put("analysis_id", id).put("proposal_id", proposed.get(i).path("proposal_id").asText())
                            .put("status", "pending");
                    pending.set("original_proposal", proposed.get(i));
                    pending.putNull("approved_proposal").putNull("decision").putNull("reason")
                            .putNull("reviewed_by").putNull("reviewed_at");
                    pending.put("execution_status", "not_started");
                }
            }
            ObjectNode reviewState = mapper.createObjectNode();
            reviewState.put("analysis_id", id).put("review_status", proposed.isEmpty() ? "no_changes"
                    : saved.isEmpty() ? "pending"
                    : saved.size() == proposed.size() ? "reviewed" : "partially_reviewed");
            reviewState.set("proposals", proposals);
            ArrayNode executionList = mapper.createArrayNode();
            for (var row : executions.getOrDefault(id, List.of())) executionList.add(executionOut(family, row));
            ObjectNode detail = mapper.createObjectNode();
            detail.set("analysis", analysisOut(family, analysis));
            detail.set("reviewState", reviewState);
            detail.set("executions", executionList);
            out.put(id, detail);
        }
        return out;
    }

    /**
     * 对齐 XxxAnalysisService.out。五个来源族的 out 只有一处不同:retro 的快照键叫
     * {@code member_snapshots}(负责人快照),其余四族叫 {@code story_snapshots}。
     */
    private ObjectNode analysisOut(String family, Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : List.of("id", "meeting_id", "client_request_id", "status"))
            out.put(key, row.get(key).toString());
        out.put("submitted_by", ((Number) row.get("submitted_by")).intValue());
        out.put("created_at", row.get("created_at").toString());
        out.set("result", json(row.get("result_json")));
        out.set("retro".equals(family) ? "member_snapshots" : "story_snapshots", json(row.get("snapshots_json")));
        out.put("transcript", row.get("transcript").toString());
        return out;
    }

    /** 对齐 XxxProposalReviewService.out(retro 多一个 approved_owner_name)。 */
    private ObjectNode proposalOut(String family, Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : List.of("analysis_id", "proposal_id", "decision", "reason", "execution_status"))
            out.put(key, row.get(key).toString());
        out.put("status", row.get("decision").equals("reject") ? "rejected" : "approved");
        out.put("reviewed_by", ((Number) row.get("reviewed_by")).intValue());
        out.put("reviewed_at", row.get("reviewed_at").toString());
        out.set("original_proposal", json(row.get("original_json")));
        out.set("approved_proposal", row.get("approved_json") == null ? null : json(row.get("approved_json")));
        if ("retro".equals(family)) out.put("approved_owner_name", (String) row.get("approved_owner_name"));
        return out;
    }

    /** 对齐 XxxProposalExecutionService.out(列名与整数字段按来源族不同)。 */
    private ObjectNode executionOut(String family, Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode().put("execution_status", "succeeded");
        for (String key : List.of("analysis_id", "proposal_id", EXECUTION_TARGET_COLUMN.get(family), "executed_at"))
            out.put(key, row.get(key).toString());
        for (String key : EXECUTION_NUMBER_COLUMNS.get(family))
            out.put(key, ((Number) row.get(key)).intValue());
        return out;
    }

    /** 对齐 AssignmentSuggestionService.out。 */
    private Map<String, JsonNode> assignmentDetails(List<String> ids) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT * FROM meeting_assignment_analyses WHERE id IN (" + placeholders(ids) + ")",
                ids.toArray())) {
            ObjectNode result = mapper.createObjectNode();
            for (String key : List.of("id", "meeting_id", "client_request_id", "created_at"))
                result.put(key, row.get(key).toString());
            result.put("submitted_by", ((Number) row.get("submitted_by")).intValue());
            result.set("input", json(row.get("input_json")));
            result.set("result", json(row.get("result_json")));
            result.set("review", row.get("review_json") == null ? null : json(row.get("review_json")));
            out.put(row.get("id").toString(), mapper.createObjectNode().set("assignment", result));
        }
        return out;
    }

    private JsonNode json(Object text) {
        try {
            return mapper.readTree(text.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Stored analysis JSON is invalid", e);
        }
    }

    private static String placeholders(List<String> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }
}
