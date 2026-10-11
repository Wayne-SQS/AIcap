package com.aicap.generation;

import com.aicap.agent.AgentProperties;
import com.aicap.common.ApiException;
import com.aicap.entity.ProjectGenerationEvent;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.entity.User;
import com.aicap.mapper.ProjectGenerationEventMapper;
import com.aicap.mapper.ProjectGenerationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectGenerationService {
    private static final int SOURCE_NAME_MAX_LENGTH = 255;
    private static final String DETAILED_SOURCE = "detailed_natural_language";
    private static final String OVERVIEW_SOURCE = "coarse_natural_language";

    private final ProjectGenerationRunMapper runs;
    private final ProjectGenerationEventMapper events;
    private final ProjectGenerationJobs jobs;
    private final ProjectGenerationValidator validator;
    private final ProjectGenerationImportService importer;
    private final ProjectGenerationExecutionService execution;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public Map<String,Object> create(String mode,String request,String strategy,User user){if(user==null)throw ApiException.unauthorized("未登录");if(!props.settingsReady())throw ApiException.server("服务端尚未配置模型密钥");if(request==null||request.isBlank())throw ApiException.badRequest("生成描述不能为空");String normalizedMode=normalizeNaturalLanguageMode(mode);ProjectGenerationRun r=base(normalizedMode,sourceNameForMode(normalizedMode),request,strategy,user);runs.insert(r);jobs.event(r,"queued","生成请求已入队");return out(r);}
    @Transactional
    public Map<String,Object> importFile(MultipartFile file,String strategy,User user){String sourceName=boundedFileName(file.getOriginalFilename());ProjectGenerationRun r=base("excel",sourceName,sourceName,strategy,user);runs.insert(r);jobs.event(r,"queued","导入请求已入队");r.setStatus("running");r.setUpdatedAt(LocalDateTime.now());runs.updateById(r);jobs.event(r,"running","正在解析并校验表格");var draft=validator.normalize(importer.parse(file), r.getStrategy());r.setDraftJson(draft.toString());r.setStatus("waiting_confirmation");r.setUpdatedAt(LocalDateTime.now());runs.updateById(r);jobs.event(r,"draft_generated","已解析表格并校验草案");jobs.event(r,"waiting_confirmation","草案已生成，等待人工确认");return out(r);}
    public Map<String,Object> get(String id){ProjectGenerationRun r=require(id);Map<String,Object> o=out(r);java.util.List<Map<String,Object>> es=new java.util.ArrayList<>();for(ProjectGenerationEvent e:events.selectList(new QueryWrapper<ProjectGenerationEvent>().eq("run_id",id).orderByAsc("id")))es.add(Map.of("kind",e.getKind(),"detail",e.getDetailJson(),"created_at",e.getCreatedAt()));o.put("events",es);return o;}
    @Transactional
    public Map<String,Object> updateStory(String id,String storyId,Map<String,Object> changes,User user){
        if(user==null)throw ApiException.unauthorized("未登录");
        ProjectGenerationRun r=require(id);
        if(!"waiting_confirmation".equals(r.getStatus()))throw ApiException.conflict("仅待确认草案可以编辑");
        try{
            JsonNode parsed=mapper.readTree(r.getDraftJson());
            if(!(parsed instanceof ObjectNode draft))throw ApiException.conflict("生成草案格式已失效，请重新生成");
            ObjectNode story=null;
            for(JsonNode row:draft.path("stories"))if(storyId.equals(row.path("id").asText())){story=(ObjectNode)row;break;}
            if(story==null)throw ApiException.notFound("草案 Story 不存在");
            String title=requiredText(changes,"title","故事名称不能为空");
            if(title.length()>200)throw ApiException.badRequest("故事名称不能超过 200 个字符");
            String priority=requiredText(changes,"priority","Priority 不能为空");
            if(!java.util.Set.of("Must","Should","Could").contains(priority))throw ApiException.badRequest("Priority 只能是 Must、Should 或 Could");
            Object sprintValue=changes.get("sprint");
            if(!(sprintValue instanceof Number))throw ApiException.badRequest("请选择有效 Sprint");
            String acceptance=requiredText(changes,"acceptance","验收标准不能为空");
            story.put("title",title);story.put("priority",priority);story.put("sprint",((Number)sprintValue).intValue());story.put("acceptance",acceptance);
            ObjectNode validated=validator.revalidateEditedDraft(draft,r.getStrategy());
            LocalDateTime now=LocalDateTime.now();
            if(runs.update(null,new UpdateWrapper<ProjectGenerationRun>().set("draft_json",validated.toString()).set("updated_at",now).eq("id",id).eq("status","waiting_confirmation"))!=1)throw ApiException.conflict("该草案已被处理，请刷新后重试");
            r=runs.selectById(id);jobs.event(r,"draft_edited","已人工修改故事草案 "+storyId+"，并重新校验");
            return get(id);
        }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.conflict("草案内容无法解析，请重新生成");}
    }
    public Map<String,Object> confirm(String id,boolean replaceConfirmed,User user){ProjectGenerationRun r=require(id);if(!"waiting_confirmation".equals(r.getStatus()))throw ApiException.conflict("该生成草案不在待确认状态");if("replace".equals(r.getStrategy())&&!replaceConfirmed)throw ApiException.badRequest("替换现有项目需要再次确认");if(runs.update(null,new UpdateWrapper<ProjectGenerationRun>().set("status","executing").set("updated_at",LocalDateTime.now()).eq("id",id).eq("status","waiting_confirmation"))!=1)throw ApiException.conflict("该草案已被处理");r=require(id);jobs.event(r,"confirmed","用户确认执行项目图");jobs.event(r,"action_started","开始写入项目图业务数据");try{execution.execute(r.getDraftJson(),r.getStrategy(),user);jobs.event(r,"action_completed","项目图业务数据写入完成");r.setStatus("completed");r.setUpdatedAt(LocalDateTime.now());runs.updateById(r);jobs.event(r,"completed","项目图已写入统一项目数据");return out(r);}catch(RuntimeException e){r.setStatus("failed");r.setErrorCode("execution_failed");r.setErrorMessage(e.getMessage()==null?"项目图写入失败":e.getMessage());r.setUpdatedAt(LocalDateTime.now());runs.updateById(r);jobs.event(r,"failed",r.getErrorMessage());throw e;}}
    @Transactional public Map<String,Object> cancel(String id,User user){ProjectGenerationRun r=require(id);if(runs.update(null,new UpdateWrapper<ProjectGenerationRun>().set("status","cancelled").set("updated_at",LocalDateTime.now()).eq("id",id).eq("status","waiting_confirmation"))!=1)throw ApiException.conflict("该草案已被处理");jobs.event(r,"cancelled","用户取消，未修改项目数据");r.setStatus("cancelled");return out(r);}
    private ProjectGenerationRun base(String mode,String sourceName,String requestText,String strategy,User u){ProjectGenerationRun r=new ProjectGenerationRun();r.setId(UUID.randomUUID().toString());r.setRequestedBy(u.getId());r.setMode(mode);r.setStrategy("replace".equals(strategy)?"replace":"merge");r.setSourceName(sourceName);r.setRequestText(requestText);r.setStatus("queued");r.setAttempt(1);r.setModel(props.getModel());r.setPromptVersion("project-generator-v1");r.setCreatedAt(LocalDateTime.now());r.setUpdatedAt(LocalDateTime.now());return r;}
    private String normalizeNaturalLanguageMode(String mode){return "overview".equals(mode)?"overview":"detailed";}
    private String sourceNameForMode(String mode){return "overview".equals(mode)?OVERVIEW_SOURCE:DETAILED_SOURCE;}
    private String boundedFileName(String originalName){String name=originalName==null?"":originalName.replace('\\','/');int separator=name.lastIndexOf('/');if(separator>=0)name=name.substring(separator+1);name=name.trim();if(name.isEmpty())name="uploaded_file";return name.length()<=SOURCE_NAME_MAX_LENGTH?name:name.substring(0,SOURCE_NAME_MAX_LENGTH);}
    private String requiredText(Map<String,Object> values,String key,String message){Object value=values==null?null:values.get(key);String text=value==null?"":String.valueOf(value).trim();if(text.isBlank())throw ApiException.badRequest(message);return text;}
    private ProjectGenerationRun require(String id){ProjectGenerationRun r=runs.selectById(id);if(r==null)throw ApiException.notFound("生成运行不存在");return r;}
    private Map<String,Object> out(ProjectGenerationRun r){Map<String,Object> o=new LinkedHashMap<>();o.put("id",r.getId());o.put("mode",r.getMode());o.put("strategy",r.getStrategy());o.put("source_name",r.getSourceName());o.put("request_text",r.getRequestText());o.put("status",r.getStatus());o.put("model",r.getModel());try{o.put("draft",r.getDraftJson()==null?null:mapper.readTree(r.getDraftJson()));}catch(Exception e){o.put("draft",null);}o.put("error_code",r.getErrorCode());o.put("error_message",r.getErrorMessage());o.put("created_at",r.getCreatedAt());o.put("updated_at",r.getUpdatedAt());return o;}
}
