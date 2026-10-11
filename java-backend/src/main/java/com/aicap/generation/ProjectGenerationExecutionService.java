package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProjectGenerationExecutionService {
    private final ProjectGenerationValidator validator;
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final UserMapper users;
    private final ObjectMapper mapper;

    @Transactional
    public void execute(String draftJson,String strategy,User actor){
        if(actor==null||!java.util.Set.of("admin","owner","member").contains(actor.getRole()))throw ApiException.forbidden("无权限执行此操作");
        try { JsonNode draft=mapper.readTree(draftJson); if(!(draft instanceof com.fasterxml.jackson.databind.node.ObjectNode o))throw ApiException.conflict("草案格式已失效"); validator.validateForExecution(o,strategy); if("replace".equals(strategy)){tasks.delete(new QueryWrapper<>());stories.delete(new QueryWrapper<>());} for(JsonNode s:o.path("stories")){Story row=new Story();row.setId(s.path("id").asText());row.setTitle(s.path("title").asText());row.setDescription(s.path("description").asText(""));row.setAcceptance(s.path("acceptance").asText(""));row.setPriority(s.path("priority").asText("Should"));row.setSprint(s.path("sprint").asInt(1));row.setActivity(s.path("activity").asInt(1));row.setStatus(s.path("status").asInt(0));row.setOwnerId(s.path("owner_id").isNull()?null:s.path("owner_id").asInt());row.setCreatedAt(LocalDateTime.now());stories.insert(row);} for(JsonNode t:o.path("tasks")){Task row=new Task();row.setId(t.path("id").asText());row.setName(t.path("name").asText());row.setOwnerId(t.path("owner_id").asInt());row.setHours(t.path("hours").asInt());row.setWeekStart(t.path("week_start").asInt());row.setWeekEnd(t.path("week_end").asInt());row.setStoryRef(t.path("story_ref").asText(null));row.setKanbanCardId(t.path("kanban_card_id").asText(null));row.setEstimatedHours(t.path("estimated_hours").asInt(row.getHours()));row.setTaskType("feature");row.setDependsOn(t.path("depends_on").asText(null));row.setPriority(t.path("priority").asText("Should"));row.setStatus(t.path("status").asInt(0));row.setProgress(t.path("progress").asInt(0));row.setBlocked(t.path("blocked").asBoolean(false)?1:0);tasks.insert(row);} }
        catch(ApiException e){throw e;} catch(Exception e){throw ApiException.conflict("项目数据写入失败，事务已回滚");}
    }
}
