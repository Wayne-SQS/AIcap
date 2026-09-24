package com.aicap.generation;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentProperties;
import com.aicap.agent.ModelClient;
import com.aicap.entity.ProjectGenerationEvent;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.mapper.ProjectGenerationEventMapper;
import com.aicap.mapper.ProjectGenerationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ProjectGenerationJobs {
    private final ProjectGenerationRunMapper runs;
    private final ProjectGenerationEventMapper events;
    private final ProjectGenerationRunner runner;
    private final ProjectGenerationValidator validator;
    private final ModelClient client;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public boolean runNext() {
        ProjectGenerationRun run=runs.selectOne(new QueryWrapper<ProjectGenerationRun>().eq("status","queued").orderByAsc("created_at").last("LIMIT 1"));
        if(run==null)return false;
        int claimed=runs.update(null,new UpdateWrapper<ProjectGenerationRun>().set("status","running").set("updated_at",LocalDateTime.now()).eq("id",run.getId()).eq("status","queued"));
        if(claimed==0)return true;
        event(run,"running","开始生成项目规划草案");
        try { client.setModelOverride(run.getModel()); var raw=runner.generate(run.getRequestText(),run.getMode(),client); var draft=validator.normalize(raw, run.getStrategy()); run.setDraftJson(draft.toString());run.setStatus("waiting_confirmation");run.setUpdatedAt(LocalDateTime.now());runs.updateById(run);event(run,"draft_generated","已生成并校验统一项目草案");event(run,"waiting_confirmation","草案已生成，等待人工确认"); }
        catch(AgentError e){fail(run,e.getCode(),e.getMessage());}
        catch(Exception e){fail(run,"generation_failed",e.getMessage()==null?"项目规划生成失败":e.getMessage());}
        finally { client.clearModelOverride(); }
        return true;
    }
    public void event(ProjectGenerationRun run,String kind,String message){ProjectGenerationEvent e=new ProjectGenerationEvent();e.setRunId(run.getId());e.setAttempt(run.getAttempt());e.setKind(kind);e.setDetailJson(mapper.createObjectNode().put("message",message==null?"":message).toString());e.setCreatedAt(LocalDateTime.now());events.insert(e);}
    private void fail(ProjectGenerationRun run,String code,String message){run.setStatus("failed");run.setErrorCode(code);run.setErrorMessage(message);run.setUpdatedAt(LocalDateTime.now());runs.updateById(run);event(run,"failed",message);}
}
