package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.Milestone;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves business codes inside an explicit project boundary.
 *
 * Business codes are resolved inside a project and the returned entity carries
 * the internal BIGINT primary key for subsequent updates.
 */
@Service
@RequiredArgsConstructor
public class ProjectScopedResolver {
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final MilestoneMapper milestones;

    public Story findStory(String projectId, String code) {
        return stories.selectOne(new QueryWrapper<Story>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .eq("code", normalizeCode(code)));
    }

    public Story requireStory(String projectId, String code) {
        Story row = findStory(projectId, code);
        if (row == null) throw ApiException.notFound("故事不存在: " + code);
        return row;
    }

    public Task findTask(String projectId, String code) {
        return tasks.selectOne(new QueryWrapper<Task>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .eq("code", normalizeCode(code)));
    }

    public Task requireTask(String projectId, String code) {
        Task row = findTask(projectId, code);
        if (row == null) throw ApiException.notFound("任务不存在: " + code);
        return row;
    }

    public Milestone findMilestone(String projectId, String code) {
        return milestones.selectOne(new QueryWrapper<Milestone>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .eq("code", normalizeCode(code)));
    }

    public Milestone requireMilestone(String projectId, String code) {
        Milestone row = findMilestone(projectId, code);
        if (row == null) throw ApiException.notFound("里程碑不存在: " + code);
        return row;
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) throw ApiException.badRequest("业务编号不能为空");
        return code.trim().toUpperCase();
    }
}
