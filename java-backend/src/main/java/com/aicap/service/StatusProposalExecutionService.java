package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.util.*;

/** Executes only the durable human-approved proposal, atomically with its audit trail. */
@Service
@RequiredArgsConstructor
public class StatusProposalExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private String proposalId(JsonNode body) {
        if (body == null || !body.isObject() || body.size()!=1 || !body.path("proposal_id").isTextual()
                || body.path("proposal_id").asText().isBlank() || body.path("proposal_id").asText().length()>80)
            throw ApiException.unprocessable("执行请求只能包含 proposal_id");
        return body.path("proposal_id").asText();
    }

    private JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); }
        catch (Exception e) { throw new IllegalStateException("Stored proposal JSON is invalid",e); }
    }

    private Map<String,Object> analysis(String meetingId,String analysisId,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM meeting_status_analyses WHERE meeting_id=? AND id=?"+(lock ? " FOR UPDATE" : ""),meetingId,analysisId);
        if(rows.isEmpty()) throw ApiException.notFound("状态分析记录不存在");
        return rows.getFirst();
    }

    private ObjectNode out(Map<String,Object> row) {
        var result=mapper.createObjectNode().put("execution_status","succeeded");
        for(String key:List.of("analysis_id","proposal_id","story_id","executed_at")) result.put(key,row.get(key).toString());
        for(String key:List.of("previous_status","new_status","story_log_id","executed_by")) result.put(key,((Number)row.get(key)).intValue());
        return result;
    }

    @Transactional
    public ObjectNode execute(String meetingId,String analysisId,JsonNode body,int executorId) {
        String id=proposalId(body);
        // Same lock order as review: analysis, review, story. Serializes duplicate executions.
        var proposals=json(analysis(meetingId,analysisId,true).get("result_json")).path("proposed_actions");
        int index=-1;
        for(int i=0;i<proposals.size();i++) if(proposals.get(i).path("proposal_id").asText().equals(id)) { index=i; break; }
        if(index<0) throw ApiException.notFound("提案不存在");
        var reviews=jdbc.queryForList("SELECT * FROM meeting_status_proposal_reviews WHERE analysis_id=? AND proposal_index=? FOR UPDATE",analysisId,index);
        if(reviews.isEmpty() || reviews.getFirst().get("approved_json")==null
                || !Set.of("approve","modify_and_approve").contains(reviews.getFirst().get("decision")))
            throw ApiException.conflict("仅已通过人工审核的提案可以执行");
        var existing=jdbc.queryForList("SELECT * FROM meeting_status_proposal_executions WHERE analysis_id=? AND proposal_index=?",analysisId,index);
        if(!existing.isEmpty()) return out(existing.getFirst());
        if(!"not_started".equals(reviews.getFirst().get("execution_status")))
            throw ApiException.conflict("提案执行状态不允许执行");
        var approved=json(reviews.getFirst().get("approved_json"));
        String storyId=approved.path("story_id").asText();
        int before=approved.path("expected").path("status").asInt();
        int after=approved.path("changes").path("status").asInt();
        var stories=jdbc.queryForList("SELECT status FROM stories WHERE id=? FOR UPDATE",storyId);
        if(stories.isEmpty() || ((Number)stories.getFirst().get("status")).intValue()!=before)
            throw ApiException.conflict("故事不存在或状态已变化，请重新分析");
        if(jdbc.update("UPDATE stories SET status=? WHERE id=? AND status=?",after,storyId,before)!=1)
            throw ApiException.conflict("故事状态已变化，请重新分析");
        String detail="会议提案执行："+before+" → "+after+"；meeting="+meetingId+"；analysis="+analysisId+"；proposal="+id;
        var key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO story_logs (story_id,log_type,detail,user_id,created_at) VALUES (?,'move',?,?,CURRENT_TIMESTAMP)",Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,storyId); statement.setString(2,detail); statement.setInt(3,executorId);
            return statement;
        },key);
        int logId=Objects.requireNonNull(key.getKey()).intValue();
        jdbc.update("INSERT INTO meeting_status_proposal_executions (analysis_id,proposal_index,proposal_id,story_id,previous_status,new_status,story_log_id,executed_by) VALUES (?,?,?,?,?,?,?,?)",
                analysisId,index,id,storyId,before,after,logId,executorId);
        jdbc.update("UPDATE meeting_status_proposal_reviews SET execution_status='succeeded' WHERE analysis_id=? AND proposal_index=?",analysisId,index);
        return out(jdbc.queryForMap("SELECT * FROM meeting_status_proposal_executions WHERE analysis_id=? AND proposal_index=?",analysisId,index));
    }

    @Transactional(readOnly=true)
    public ArrayNode list(String meetingId,String analysisId) {
        analysis(meetingId,analysisId,false);
        var result=mapper.createArrayNode();
        for(var row:jdbc.queryForList("SELECT * FROM meeting_status_proposal_executions WHERE analysis_id=? ORDER BY proposal_index",analysisId)) result.add(out(row));
        return result;
    }
}
