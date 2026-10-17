package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.Milestone;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.security.Roles;
import com.aicap.security.AuthContext;
import com.aicap.security.ProjectAccess;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 甘特图和 Planning Agent 共用的真实里程碑只读接口。 */
@RestController
@RequestMapping("/api/milestones")
@RequiredArgsConstructor
public class MilestoneController {
    private final MilestoneMapper milestones;
    private final TaskMapper tasks;
    private final ProjectAccess projectAccess;

    @GetMapping
    public List<Milestone> list() {
        Roles.any();
        return milestones.selectList(new QueryWrapper<Milestone>().eq("project_id", AuthContext.projectId())
                .orderByAsc("week").orderByAsc("id"));
    }

    @PostMapping
    @Transactional
    public Milestone create(@RequestBody JsonNode body) {
        Roles.writer();
        if (!body.path("name").isTextual() || body.path("name").asText().isBlank()
                || body.path("name").asText().length() > 200) throw ApiException.unprocessable("name 必须为非空字符串且不超过 200 字");
        if (!body.path("week").isIntegralNumber() || body.path("week").asInt() < 1 || body.path("week").asInt() > 6)
            throw ApiException.unprocessable("week 必须在 1..6 之间");
        String id = body.path("id").asText("").trim();
        if (id.isEmpty()) {
            int next = milestones.selectList(null).stream().map(Milestone::getId)
                    .filter(v -> v.matches("MS\\d+"))
                    .mapToInt(v -> Integer.parseInt(v.substring(2))).max().orElse(0) + 1;
            id = String.format("MS%02d", next);
        }
        if (id.length() > 20) throw ApiException.unprocessable("id 不能超过 20 字");
        if (milestones.selectById(id) != null) throw ApiException.conflict("里程碑编号已存在");
        String status = body.path("status").asText("planned");
        if (!Set.of("planned", "in_progress", "done", "delayed").contains(status))
            throw ApiException.unprocessable("里程碑状态无效");
        String related = body.path("related_task_ids").asText("");
        List<String> ids = related.isBlank() ? List.of() : java.util.Arrays.stream(related.split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
        var taskRows = tasks.selectList(new QueryWrapper<com.aicap.entity.Task>().eq("project_id", AuthContext.projectId()));
        if (!taskRows.stream().map(t -> t.getId()).collect(Collectors.toSet()).containsAll(ids))
            throw ApiException.badRequest("关联任务不存在");
        int week = body.path("week").asInt();
        if (taskRows.stream().anyMatch(t -> ids.contains(t.getId()) && t.getWeekEnd() != null && t.getWeekEnd() > week))
            throw ApiException.badRequest("里程碑周次早于关联任务结束周");
        Milestone milestone = new Milestone();
        milestone.setProjectId(AuthContext.projectId());
        milestone.setId(id);
        milestone.setName(body.path("name").asText().trim());
        milestone.setWeek(week);
        milestone.setDescription(body.path("description").asText(""));
        milestone.setStatus(status);
        milestone.setRelatedTaskIds(String.join(",", ids));
        milestones.insert(milestone);
        return milestone;
    }

    @DeleteMapping("/{id}")
    public java.util.Map<String, Boolean> delete(@PathVariable String id) {
        Roles.writer();
        Milestone existing = milestones.selectById(id);
        if (existing == null || !projectAccess.inCurrentProject(existing.getProjectId()))
            throw ApiException.notFound("里程碑不存在");
        if (milestones.deleteById(id) == 0) throw ApiException.notFound("里程碑不存在");
        return java.util.Map.of("ok", true);
    }
}
