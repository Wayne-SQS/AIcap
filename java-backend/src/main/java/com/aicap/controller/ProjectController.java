package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.fasterxml.jackson.databind.JsonNode;
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
    @PatchMapping("/{projectId}/members/{userId}/capacity")
    public Map<String,Object> updateMemberCapacity(@PathVariable String projectId, @PathVariable Integer userId,
                                                    @RequestBody JsonNode body) {
        Roles.writer();
        if (body == null || !body.has("weekly_capacity_hours"))
            throw ApiException.badRequest("须提供 weekly_capacity_hours");
        JsonNode value = body.get("weekly_capacity_hours");
        if (!value.isNull() && !value.isInt())
            throw ApiException.badRequest("周容量须为整数或 null");
        return projects.updateMemberCapacity(projectId, userId, value.isNull() ? null : value.intValue());
    }
    @PostMapping public Map<String,Object> create(@RequestBody Map<String,Object> body){ Roles.writer(); return projects.create(body); }
}
