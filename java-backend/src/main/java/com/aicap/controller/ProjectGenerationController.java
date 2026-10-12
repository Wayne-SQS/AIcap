package com.aicap.controller;

import com.aicap.generation.ProjectGenerationService;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/project-generator")
@RequiredArgsConstructor
public class ProjectGenerationController {
    private final ProjectGenerationService service;
    private final ProjectAccessService projectAccess;
    @PostMapping("/runs")
    public Map<String,Object> create(@RequestBody Map<String,Object> body) {
        var user = Roles.any();
        if (isCreateMode(body)) {
            return service.createNew(
                    text(body, "mode", "detailed"),
                    text(body, "request", ""),
                    text(body, "project_name", ""),
                    text(body, "project_description", ""),
                    number(body.get("total_sprints"), 3),
                    user);
        }

        Object rawProject = body.containsKey("project_id") ? body.get("project_id") : body.get("projectId");
        String projectId = ProjectAccessService.normalize(rawProject == null ? "" : String.valueOf(rawProject));
        projectAccess.requireMember(projectId);
        return service.create(projectId, text(body, "mode", "detailed"), text(body, "request", ""),
                text(body, "strategy", "merge"), user);
    }
    @PostMapping(value="/import",consumes="multipart/form-data") public Map<String,Object> importFile(@RequestPart("file") MultipartFile file,@RequestParam(defaultValue="merge") String strategy,@RequestParam(required=false) String projectId,@RequestParam(defaultValue="existing") String context,@RequestParam(defaultValue="") String projectName,@RequestParam(defaultValue="") String projectDescription,@RequestParam(defaultValue="0") int totalSprints){var u=Roles.any();if(isCreate(context))return service.importNew(file,projectName,projectDescription,totalSprints,u);String scopedProjectId=ProjectAccessService.normalize(projectId);projectAccess.requireMember(scopedProjectId);return service.importFile(scopedProjectId,file,strategy,u);}
    @GetMapping("/runs/{id}") public Map<String,Object> get(@PathVariable String id,@RequestParam(required=false) String projectId,@RequestParam(defaultValue="existing") String context){var u=Roles.any();if(isCreate(context))return service.getNew(id,u);String scopedProjectId=ProjectAccessService.normalize(projectId);projectAccess.requireMember(scopedProjectId);return service.get(id,scopedProjectId);}
    @PatchMapping("/runs/{id}/stories/{storyId}") public Map<String,Object> updateStory(@PathVariable String id,@PathVariable String storyId,@RequestParam(required=false) String projectId,@RequestParam(defaultValue="existing") String context,@RequestBody Map<String,Object> body){var u=Roles.writer();if(isCreate(context))return service.updateStoryNew(id,storyId,body,u);String scopedProjectId=ProjectAccessService.normalize(projectId);projectAccess.requireWriter(scopedProjectId);return service.updateStory(id,scopedProjectId,storyId,body,u);}
    @PostMapping("/runs/{id}/confirm") public Map<String,Object> confirm(@PathVariable String id,@RequestParam(required=false) String projectId,@RequestParam(defaultValue="existing") String context,@RequestBody(required=false) Map<String,Object> body){var u=Roles.writer();if(isCreate(context))return service.confirmNew(id,u);String scopedProjectId=ProjectAccessService.normalize(projectId);projectAccess.requireWriter(scopedProjectId);boolean replace=body!=null&&Boolean.TRUE.equals(body.get("replace_confirmed"));return service.confirm(id,scopedProjectId,replace,u);}
    @PostMapping("/runs/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id,@RequestParam(required=false) String projectId,@RequestParam(defaultValue="existing") String context){var u=Roles.writer();if(isCreate(context))return service.cancelNew(id,u);String scopedProjectId=ProjectAccessService.normalize(projectId);projectAccess.requireWriter(scopedProjectId);return service.cancel(id,scopedProjectId,u);}
    private boolean isCreateMode(Map<String,Object> body){return "create".equalsIgnoreCase(text(body,"context","").trim())&&"create".equalsIgnoreCase(text(body,"strategy","").trim());}
    private boolean isCreate(Object context){return "create".equalsIgnoreCase(String.valueOf(context).trim());}
    private String text(Map<String,Object> body,String key,String fallback){Object value=body.get(key);return value==null?fallback:String.valueOf(value);}
    private int number(Object value,int fallback){if(value instanceof Number n)return n.intValue();try{return Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return fallback;}}
}
