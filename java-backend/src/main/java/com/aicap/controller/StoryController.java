package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.StoryDtos;
import com.aicap.entity.Story;
import com.aicap.entity.Epic;
import com.aicap.entity.StoryLog;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.StoryLogMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.EpicMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.security.Roles;
import com.aicap.service.IdAllocator;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.TaskStoryRelationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 用户故事/看板卡接口(对齐 FastAPI routers/stories.py)。
 * 删除卡时按 undone 策略级联处理未完成子任务(cancel/keep/detach)。
 */
@RestController
@RequestMapping("/api/stories")
@RequiredArgsConstructor
public class StoryController {

    private final StoryMapper storyMapper;
    private final EpicMapper epicMapper;
    private final StoryLogMapper storyLogMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    /** 编号分配:原为本类私有 nextStoryId(),与 TaskPoolController 的同名方法逐字重复,已集中 */
    private final IdAllocator idAllocator;
    private final ProjectAccessService projectAccess;
    private final TaskStoryRelationService taskStoryRelations;

    // ---------- 工具 ----------

    @Transactional
    void addLog(String projectId, String storyId, String logType, String detail, Integer userId) {
        StoryLog log = new StoryLog();
        log.setProjectId(projectId);
        log.setStoryId(storyId);
        log.setLogType(logType);
        log.setDetail(detail);
        log.setUserId(userId);
        log.setCreatedAt(LocalDateTime.now());
        storyLogMapper.insert(log);
    }

    private void checkOwner(String projectId, Integer ownerId) {
        if (ownerId != null && userMapper.selectById(ownerId) == null) {
            throw ApiException.badRequest("负责人不存在");
        }
        projectAccess.requireProjectMember(projectId, ownerId);
    }

    private void checkEpic(String projectId, Long epicId) {
        if (epicId == null) return;
        if (epicMapper.selectCount(new QueryWrapper<Epic>().eq("project_id", projectId).eq("id", epicId)) == 0) {
            throw ApiException.badRequest("史诗不是当前项目的史诗");
        }
    }

    // ---------- 接口 ----------

    /** GET /api/stories/logs 最近 50 条(对齐 recent_logs) */
    @GetMapping("/logs")
    public List<StoryDtos.LogOut> recentLogs(@RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        List<StoryLog> logs = storyLogMapper.selectList(new QueryWrapper<StoryLog>().eq("project_id", ProjectAccessService.normalize(projectId)).orderByDesc("id"));
        return logs.stream().limit(50)
                .map(l -> new StoryDtos.LogOut(l.getId(), l.getStoryId(), l.getLogType(),
                        l.getDetail(), l.getCreatedAt()))
                .toList();
    }

    /** GET /api/stories?owner=&sprint=&status=&activity=&priority=&q= */
    @GetMapping("")
    public List<StoryDtos.StoryOut> list(@RequestParam(required = false) Integer owner,
                                         @RequestParam(required = false) Integer sprint,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) Integer activity,
                                         @RequestParam(required = false) String priority,
                                         @RequestParam(required = false) String q,
                                         @RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        QueryWrapper<Story> qw = new QueryWrapper<Story>().eq("project_id", ProjectAccessService.normalize(projectId));
        if (owner != null) qw.eq("owner_id", owner);
        if (sprint != null) qw.eq("sprint", sprint);
        if (status != null) qw.eq("status", status);
        if (activity != null) qw.eq("activity", activity);
        if (StringUtils.hasText(priority)) qw.eq("priority", priority);
        if (StringUtils.hasText(q)) {
            String like = "%" + q + "%";
            qw.and(w -> w.like("code", like).or().like("title", like));
        }
        qw.orderByAsc("code");
        return StoryDtos.toOutList(storyMapper.selectList(qw));
    }

    /** POST /api/stories 新建(admin/owner/member) */
    @PostMapping("")
    @Transactional
    public StoryDtos.StoryOut create(@Valid @RequestBody StoryDtos.StoryIn body, @RequestParam String projectId) {
        User user = Roles.writer();
        projectAccess.requireWriter(projectId);
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        checkOwner(scopedProjectId, body.ownerId());
        checkEpic(scopedProjectId, body.epicId());
        String sid = idAllocator.nextStoryId(scopedProjectId);
        Story story = new Story();
        story.setProjectId(scopedProjectId);
        story.setId(sid);
        story.setTitle(body.title().trim());
        story.setDescription(body.description() == null ? "" : body.description());
        story.setRole(body.role() == null ? "" : body.role());
        story.setGoal(body.goal() == null ? "" : body.goal());
        story.setValue(body.value() == null ? "" : body.value());
        story.setEpicId(body.epicId());
        story.setAcceptance(body.acceptance() == null ? "" : body.acceptance());
        story.setPriority(body.priority());
        story.setSprint(body.sprint());
        story.setActivity(body.activity());
        story.setStatus(body.status());
        story.setOwnerId(body.ownerId());
        story.setCreatedAt(LocalDateTime.now());
        storyMapper.insert(story);
        addLog(scopedProjectId, sid, "create", story.getTitle(), user.getId());
        return StoryDtos.toOut(story);
    }

    /** PATCH /api/stories/{id} 局部更新(admin/owner/member) */
    @PatchMapping("/{storyId}")
    @Transactional
    public StoryDtos.StoryOut patch(@PathVariable String storyId,
                                    @Valid @RequestBody StoryDtos.StoryPatch body,
                                    @RequestParam String projectId) {
        User user = Roles.writer();
        projectAccess.requireWriter(projectId);
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        Story story = storyMapper.selectOne(new QueryWrapper<Story>().eq("project_id", scopedProjectId).eq("code", storyId));
        if (story == null) {
            throw ApiException.notFound("故事不存在");
        }
        Integer oldStatus = story.getStatus();
        Integer oldOwner = story.getOwnerId();
        Integer oldSprint = story.getSprint();
        boolean changed = false;
        if (body.ownerId() != null && !body.ownerId().equals(story.getOwnerId())) {
            checkOwner(scopedProjectId, body.ownerId());
            story.setOwnerId(body.ownerId());
            changed = true;
        }
        if (body.title() != null) { story.setTitle(body.title().trim()); changed = true; }
        if (body.description() != null) { story.setDescription(body.description()); changed = true; }
        if (body.role() != null) { story.setRole(body.role()); changed = true; }
        if (body.goal() != null) { story.setGoal(body.goal()); changed = true; }
        if (body.value() != null) { story.setValue(body.value()); changed = true; }
        if (body.epicId() != null) {
            JsonNode requested = body.epicId();
            if (!requested.isNull() && (!requested.isIntegralNumber() || requested.asLong() <= 0))
                throw ApiException.badRequest("epic_id 必须为正整数或 null");
            Long epicId = requested.isNull() ? null : requested.asLong();
            checkEpic(scopedProjectId, epicId);
            if (!Objects.equals(epicId, story.getEpicId())) {
                story.setEpicId(epicId);
                changed = true;
            }
        }
        if (body.acceptance() != null) { story.setAcceptance(body.acceptance()); changed = true; }
        if (body.priority() != null) { story.setPriority(body.priority()); changed = true; }
        if (body.sprint() != null) { story.setSprint(body.sprint()); changed = true; }
        if (body.activity() != null) { story.setActivity(body.activity()); changed = true; }
        if (body.status() != null && !body.status().equals(story.getStatus())) {
            story.setStatus(body.status());
            changed = true;
        }
        if (!changed) {
            return StoryDtos.toOut(story);
        }
        boolean cascadeOwner = Boolean.TRUE.equals(body.cascadeOwner())
                && body.ownerId() != null && !body.ownerId().equals(oldOwner);
        boolean cascadeSprint = Boolean.TRUE.equals(body.cascadeSprint())
                && body.sprint() != null && !body.sprint().equals(oldSprint);
        if (cascadeOwner || cascadeSprint) {
            int shift = cascadeSprint ? (body.sprint() - oldSprint) * 2 : 0;
            for (Task task : taskMapper.selectList(new QueryWrapper<Task>()
                    .eq("project_id", scopedProjectId).eq("kanban_card_id", storyId)
                    .eq("task_type", "feature"))) {
                if (task.getStatus() != null && (task.getStatus() == 2 || task.getStatus() == 3)) continue;
                if (cascadeOwner) task.setOwnerId(body.ownerId());
                if (cascadeSprint) {
                    int start = Math.min(6, Math.max(1, task.getWeekStart() + shift));
                    int end = Math.min(6, Math.max(start, task.getWeekEnd() + shift));
                    task.setWeekStart(start);
                    task.setWeekEnd(end);
                }
                taskMapper.updateById(task);
            }
        }
        storyMapper.updateById(story);
        if (body.epicId() != null && body.epicId().isNull()) {
            // MyBatis-Plus skips null entity fields by default; clearing needs explicit SQL NULL.
            storyMapper.update(null, new UpdateWrapper<Story>().eq("project_id", scopedProjectId)
                    .eq("internal_id", story.getInternalId()).set("epic_id", null));
        }
        if (body.status() != null && oldStatus != body.status()) {
            addLog(scopedProjectId, storyId, "move", story.getTitle() + " → 状态 " + story.getStatus(), user.getId());
        } else {
            addLog(scopedProjectId, storyId, "edit", story.getTitle(), user.getId());
        }
        return StoryDtos.toOut(story);
    }

    /** DELETE /api/stories/{id}?undone=cancel|keep|detach (admin/owner) */
    @DeleteMapping("/{storyId}")
    @Transactional
    public StoryDtos.DeleteOut delete(@PathVariable String storyId,
                                      @RequestParam(defaultValue = "keep") String undone,
                                      @RequestParam String projectId) {
        User user = Roles.reviewer();
        projectAccess.requireOwner(projectId);
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        Story story = storyMapper.selectOne(new QueryWrapper<Story>().eq("project_id", scopedProjectId).eq("code", storyId));
        if (story == null) {
            throw ApiException.notFound("故事不存在");
        }
        if (!undone.equals("cancel") && !undone.equals("keep") && !undone.equals("detach")) {
            throw ApiException.badRequest("undone 策略仅支持 cancel/keep/detach");
        }
        List<Task> subs = taskMapper.selectList(
                new QueryWrapper<Task>().eq("project_id", scopedProjectId).eq("kanban_card_id", storyId));
        List<Task> undoneRows = subs.stream()
                .filter(t -> t.getStatus() == null || (t.getStatus() != 2 && t.getStatus() != 3))
                .toList();
        for (Task t : undoneRows) {
            if (undone.equals("cancel")) {
                t.setStatus(3);
            }
            t.setKanbanCardId(null);
            if (undone.equals("detach")) {
                t.setWeekStart(Math.min(t.getWeekStart() + 2, 6));
                t.setWeekEnd(Math.min(t.getWeekEnd() + 2, 6));
            }
            taskMapper.updateById(t);
        }
        taskStoryRelations.removeStory(scopedProjectId, story.getInternalId());
        storyMapper.delete(new QueryWrapper<Story>().eq("project_id", scopedProjectId).eq("code", storyId));
        String detail = story.getTitle() + " · 子任务级联:" + undone + "(" + undoneRows.size() + " 条)";
        addLog(scopedProjectId, storyId, "del", detail, user.getId());
        return new StoryDtos.DeleteOut(true, undone, undoneRows.size());
    }
}
