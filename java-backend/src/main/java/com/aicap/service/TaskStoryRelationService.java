package com.aicap.service;

import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.TaskStoryRelation;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.TaskStoryRelationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Maintains the primary task story plus project-scoped additional story relations. */
@Service
@RequiredArgsConstructor
public class TaskStoryRelationService {
    private final TaskStoryRelationMapper relations;
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final ProjectScopedResolver resolver;

    public List<TaskStoryRelation> findAdditional(String projectId, Long taskInternalId) {
        return relations.selectList(new QueryWrapper<TaskStoryRelation>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .eq("task_internal_id", taskInternalId)
                .orderByAsc("internal_id"));
    }

    public List<String> storyCodes(String projectId, Task task) {
        List<String> codes = new ArrayList<>();
        if (task.getStoryInternalId() != null) {
            Story primary = stories.selectOne(new QueryWrapper<Story>()
                    .eq("project_id", ProjectAccessService.normalize(projectId))
                    .eq("internal_id", task.getStoryInternalId()));
            if (primary != null) codes.add(primary.getCode());
        }
        for (TaskStoryRelation relation : findAdditional(projectId, task.getInternalId())) {
            Story story = stories.selectOne(new QueryWrapper<Story>()
                    .eq("project_id", ProjectAccessService.normalize(projectId))
                    .eq("internal_id", relation.getStoryInternalId()));
            if (story != null && !codes.contains(story.getCode())) codes.add(story.getCode());
        }
        if (codes.isEmpty() && task.getStoryRef() != null) {
            codes.addAll(parseRefs(task.getStoryRef()));
        }
        return codes;
    }

    public String primaryStoryCode(String projectId, Task task) {
        List<String> codes = storyCodes(projectId, task);
        return codes.isEmpty() ? null : codes.get(0);
    }

    @Transactional
    public void replaceRelations(String projectId, Task task, String storyRef) {
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        List<String> codes = parseRefs(storyRef);
        List<Story> resolved = new ArrayList<>();
        for (String code : codes) resolved.add(resolver.requireStory(scopedProjectId, code));
        task.setStoryInternalId(resolved.isEmpty() ? null : resolved.get(0).getInternalId());
        task.setStoryRef(codes.isEmpty() ? null : String.join(",", codes));
        tasks.updateById(task);
        relations.delete(new QueryWrapper<TaskStoryRelation>()
                .eq("project_id", scopedProjectId)
                .eq("task_internal_id", task.getInternalId()));
        LocalDateTime now = LocalDateTime.now();
        for (Story story : resolved.subList(Math.min(1, resolved.size()), resolved.size())) {
            TaskStoryRelation relation = new TaskStoryRelation();
            relation.setProjectId(scopedProjectId);
            relation.setTaskInternalId(task.getInternalId());
            relation.setStoryInternalId(story.getInternalId());
            relation.setCreatedAt(now);
            relation.setUpdatedAt(now);
            relations.insert(relation);
        }
    }

    @Transactional
    public void removeForTask(String projectId, Long taskInternalId) {
        relations.delete(new QueryWrapper<TaskStoryRelation>()
                .eq("project_id", ProjectAccessService.normalize(projectId))
                .eq("task_internal_id", taskInternalId));
    }

    /** Rebuilds primary/additional links when a story is deleted. */
    @Transactional
    public void removeStory(String projectId, Long storyInternalId) {
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        List<Long> relatedTaskIds = relations.selectList(new QueryWrapper<TaskStoryRelation>()
                        .eq("project_id", scopedProjectId)
                        .eq("story_internal_id", storyInternalId)).stream()
                .filter(relation -> storyInternalId.equals(relation.getStoryInternalId()))
                .map(TaskStoryRelation::getTaskInternalId)
                .toList();
        // 空集合不能进 in():MyBatis-Plus 会原样拼出 "IN ()",MySQL 直接语法错误
        // —— 表现是删故事 500,而日志里只有一句 near '))))',查起来很费劲
        QueryWrapper<Task> affectedQuery = new QueryWrapper<Task>().eq("project_id", scopedProjectId);
        if (relatedTaskIds.isEmpty()) {
            affectedQuery.eq("story_internal_id", storyInternalId);
        } else {
            affectedQuery.and(w -> w.eq("story_internal_id", storyInternalId)
                    .or(x -> x.in("internal_id", relatedTaskIds)));
        }
        List<Task> affected = tasks.selectList(affectedQuery);
        for (Task task : affected) {
            List<String> codes = storyCodes(scopedProjectId, task).stream()
                    .filter(code -> {
                        Story story = resolver.findStory(scopedProjectId, code);
                        return story != null && !story.getInternalId().equals(storyInternalId);
                    }).toList();
            replaceRelations(scopedProjectId, task, String.join(",", codes));
        }
        relations.delete(new QueryWrapper<TaskStoryRelation>().eq("project_id", scopedProjectId).eq("story_internal_id", storyInternalId));
    }

    private List<String> parseRefs(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> refs = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT)).forEach(refs::add);
        return List.copyOf(refs);
    }
}
