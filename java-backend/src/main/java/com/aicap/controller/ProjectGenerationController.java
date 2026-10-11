package com.aicap.controller;

import com.aicap.generation.ProjectGenerationService;
import com.aicap.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/project-generator")
@RequiredArgsConstructor
public class ProjectGenerationController {
    private final ProjectGenerationService service;
    @PostMapping("/runs") public Map<String,Object> create(@RequestBody Map<String,Object> body){var u=Roles.any();return service.create(String.valueOf(body.getOrDefault("mode","detailed")),String.valueOf(body.getOrDefault("request","")),String.valueOf(body.getOrDefault("strategy","merge")),u);}
    @PostMapping(value="/import",consumes="multipart/form-data") public Map<String,Object> importFile(@RequestPart("file") MultipartFile file,@RequestParam(defaultValue="merge") String strategy){return service.importFile(file,strategy,Roles.any());}
    @GetMapping("/runs/{id}") public Map<String,Object> get(@PathVariable String id){Roles.any();return service.get(id);}
    @PatchMapping("/runs/{id}/stories/{storyId}") public Map<String,Object> updateStory(@PathVariable String id,@PathVariable String storyId,@RequestBody Map<String,Object> body){return service.updateStory(id,storyId,body,Roles.writer());}
    @PostMapping("/runs/{id}/confirm") public Map<String,Object> confirm(@PathVariable String id,@RequestBody(required=false) Map<String,Object> body){boolean replace=body!=null&&Boolean.TRUE.equals(body.get("replace_confirmed"));return service.confirm(id,replace,Roles.writer());}
    @PostMapping("/runs/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id){return service.cancel(id,Roles.writer());}
}
