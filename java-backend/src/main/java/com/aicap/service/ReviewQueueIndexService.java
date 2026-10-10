package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A read-only, proposal-level index over the existing source tables. No review decision is copied. */
@Service
@RequiredArgsConstructor
public class ReviewQueueIndexService {
    private static final Set<String> SOURCES = Set.of("daily", "planning", "review", "retro", "refinement", "assignment", "general");
    private static final Set<String> STATUSES = Set.of("pending", "reviewed", "execution", "all");
    private static final int MAX_LIMIT = 100;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record Item(String source, String meetingId, String meetingTitle, String analysisId,
                       String proposalId, int proposalIndex, String createdAt, long sortTime, String status,
                       String executionStatus) {}
    public record Page(List<Item> items, String nextCursor) {}
    public record RecentMeeting(String id, String title) {}
    public record Summary(long pendingCount, long executionCount, Item latest, RecentMeeting recentMeeting) {}
    private record Cursor(long sortTime, String source, String analysisId, int proposalIndex) {}

    private Map<String, String> sql() {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String source : List.of("status", "planning", "review", "retro", "refinement")) {
            String table = "meeting_" + source + "_analyses";
            String reviews = "meeting_" + source + "_proposal_reviews";
            String key = source.equals("status") ? "daily" : source;
            parts.put(key, "SELECT '" + key + "' AS source,a.meeting_id,m.title AS meeting_title,a.id AS analysis_id," +
                    "jt.proposal_id,jt.ordinal-1 AS proposal_index,a.created_at," +
                    "CASE WHEN r.decision IS NULL THEN 'pending' WHEN r.decision='reject' THEN 'rejected' ELSE 'approved' END AS status," +
                    "COALESCE(r.execution_status,'not_started') AS execution_status " +
                    "FROM " + table + " a JOIN meetings m ON m.id=a.meeting_id " +
                    "JOIN JSON_TABLE(a.result_json,'$.proposed_actions[*]' COLUMNS (ordinal FOR ORDINALITY," +
                    "proposal_id VARCHAR(80) PATH '$.proposal_id')) jt " +
                    "LEFT JOIN " + reviews + " r ON r.analysis_id=a.id AND r.proposal_index=jt.ordinal-1");
        }
        parts.put("assignment", "SELECT 'assignment' AS source,a.meeting_id,m.title AS meeting_title,a.id AS analysis_id," +
                "a.id AS proposal_id,0 AS proposal_index,a.created_at," +
                "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.review_json,'$.status')),'pending') AS status," +
                "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.review_json,'$.execution_status')),'not_started') AS execution_status " +
                "FROM meeting_assignment_analyses a JOIN meetings m ON m.id=a.meeting_id");
        parts.put("general", "SELECT 'general' AS source,r.meeting_id,COALESCE(m.title,'') AS meeting_title," +
                "s.id AS analysis_id,s.id AS proposal_id,0 AS proposal_index," +
                "COALESCE(s.created_at,CAST('1970-01-01 00:00:00' AS DATETIME)) AS created_at," +
                "s.status,COALESCE(r.execution_status,'not_started') AS execution_status " +
                "FROM meeting_suggestion_records r JOIN suggestions s ON s.id=r.suggestion_id " +
                "LEFT JOIN meetings m ON m.id=r.meeting_id");
        return parts;
    }

    private Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            if (encoded.length() > 512) throw new IllegalArgumentException();
            JsonNode node = mapper.readTree(Base64.getUrlDecoder().decode(encoded));
            if (!node.isArray() || node.size() != 4) throw new IllegalArgumentException();
            if (!node.get(0).isIntegralNumber()) throw new IllegalArgumentException();
            long time = node.get(0).longValue();
            String source = node.get(1).asText(), id = node.get(2).asText();
            int index = node.get(3).intValue();
            if (!SOURCES.contains(source) || id.isBlank() || id.length() > 36 || index < 0 || index > 10000)
                throw new IllegalArgumentException();
            return new Cursor(time, source, id, index);
        } catch (Exception ex) { throw ApiException.unprocessable("审核队列游标无效"); }
    }

    private String encode(Item item) {
        try {
            byte[] data = mapper.writeValueAsBytes(List.of(item.sortTime(), item.source(), item.analysisId(), item.proposalIndex()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        } catch (Exception ex) { throw new IllegalStateException("Cannot encode review queue cursor", ex); }
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        long pending = 0, execution = 0;
        for (String source : List.of("status", "planning", "review", "retro", "refinement")) {
            String analyses = "meeting_" + source + "_analyses";
            String reviews = "meeting_" + source + "_proposal_reviews";
            Map<String, Object> counts = jdbc.queryForMap("SELECT " +
                    "COALESCE(SUM(COALESCE(JSON_LENGTH(a.result_json,'$.proposed_actions'),0)" +
                    "-COALESCE(r.reviewed_count,0)),0) AS pending_count," +
                    "COALESCE(SUM(COALESCE(r.execution_count,0)),0) AS execution_count " +
                    "FROM " + analyses + " a JOIN meetings m ON m.id=a.meeting_id " +
                    "LEFT JOIN (SELECT analysis_id,COUNT(*) AS reviewed_count," +
                    "SUM(CASE WHEN decision<>'reject' AND execution_status='not_started' THEN 1 ELSE 0 END)" +
                    " AS execution_count FROM " + reviews + " GROUP BY analysis_id) r ON r.analysis_id=a.id");
            pending += ((Number) counts.get("pending_count")).longValue();
            execution += ((Number) counts.get("execution_count")).longValue();
        }
        Map<String, String> parts = sql();
        for (String source : List.of("assignment", "general")) {
            String sourceSql = parts.get(source);
            Map<String, Object> counts = jdbc.queryForMap("SELECT " +
                    "COALESCE(SUM(CASE WHEN q.status='pending' THEN 1 ELSE 0 END),0) AS pending_count," +
                    "COALESCE(SUM(CASE WHEN q.status='approved' AND q.execution_status='not_started' THEN 1 ELSE 0 END),0) AS execution_count " +
                    "FROM (" + sourceSql + ") q");
            pending += ((Number) counts.get("pending_count")).longValue();
            execution += ((Number) counts.get("execution_count")).longValue();
        }
        List<Item> latest = page("all", "all", null, null, null, null, 1).items();
        List<RecentMeeting> meetings = jdbc.query("SELECT id,title FROM meetings ORDER BY created_at DESC,BINARY id DESC LIMIT 1",
                (rs, rowNum) -> new RecentMeeting(rs.getString("id"), rs.getString("title")));
        return new Summary(pending, execution, latest.isEmpty() ? null : latest.getFirst(),
                meetings.isEmpty() ? null : meetings.getFirst());
    }

    @Transactional(readOnly = true)
    public Page page(String status, String source, String search, LocalDate from, LocalDate to,
                     String cursorText, Integer limitValue) {
        String selectedStatus = status == null || status.isBlank() ? "pending" : status;
        String selectedSource = source == null || source.isBlank() ? "all" : source;
        if (!STATUSES.contains(selectedStatus) || (!selectedSource.equals("all") && !SOURCES.contains(selectedSource)))
            throw ApiException.unprocessable("审核队列筛选条件无效");
        if (limitValue != null && (limitValue < 1 || limitValue > MAX_LIMIT))
            throw ApiException.unprocessable("审核队列每页数量须在 1 到 100 之间");
        if (from != null && to != null && from.isAfter(to)) throw ApiException.unprocessable("审核队列日期范围无效");
        String query = search == null ? "" : search.trim();
        if (query.length() > 200) throw ApiException.unprocessable("审核队列搜索词过长");
        int limit = limitValue == null ? 50 : limitValue;
        Cursor cursor = decode(cursorText);
        List<Item> items = new ArrayList<>();
        for (var entry : sql().entrySet()) {
            if (!selectedSource.equals("all") && !selectedSource.equals(entry.getKey())) continue;
            StringBuilder statement = new StringBuilder("SELECT q.*,CAST(UNIX_TIMESTAMP(q.created_at)*1000000 AS SIGNED) AS sort_time FROM (")
                    .append(entry.getValue()).append(") q WHERE 1=1");
            List<Object> args = new ArrayList<>();
            if (!selectedStatus.equals("all")) {
                if (selectedStatus.equals("pending")) statement.append(" AND q.status='pending'");
                else if (selectedStatus.equals("reviewed")) statement.append(" AND q.status IN ('approved','rejected','modified')");
                else statement.append(" AND (q.status='approved' OR q.execution_status IN ('succeeded','failed','conflict','not_applicable','not_needed'))");
            }
            if (!query.isEmpty()) {
                statement.append(" AND (q.meeting_title LIKE ? OR q.proposal_id LIKE ?)");
                String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                args.add(pattern); args.add(pattern);
            }
            if (from != null) { statement.append(" AND q.created_at>=?"); args.add(Timestamp.valueOf(from.atStartOfDay())); }
            if (to != null) { statement.append(" AND q.created_at<?"); args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay())); }
            if (cursor != null) {
                statement.append(" AND (CAST(UNIX_TIMESTAMP(q.created_at)*1000000 AS SIGNED),BINARY q.source,BINARY q.analysis_id,q.proposal_index) < (?,CAST(? AS BINARY),CAST(? AS BINARY),?)");
                args.add(cursor.sortTime()); args.add(cursor.source());
                args.add(cursor.analysisId()); args.add(cursor.proposalIndex());
            }
            statement.append(" ORDER BY sort_time DESC,BINARY q.source DESC,BINARY q.analysis_id DESC,q.proposal_index DESC LIMIT ?");
            args.add(limit + 1);
            for (Map<String,Object> row : jdbc.queryForList(statement.toString(), args.toArray())) {
                items.add(new Item(row.get("source").toString(), row.get("meeting_id") == null ? null : row.get("meeting_id").toString(),
                        row.get("meeting_title").toString(), row.get("analysis_id").toString(),
                        row.get("proposal_id").toString(), ((Number)row.get("proposal_index")).intValue(),
                        row.get("created_at").toString().replace(' ', 'T'), ((Number)row.get("sort_time")).longValue(), row.get("status").toString(),
                        row.get("execution_status").toString()));
            }
        }
        items.sort(Comparator.comparingLong(Item::sortTime).thenComparing(Item::source)
                .thenComparing(Item::analysisId).thenComparingInt(Item::proposalIndex).reversed());
        boolean more = items.size() > limit;
        List<Item> page = items.subList(0, Math.min(items.size(), limit));
        return new Page(page, more ? encode(page.getLast()) : null);
    }
}
