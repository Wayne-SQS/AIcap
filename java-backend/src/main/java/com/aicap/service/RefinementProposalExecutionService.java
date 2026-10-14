package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Creates only the durable human-approved story and its audit atomically. */
@Service @RequiredArgsConstructor
public class RefinementProposalExecutionService {
    private final JdbcTemplate jdbc;
    private final ReviewQueueShadowIndexService shadow;
    private final ObjectMapper mapper;
    private final IdAllocator idAllocator;

    private JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored proposal",e); }
    }
    private Map<String,Object> analysis(String meetingId,String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM meeting_refinement_analyses WHERE meeting_id=? AND id=?"+(lock?" FOR UPDATE":""),meetingId,id);
        if(rows.isEmpty()) throw ApiException.notFound("需求细化分析记录不存在");
        return rows.getFirst();
    }
    private ObjectNode out(Map<String,Object> row) {
        var result=mapper.createObjectNode().put("execution_status","succeeded");
        for(String key:List.of("analysis_id","proposal_id","story_id","executed_at")) result.put(key,row.get(key).toString());
        for(String key:List.of("story_log_id","executed_by")) result.put(key,((Number)row.get(key)).intValue());
        return result;
    }
    @Transactional
    public ObjectNode execute(String meetingId,String analysisId,JsonNode body,int executorId) {
        if(body==null || !body.isObject() || body.size()!=1 || !body.path("proposal_id").isTextual()
                || body.path("proposal_id").asText().isBlank() || body.path("proposal_id").asText().length()>80)
            throw ApiException.unprocessable("执行请求只能包含proposal_id");
        String id=body.path("proposal_id").asText();
        var proposals=json(analysis(meetingId,analysisId,true).get("result_json")).path("proposed_actions");
        int index=-1;
        for(int i=0;i<proposals.size();i++) if(proposals.get(i).path("proposal_id").asText().equals(id)) { index=i;break; }
        if(index<0) throw ApiException.notFound("提案不存在");
        var reviews=jdbc.queryForList("SELECT * FROM meeting_refinement_proposal_reviews WHERE analysis_id=? AND proposal_index=? FOR UPDATE",analysisId,index);
        if(reviews.isEmpty() || reviews.getFirst().get("approved_json")==null
                || !Set.of("approve","modify_and_approve").contains(reviews.getFirst().get("decision")))
            throw ApiException.conflict("仅已批准提案可以执行");
        var existing=jdbc.queryForList("SELECT * FROM meeting_refinement_proposal_executions WHERE analysis_id=? AND proposal_index=?",analysisId,index);
        if(!existing.isEmpty()) return out(existing.getFirst());
        var review=reviews.getFirst();
        if(!"not_started".equals(review.get("execution_status"))) throw ApiException.conflict("执行状态无效");
        var approved=json(review.get("approved_json"));
        if(!approved.path("action").asText().equals("create_story")) throw ApiException.conflict("批准动作无效");
        var changes=approved.path("changes");
        RefinementProposalReviewService.requireComplete(changes);
        String projectId=jdbc.queryForObject("SELECT project_id FROM meetings WHERE id=?",String.class,meetingId);
        for (var story : jdbc.queryForList("SELECT code AS id,title FROM stories WHERE project_id=? ORDER BY code FOR UPDATE", projectId)) {
            if (RefinementProposalReviewService.titleKey(story.get("title").toString()).equals(
                    RefinementProposalReviewService.titleKey(changes.path("title").asText())))
                throw ApiException.conflict("已有同名故事，请重新分析审核");
        }
        String storyId=idAllocator.nextStoryId(projectId);
        var storyKey=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO stories(project_id,code,title,description,acceptance,priority,sprint,activity,status,owner_id,created_at) VALUES (?,?,?,?,?,?,?,?,0,NULL,CURRENT_TIMESTAMP)",new String[]{"internal_id"});
            statement.setString(1,projectId);statement.setString(2,storyId);
            statement.setString(3,changes.path("title").asText().strip());statement.setString(4,changes.path("description").asText());
            statement.setString(5,changes.path("acceptance").asText());statement.setString(6,changes.path("priority").asText());
            statement.setInt(7,changes.path("sprint").intValue());statement.setInt(8,changes.path("activity").intValue());
            return statement;
        },storyKey);
        long internalId=Objects.requireNonNull(storyKey.getKey()).longValue();
        var key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO story_logs(project_id,story_internal_id,story_code_snapshot,story_id,log_type,detail,user_id,created_at) VALUES (?,?,?,?,'create',?,?,CURRENT_TIMESTAMP)",new String[]{"id"});
            statement.setString(1,projectId);statement.setLong(2,internalId);statement.setString(3,storyId);statement.setString(4,storyId);
            statement.setString(5,approved.toString());statement.setInt(6,executorId);return statement;
        },key);
        int logId=Objects.requireNonNull(key.getKey()).intValue();
        jdbc.update("INSERT INTO meeting_refinement_proposal_executions(analysis_id,proposal_index,proposal_id,story_id,story_log_id,executed_by) VALUES (?,?,?,?,?,?)",
            analysisId,index,id,storyId,logId,executorId);
        jdbc.update("UPDATE meeting_refinement_proposal_reviews SET execution_status='succeeded' WHERE analysis_id=? AND proposal_index=?",analysisId,index);
        shadow.sync("refinement", analysisId);
        return out(jdbc.queryForMap("SELECT * FROM meeting_refinement_proposal_executions WHERE analysis_id=? AND proposal_index=?",analysisId,index));
    }
    @Transactional(readOnly=true)
    public ArrayNode list(String meetingId,String analysisId) {
        analysis(meetingId,analysisId,false);
        var result=mapper.createArrayNode();
        for(var row:jdbc.queryForList("SELECT * FROM meeting_refinement_proposal_executions WHERE analysis_id=? ORDER BY proposal_index",analysisId)) result.add(out(row));
        return result;
    }
}
