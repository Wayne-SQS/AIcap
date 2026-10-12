package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.Task;
import com.aicap.entity.Story;
import com.aicap.entity.User;
import com.aicap.entity.Milestone;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.mapper.MilestoneMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** 四视图和规划 Agent 共用的业务动作入口。 */
@Service
@RequiredArgsConstructor
public class ProjectPlanningActionService {
    private final TaskMapper taskMapper;
    private final StoryMapper storyMapper;
    private final UserMapper userMapper;
    private final MilestoneMapper milestoneMapper;
    private final ProjectAccessService projectAccess;
    private final ProjectScopedResolver resolver;

    @Transactional
    public Task updateTaskOwner(String projectId, String taskId, Integer ownerId, User actor) {
        if (actor == null || !SetUtil.WRITER_ROLES.contains(actor.getRole())) {
            throw ApiException.forbidden("无权限执行此操作");
        }
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        projectAccess.requireWriter(scopedProjectId);
        Task task = resolver.requireTask(scopedProjectId, taskId);
        User owner = userMapper.selectById(ownerId);
        if (owner == null) throw ApiException.badRequest("负责人不存在");
        projectAccess.requireProjectMember(scopedProjectId, ownerId);
        task.setOwnerId(ownerId);
        taskMapper.updateById(task);
        return task;
    }

    public Map<String, Object> ownerImpact(String projectId, String taskId, Integer ownerId) {
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        projectAccess.requireMember(scopedProjectId);
        Task task = resolver.requireTask(scopedProjectId, taskId);
        User owner = userMapper.selectById(ownerId);
        if (task == null || owner == null) throw ApiException.badRequest("任务或负责人不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", taskId);
        out.put("to_owner_id", ownerId);
        out.put("to_owner_name", owner.getDisplayName());
        out.put("hours", task.getHours());
        return out;
    }

    @Transactional
    public Task updateTaskSchedule(String projectId, String taskId, Integer startWeek, Integer endWeek, User actor) {
        requireWriter(actor);
        if (startWeek == null || endWeek == null || startWeek < 1 || endWeek > 6 || startWeek > endWeek) {
            throw ApiException.badRequest("任务排期必须在 W1-W6 且开始周不能晚于结束周");
        }
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        projectAccess.requireWriter(scopedProjectId);
        Task task = resolver.requireTask(scopedProjectId, taskId);
        task.setWeekStart(startWeek);
        task.setWeekEnd(endWeek);
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public Story updateStorySprint(String projectId, String storyId, Integer sprint, User actor) {
        requireWriter(actor);
        if (sprint == null || sprint < 1 || sprint > 4) throw ApiException.badRequest("Sprint 必须在 1-4 之间");
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        projectAccess.requireWriter(scopedProjectId);
        Story story = resolver.requireStory(scopedProjectId, storyId);
        story.setSprint(sprint);
        storyMapper.updateById(story);
        return story;
    }

    @Transactional
    public Task updateTaskDependency(String projectId, String taskId, String before, String after, User actor) {
        requireWriter(actor); String scopedProjectId = ProjectAccessService.normalize(projectId); projectAccess.requireWriter(scopedProjectId); Task task = requireTask(scopedProjectId, taskId); String current = normalize(task.getDependsOn());
        if (!current.equals(normalize(before))) throw ApiException.conflict("任务依赖已发生变化，请重新生成规划");
        String next = normalize(after); Set<String> deps = new LinkedHashSet<>(csv(next));
        if (deps.contains(task.getId())) throw ApiException.badRequest("任务不能依赖自身");
        for (String dep : deps) resolver.requireTask(scopedProjectId, dep);
        if (hasCycle(scopedProjectId, task.getId(), deps)) throw ApiException.badRequest("任务依赖不能形成循环");
        task.setDependsOn(next.isBlank() ? null : next); taskMapper.updateById(task); return task;
    }

    @Transactional
    public Task updateTaskPriority(String projectId, String taskId, String before, String after, User actor) {
        requireWriter(actor); String scopedProjectId = ProjectAccessService.normalize(projectId); projectAccess.requireWriter(scopedProjectId); Task task = requireTask(scopedProjectId, taskId); String current = task.getPriority() == null ? "Should" : task.getPriority();
        if (!current.equals(before)) throw ApiException.conflict("任务优先级已发生变化，请重新生成规划");
        if (!Set.of("Must", "Should", "Could").contains(after)) throw ApiException.badRequest("任务优先级必须是 Must/Should/Could");
        task.setPriority(after); taskMapper.updateById(task); return task;
    }

    @Transactional
    public Milestone createMilestone(String projectId, String id, String name, Integer week, String description, String related, User actor) {
        requireWriter(actor); String scopedProjectId = ProjectAccessService.normalize(projectId); projectAccess.requireWriter(scopedProjectId); validateMilestone(scopedProjectId, id, name, week, related);
        if (resolver.findMilestone(scopedProjectId, id) != null) throw ApiException.conflict("里程碑已存在: " + id);
        Milestone row = new Milestone(); row.setProjectId(scopedProjectId); row.setId(id); row.setName(name); row.setWeek(week); row.setDescription(description == null ? "" : description); row.setStatus("planned"); row.setRelatedTaskIds(normalize(related));
        milestoneMapper.insert(row); return row;
    }

    @Transactional
    public Milestone updateMilestone(String projectId, String id, String beforeJson, String afterName, Integer afterWeek, String afterDescription, String afterRelated, User actor) {
        requireWriter(actor); String scopedProjectId = ProjectAccessService.normalize(projectId); projectAccess.requireWriter(scopedProjectId); Milestone row = resolver.requireMilestone(scopedProjectId, id);
        if (beforeJson != null && !beforeJson.isBlank() && !beforeJson.equals(snapshot(row))) throw ApiException.conflict("里程碑已发生变化，请重新生成规划");
        validateMilestone(scopedProjectId, id, afterName, afterWeek, afterRelated); row.setName(afterName); row.setWeek(afterWeek); row.setDescription(afterDescription == null ? "" : afterDescription); row.setRelatedTaskIds(normalize(afterRelated)); milestoneMapper.updateById(row); return row;
    }

    private Task requireTask(String projectId, String id) { return resolver.requireTask(projectId, id); }
    private void validateMilestone(String projectId, String id, String name, Integer week, String related) { if (id == null || !id.matches("M[A-Z0-9_-]+")) throw ApiException.badRequest("里程碑编号格式无效"); if (name == null || name.isBlank()) throw ApiException.badRequest("里程碑名称不能为空"); if (week == null || week < 1 || week > 6) throw ApiException.badRequest("里程碑周次必须在 W1-W6"); for (String ref : csv(normalize(related))) resolver.requireTask(projectId, ref); }
    private String normalize(String value) { return String.join(",", new LinkedHashSet<>(csv(value))); }
    private Set<String> csv(String value) { if (value == null || value.isBlank()) return new LinkedHashSet<>(); return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).map(String::toUpperCase).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)); }
    private boolean hasCycle(String projectId, String id, Set<String> next) { return reaches(projectId, id, id, next, new LinkedHashSet<>()); }
    private boolean reaches(String projectId, String target, String current, Set<String> replacement, Set<String> seen) { if (!seen.add(current)) return false; Task row = resolver.findTask(projectId, current); Set<String> deps = current.equals(target) ? replacement : csv(row == null ? "" : row.getDependsOn()); for (String dep : deps) { if (target.equals(dep) || reaches(projectId, target, dep, replacement, seen)) return true; } return false; }
    private String snapshot(Milestone row) { return row.getName() + "|" + row.getWeek() + "|" + (row.getDescription() == null ? "" : row.getDescription()) + "|" + normalize(row.getRelatedTaskIds()); }


    private void requireWriter(User actor) {
        if (actor == null || !SetUtil.WRITER_ROLES.contains(actor.getRole())) {
            throw ApiException.forbidden("无权限执行此操作");
        }
    }

    private static final class SetUtil {
        private static final java.util.Set<String> WRITER_ROLES = java.util.Set.of("admin", "owner", "member");
    }
}
