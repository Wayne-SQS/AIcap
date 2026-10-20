package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.Milestone;
import com.aicap.entity.Task;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 甘特图和 Planning Agent 共用的真实里程碑接口(读 + 手工维护)。 */
@RestController
@RequestMapping("/api/milestones")
@RequiredArgsConstructor
public class MilestoneController {
    private final MilestoneMapper milestones;
    private final TaskMapper tasks;
    private final ProjectAccessService projectAccess;
    private final JdbcTemplate jdbc;

    @GetMapping
    public List<Milestone> list(@RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        return milestones.selectList(new QueryWrapper<Milestone>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .orderByAsc("week").orderByAsc("code"));
    }

    @PostMapping
    @Transactional
    public Milestone create(@RequestBody JsonNode body, @RequestParam String projectId) {
        Roles.writer();
        projectAccess.requireWriter(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        if (!body.path("name").isTextual() || body.path("name").asText().isBlank()
                || body.path("name").asText().length() > 200)
            throw ApiException.unprocessable("name 必须为非空字符串且不超过 200 字");
        if (!body.path("week").isIntegralNumber() || body.path("week").asInt() < 1 || body.path("week").asInt() > 6)
            throw ApiException.unprocessable("week 必须在 1..6 之间");
        int week = body.path("week").asInt();
        String id = body.path("id").asText("").trim();
        if (id.isEmpty()) {
            int max = 0;
            for (Milestone row : milestones.selectList(new QueryWrapper<Milestone>().eq("project_id", scoped))) {
                if (row.getId() != null && row.getId().matches("MS\\d+")) {
                    max = Math.max(max, Integer.parseInt(row.getId().substring(2)));
                }
            }
            id = String.format("MS%02d", max + 1);
        }
        if (id.length() > 20) throw ApiException.unprocessable("id 不能超过 20 字");
        if (milestones.selectOne(new QueryWrapper<Milestone>()
                .eq("project_id", scoped).eq("code", id)) != null)
            throw ApiException.conflict("里程碑编号已存在");
        String status = body.path("status").asText("planned");
        if (!Set.of("planned", "in_progress", "done", "delayed").contains(status))
            throw ApiException.unprocessable("里程碑状态无效");
        List<String> related = java.util.Arrays.stream(body.path("related_task_ids").asText("").split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
        List<Task> taskRows = tasks.selectList(new QueryWrapper<Task>().eq("project_id", scoped));
        Set<String> taskIds = taskRows.stream().map(Task::getId).collect(Collectors.toSet());
        if (!taskIds.containsAll(related)) throw ApiException.badRequest("关联任务不存在");
        if (taskRows.stream().anyMatch(t -> related.contains(t.getId())
                && t.getWeekEnd() != null && t.getWeekEnd() > week))
            throw ApiException.badRequest("里程碑周次早于关联任务结束周");
        Milestone milestone = new Milestone();
        milestone.setProjectId(scoped);
        milestone.setId(id);
        milestone.setName(body.path("name").asText().trim());
        milestone.setWeek(week);
        milestone.setDescription(body.path("description").asText(""));
        milestone.setStatus(status);
        String joined = String.join(",", related);
        milestone.setRelatedTaskIds(joined);
        milestone.setRelatedTaskIdsLegacy(joined);
        milestones.insert(milestone);
        for (Task task : taskRows) {
            if (related.contains(task.getId()) && task.getInternalId() != null) {
                jdbc.update("INSERT IGNORE INTO milestone_tasks(project_id,milestone_internal_id,task_internal_id,created_at)"
                        + " VALUES (?,?,?,NOW())", scoped, milestone.getInternalId(), task.getInternalId());
            }
        }
        return milestone;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Boolean> delete(@PathVariable String id, @RequestParam String projectId) {
        Roles.writer();
        projectAccess.requireWriter(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        Milestone existing = milestones.selectOne(new QueryWrapper<Milestone>()
                .eq("project_id", scoped).eq("code", id));
        if (existing == null) throw ApiException.notFound("里程碑不存在");
        if (existing.getInternalId() != null)
            jdbc.update("DELETE FROM milestone_tasks WHERE project_id=? AND milestone_internal_id=?",
                    scoped, existing.getInternalId());
        milestones.deleteById(existing.getInternalId());
        return Map.of("ok", true);
    }
}
