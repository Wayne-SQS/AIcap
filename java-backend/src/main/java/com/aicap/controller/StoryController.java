package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.StoryDtos;
import com.aicap.entity.Story;
import com.aicap.entity.StoryLog;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.StoryLogMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.security.Roles;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户故事/看板卡接口(对齐 FastAPI routers/stories.py)。
 * 删除卡时按 undone 策略级联处理未完成子任务(cancel/keep/detach)。
 */
@RestController
@RequestMapping("/api/stories")
@RequiredArgsConstructor
public class StoryController {

    private static final Pattern M_ID = Pattern.compile("^M(\\d+)$");

    private final StoryMapper storyMapper;
    private final StoryLogMapper storyLogMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;

    // ---------- 工具 ----------

    private String nextStoryId() {
        int max = 0;
        for (Story s : storyMapper.selectList(null)) {
            Matcher m = M_ID.matcher(s.getId());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return String.format("M%02d", max + 1);
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

    private void checkOwner(Integer ownerId) {
        if (ownerId != null && userMapper.selectById(ownerId) == null) {
            throw ApiException.badRequest("负责人不存在");
        }
    }

    // ---------- 接口 ----------

    /** GET /api/stories/logs 最近 50 条(对齐 recent_logs) */
    @GetMapping("/logs")
    public List<StoryDtos.LogOut> recentLogs() {
        Roles.any();
        List<StoryLog> logs = storyLogMapper.selectList(new QueryWrapper<StoryLog>().orderByDesc("id"));
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
                                         @RequestParam(required = false) String q) {
        Roles.any();
        QueryWrapper<Story> qw = new QueryWrapper<>();
        if (owner != null) qw.eq("owner_id", owner);
        if (sprint != null) qw.eq("sprint", sprint);
        if (status != null) qw.eq("status", status);
        if (activity != null) qw.eq("activity", activity);
        if (StringUtils.hasText(priority)) qw.eq("priority", priority);
        if (StringUtils.hasText(q)) {
            String like = "%" + q + "%";
            qw.and(w -> w.like("id", like).or().like("title", like));
        }
        qw.orderByAsc("id");
        return StoryDtos.toOutList(storyMapper.selectList(qw));
    }

    /** POST /api/stories 新建(admin/owner/member) */
    @PostMapping("")
    @Transactional
    public StoryDtos.StoryOut create(@Valid @RequestBody StoryDtos.StoryIn body) {
        User user = Roles.writer();
        checkOwner(body.ownerId());
        String sid = nextStoryId();
        Story story = new Story();
        story.setId(sid);
        story.setTitle(body.title().trim());
        story.setDescription(body.description() == null ? "" : body.description());
        story.setAcceptance(body.acceptance() == null ? "" : body.acceptance());
        story.setPriority(body.priority());
        story.setSprint(body.sprint());
        story.setActivity(body.activity());
        story.setStatus(body.status());
        story.setOwnerId(body.ownerId());
        story.setCreatedAt(LocalDateTime.now());
        storyMapper.insert(story);
        addLog(sid, "create", story.getTitle(), user.getId());
        return StoryDtos.toOut(story);
    }

    /** PATCH /api/stories/{id} 局部更新(admin/owner/member) */
    @PatchMapping("/{storyId}")
    @Transactional
    public StoryDtos.StoryOut patch(@PathVariable String storyId,
                                    @Valid @RequestBody StoryDtos.StoryPatch body) {
        User user = Roles.writer();
        Story story = storyMapper.selectById(storyId);
        if (story == null) {
            throw ApiException.notFound("故事不存在");
        }
        Integer oldStatus = story.getStatus();
        boolean changed = false;
        if (body.ownerId() != null && !body.ownerId().equals(story.getOwnerId())) {
            checkOwner(body.ownerId());
            story.setOwnerId(body.ownerId());
            changed = true;
        }
        if (body.title() != null) { story.setTitle(body.title().trim()); changed = true; }
        if (body.description() != null) { story.setDescription(body.description()); changed = true; }
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
        storyMapper.updateById(story);
        if (body.status() != null && oldStatus != body.status()) {
            addLog(storyId, "move", story.getTitle() + " → 状态 " + story.getStatus(), user.getId());
        } else {
            addLog(storyId, "edit", story.getTitle(), user.getId());
        }
        return StoryDtos.toOut(story);
    }

    /** DELETE /api/stories/{id}?undone=cancel|keep|detach (admin/owner) */
    @DeleteMapping("/{storyId}")
    @Transactional
    public StoryDtos.DeleteOut delete(@PathVariable String storyId,
                                      @RequestParam(defaultValue = "keep") String undone) {
        User user = Roles.reviewer();
        Story story = storyMapper.selectById(storyId);
        if (story == null) {
            throw ApiException.notFound("故事不存在");
        }
        if (!undone.equals("cancel") && !undone.equals("keep") && !undone.equals("detach")) {
            throw ApiException.badRequest("undone 策略仅支持 cancel/keep/detach");
        }
        List<Task> subs = taskMapper.selectList(
                new QueryWrapper<Task>().eq("kanban_card_id", storyId));
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
        storyMapper.deleteById(storyId);
        String detail = story.getTitle() + " · 子任务级联:" + undone + "(" + undoneRows.size() + " 条)";
        addLog(storyId, "del", detail, user.getId());
        return new StoryDtos.DeleteOut(true, undone, undoneRows.size());
    }
}
