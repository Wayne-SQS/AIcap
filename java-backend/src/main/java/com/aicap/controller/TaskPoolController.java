package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.PoolDtos;
import com.aicap.dto.StoryDtos;
import com.aicap.dto.TaskDtos;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Story;
import com.aicap.entity.StoryLog;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.PoolItemMapper;
import com.aicap.mapper.StoryLogMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.security.Roles;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务 + 需求池接口(对齐 FastAPI routers/tasks.py + pool.py)。
 * tasks:PATCH 需区分"未提供"与"显式 null"(kanban_card_id=null 即解绑)。
 * pool:CRUD + promote(条目 → 看板卡,带 sprint/owner 选择)。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskPoolController {

    private static final Pattern M_ID = Pattern.compile("^M(\\d+)$");
    private static final Pattern R_ID = Pattern.compile("^R(\\d+)$");

    private final TaskMapper taskMapper;
    private final StoryMapper storyMapper;
    private final StoryLogMapper storyLogMapper;
    private final PoolItemMapper poolItemMapper;
    private final UserMapper userMapper;

    // ---------- 工具 ----------

    private String nextStoryId() {
        int max = 0;
        for (Story s : storyMapper.selectList(null)) {
            Matcher m = M_ID.matcher(s.getId());
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("M%02d", max + 1);
    }

    private String nextPoolId() {
        int max = 0;
        for (PoolItem p : poolItemMapper.selectList(null)) {
            Matcher m = R_ID.matcher(p.getId());
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("R%02d", max + 1);
    }

    @Transactional
    void addLog(String storyId, String logType, String detail, Integer userId) {
        StoryLog log = new StoryLog();
        log.setStoryId(storyId);
        log.setLogType(logType);
        log.setDetail(detail);
        log.setUserId(userId);
        log.setCreatedAt(LocalDateTime.now());
        storyLogMapper.insert(log);
    }

    // ---------- tasks ----------

    @GetMapping("/tasks")
    public List<TaskDtos.TaskOut> listTasks() {
        Roles.any();
        return TaskDtos.toOutList(taskMapper.selectList(new QueryWrapper<Task>().orderByAsc("id")));
    }

    /**
     * PATCH /api/tasks/{id}:status/kanban_card_id/week_start/week_end。
     * 接收 JsonNode 区分"未提供"与"显式 null"(kanban_card_id=null 解绑)。
     */
    @PatchMapping("/tasks/{taskId}")
    @Transactional
    public TaskDtos.TaskOut patchTask(@PathVariable String taskId,
                                      @RequestBody JsonNode body) {
        Roles.writer();
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw ApiException.notFound("任务不存在");
        }
        boolean changed = false;

        if (body.hasNonNull("status")) {
            int s = body.get("status").asInt();
            if (s < 0 || s > 3) throw ApiException.unprocessable("status 必须在 0..3");
            task.setStatus(s);
            changed = true;
        }
        if (body.has("kanban_card_id")) {
            JsonNode v = body.get("kanban_card_id");
            if (v.isNull()) {
                task.setKanbanCardId(null);
            } else {
                String cardId = v.asText();
                if (storyMapper.selectById(cardId) == null) {
                    throw ApiException.badRequest("所属看板卡不存在");
                }
                task.setKanbanCardId(cardId);
            }
            changed = true;
        }
        if (body.has("week_start") || body.has("week_end")) {
            int ws = task.getWeekStart();
            int we = task.getWeekEnd();
            if (body.hasNonNull("week_start")) {
                ws = body.get("week_start").asInt();
                if (ws < 1 || ws > 6) throw ApiException.unprocessable("week_start 必须在 1..6");
            }
            if (body.hasNonNull("week_end")) {
                we = body.get("week_end").asInt();
                if (we < 1 || we > 6) throw ApiException.unprocessable("week_end 必须在 1..6");
            }
            if (we < ws) {
                throw ApiException.badRequest("结束周不能早于开始周");
            }
            task.setWeekStart(ws);
            task.setWeekEnd(we);
            changed = true;
        }
        if (!changed) {
            return TaskDtos.toOut(task);
        }
        taskMapper.updateById(task);
        return TaskDtos.toOut(task);
    }

    // ---------- pool ----------

    @GetMapping("/pool")
    public List<PoolDtos.PoolOut> listPool() {
        Roles.any();
        return PoolDtos.toOutList(poolItemMapper.selectList(new QueryWrapper<PoolItem>().orderByAsc("id")));
    }

    @PostMapping("/pool")
    @Transactional
    public PoolDtos.PoolOut createPool(@Valid @RequestBody PoolDtos.PoolIn body) {
        Roles.writer();
        PoolItem item = new PoolItem();
        item.setId(nextPoolId());
        item.setTitle(body.title() == null ? "" : body.title());
        item.setDescription(body.description());
        item.setSource(body.source());
        item.setPriority(body.priority());
        item.setCreatedAt(LocalDateTime.now());
        poolItemMapper.insert(item);
        return PoolDtos.toOut(item);
    }

    @DeleteMapping("/pool/{poolId}")
    public PoolDtos.DeleteOut deletePool(@PathVariable String poolId) {
        Roles.writer();
        PoolItem item = poolItemMapper.selectById(poolId);
        if (item == null) {
            throw ApiException.notFound("需求池条目不存在");
        }
        poolItemMapper.deleteById(poolId);
        return new PoolDtos.DeleteOut(true);
    }

    /** promote:需求池 → 看板卡(sprint 必填,owner 可选;写 create 日志) */
    @PostMapping("/pool/{poolId}/promote")
    @Transactional
    public StoryDtos.StoryOut promote(@PathVariable String poolId,
                                      @Valid @RequestBody PoolDtos.PoolPromoteIn body) {
        User user = Roles.writer();
        PoolItem item = poolItemMapper.selectById(poolId);
        if (item == null) {
            throw ApiException.notFound("需求池条目不存在");
        }
        if (body.ownerId() != null && userMapper.selectById(body.ownerId()) == null) {
            throw ApiException.unprocessable("所选负责人不存在");
        }
        String sid = nextStoryId();
        Story story = new Story();
        story.setId(sid);
        story.setTitle(item.getTitle());
        story.setDescription(item.getDescription() == null || item.getDescription().isBlank()
                ? "（待补充描述）" : item.getDescription());
        story.setAcceptance("来源：" + (item.getSource() == null || item.getSource().isBlank()
                ? "需求池" : item.getSource()) + "（待补充验收条件）");
        story.setPriority(item.getPriority());
        story.setSprint(body.sprint());
        story.setActivity(body.activity());
        story.setStatus(0);
        story.setOwnerId(body.ownerId());
        story.setCreatedAt(LocalDateTime.now());
        storyMapper.insert(story);
        poolItemMapper.deleteById(poolId);
        String detail = "由需求池 " + poolId + " 移入 Sprint " + body.sprint() + "，负责人 "
                + (body.ownerId() == null ? "未分配" : body.ownerId());
        addLog(sid, "create", detail, user.getId());
        return StoryDtos.toOut(story);
    }
}
