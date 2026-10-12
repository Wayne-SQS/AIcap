package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectAccessService projects;

    @GetMapping public List<Map<String,Object>> list(){ Roles.any(); return projects.listAccessible(); }
    @GetMapping("/{projectId}") public Map<String,Object> get(@PathVariable String projectId){ Roles.any(); return projects.detail(projectId); }
    @GetMapping("/{projectId}/members") public List<Map<String,Object>> members(@PathVariable String projectId){ Roles.any(); return projects.projectMembers(projectId); }
    @PostMapping public Map<String,Object> create(@RequestBody Map<String,Object> body){ Roles.writer(); return projects.create(body); }
}
