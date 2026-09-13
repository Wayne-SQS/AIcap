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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 任务 + 需求池接口(对齐 FastAPI routers/tasks.py + pool.py)。
 * tasks:PATCH 需区分"未提供"与"显式 null"(kanban_card_id=null 即解绑)。
 * pool:CRUD + promote(条目 → 看板卡,带 sprint/owner 选择)。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskPoolController {

    private static final Pattern US_ID = Pattern.compile("^US(\\d+)$");
    private static final Pattern R_ID = Pattern.compile("^R(\\d+)$");

    private final TaskMapper taskMapper;
    private final StoryMapper storyMapper;
    private final StoryLogMapper storyLogMapper;
    private final PoolItemMapper poolItemMapper;
    private final UserMapper userMapper;

    // ---------- 工具 ----------

    /** 新看板卡 ID:沿用 US 命名空间(对齐 FastAPI pool.py 的 _next_story_id:US38 起) */
    private String nextStoryId() {
        int max = 0;
        for (Story s : storyMapper.selectList(null)) {
            Matcher m = US_ID.matcher(s.getId());
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("US%02d", max + 1);
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

    /**
     * 关联编号解析(对齐 FastAPI tasks.py `_parse_refs`):
     * 空值→空表;按逗号切分、去空白、转大写;先查重、再校验 `<前缀>xx` 格式。
     */
    private List<String> parseRefs(String value, String prefix) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                refs.add(trimmed.toUpperCase(Locale.ROOT));
            }
        }
        if (new HashSet<>(refs).size() != refs.size()) {
            throw ApiException.badRequest("关联编号不能重复");
        }
        Pattern pattern = Pattern.compile(prefix + "\\d+");
        for (String ref : refs) {
            if (!pattern.matcher(ref).matches()) {
                throw ApiException.badRequest("关联编号必须使用 " + prefix + "xx 格式，并以逗号分隔");
            }
        }
        return refs;
    }

    /** 依赖环检测(对齐 FastAPI tasks.py `_has_dependency_cycle`):以 taskId 为终点做可达性 DFS */
    private boolean hasDependencyCycle(String taskId, List<String> dependencies) {
        Map<String, List<String>> dependencyMap = new HashMap<>();
        for (Task t : taskMapper.selectList(null)) {
            dependencyMap.put(t.getId(), parseRefs(t.getDependsOn(), "T"));
        }
        dependencyMap.put(taskId, dependencies);
        return dependencies.stream()
                .anyMatch(dep -> reaches(dep, taskId, dependencyMap, new HashSet<>()));
    }

    private boolean reaches(String current, String target,
                            Map<String, List<String>> dependencyMap, Set<String> visited) {
        if (current.equals(target)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (String next : dependencyMap.getOrDefault(current, List.of())) {
            if (reaches(next, target, dependencyMap, visited)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 字段级校验(对齐 pydantic TaskPatch 约束,先于业务校验) ----------

    private NullableField<Integer> intField(JsonNode body, String name, int min, int max) {
        JsonNode v = body.get(name);
        if (v == null || v.isNull()) {
            return NullableField.absent();
        }
        if (!v.isIntegralNumber()) {
            throw ApiException.unprocessable("字段 " + name + ": 必须为整数");
        }
        int n = v.asInt();
        if (n < min || n > max) {
            throw ApiException.unprocessable("字段 " + name + ": 必须在 " + min + ".." + max + " 之间");
        }
        return NullableField.of(n);
    }

    private NullableField<String> textField(JsonNode body, String name, Integer maxLen, boolean nonEmpty) {
        JsonNode v = body.get(name);
        if (v == null || v.isNull()) {
            return NullableField.absent();
        }
        if (!v.isTextual()) {
            throw ApiException.unprocessable("字段 " + name + ": 必须为字符串");
        }
        String s = v.asText();
        if (nonEmpty && s.isEmpty()) {
            throw ApiException.unprocessable("字段 " + name + ": 不能为空");
        }
        if (maxLen != null && s.length() > maxLen) {
            throw ApiException.unprocessable("字段 " + name + ": 长度不能超过 " + maxLen);
        }
        return NullableField.of(s);
    }

    /** 布尔字段:接受 true/false,兼容 pydantic 宽松模式下的 0/1 */
    private NullableField<Boolean> boolField(JsonNode body, String name) {
        JsonNode v = body.get(name);
        if (v == null || v.isNull()) {
            return NullableField.absent();
        }
        if (v.isBoolean()) {
            return NullableField.of(v.asBoolean());
        }
        if (v.isIntegralNumber() && (v.asInt() == 0 || v.asInt() == 1)) {
            return NullableField.of(v.asInt() == 1);
        }
        throw ApiException.unprocessable("字段 " + name + ": 必须为布尔值");
    }

    /** 区分"未提供"与"提供了值"的小包装(null 视为未提供,与 FastAPI 的 Optional 语义一致) */
    private record NullableField<T>(boolean present, T value) {
        static <T> NullableField<T> absent() {
            return new NullableField<>(false, null);
        }

        static <T> NullableField<T> of(T value) {
            return new NullableField<>(true, value);
        }
    }

    // ---------- tasks ----------

    @GetMapping("/tasks")
    public List<TaskDtos.TaskOut> listTasks() {
        Roles.any();
        return TaskDtos.toOutList(taskMapper.selectList(new QueryWrapper<Task>().orderByAsc("id")));
    }

    /**
     * PATCH /api/tasks/{id}:看板/甘特/成员/需求四视图共用的任务记录更新
     * (对齐 FastAPI routers/tasks.py patch_task)。
     *
     * <p>校验顺序与 FastAPI 一致:字段级(422)→ 负责人存在 → 看板卡存在 → 周序
     * → story_ref(USxx)→ depends_on(Txx、自依赖、存在性、依赖环)→ status/progress 联动。
     * 接收 JsonNode 以区分"未提供"与"显式 null"(kanban_card_id=null 即解绑)。
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

        // ---- 1) 字段级约束(对应 pydantic TaskPatch) ----
        NullableField<String> name = textField(body, "name", 200, true);
        NullableField<Integer> ownerId = intField(body, "owner_id", Integer.MIN_VALUE, Integer.MAX_VALUE);
        NullableField<Integer> hours = intField(body, "hours", 0, 999);
        NullableField<Integer> weekStart = intField(body, "week_start", 1, 6);
        NullableField<Integer> weekEnd = intField(body, "week_end", 1, 6);
        NullableField<String> storyRef = textField(body, "story_ref", 100, false);
        NullableField<Integer> estimatedHours = intField(body, "estimated_hours", 0, 999);
        NullableField<String> taskType = textField(body, "task_type", null, false);
        if (taskType.present() && !"feature".equals(taskType.value()) && !"management".equals(taskType.value())) {
            throw ApiException.unprocessable("字段 task_type: 仅支持 feature/management");
        }
        NullableField<String> dependsOn = textField(body, "depends_on", 100, false);
        NullableField<Integer> status = intField(body, "status", 0, 3);
        NullableField<Integer> progress = intField(body, "progress", 0, 100);
        NullableField<Boolean> blocked = boolField(body, "blocked");
        boolean hasKanban = body.has("kanban_card_id");

        // ---- 2) 业务校验 ----
        if (ownerId.present() && userMapper.selectById(ownerId.value()) == null) {
            throw ApiException.badRequest("负责人不存在");
        }

        String kanban = null;
        if (hasKanban && !body.get("kanban_card_id").isNull()) {
            kanban = body.get("kanban_card_id").asText();
            if (storyMapper.selectById(kanban) == null) {
                throw ApiException.badRequest("所属看板卡不存在");
            }
        }

        int ws = weekStart.present() ? weekStart.value() : task.getWeekStart();
        int we = weekEnd.present() ? weekEnd.value() : task.getWeekEnd();
        if (ws > we) {
            throw ApiException.badRequest("开始周不能晚于结束周");
        }

        String normalizedStoryRef = null;
        if (body.has("story_ref")) {
            List<String> refs = parseRefs(storyRef.value(), "US");
            Set<String> storyIds = storyMapper.selectList(null).stream()
                    .map(Story::getId).collect(Collectors.toSet());
            List<String> missing = refs.stream().filter(r -> !storyIds.contains(r)).toList();
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("关联故事不存在：" + String.join(", ", missing));
            }
            normalizedStoryRef = String.join(",", refs);
        }

        String normalizedDependsOn = null;
        if (body.has("depends_on")) {
            List<String> deps = parseRefs(dependsOn.value(), "T");
            if (deps.contains(taskId)) {
                throw ApiException.badRequest("任务不能依赖自身");
            }
            Set<String> taskIds = taskMapper.selectList(null).stream()
                    .map(Task::getId).collect(Collectors.toSet());
            List<String> missing = deps.stream().filter(r -> !taskIds.contains(r)).toList();
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("前置任务不存在：" + String.join(", ", missing));
            }
            if (hasDependencyCycle(taskId, deps)) {
                throw ApiException.badRequest("任务依赖不能形成循环");
            }
            normalizedDependsOn = String.join(",", deps);
        }

        // ---- 3) status/progress 联动(单给其一时推导另一个;status=3 保留原进度) ----
        Integer finalStatus = status.present() ? status.value() : null;
        Integer finalProgress = progress.present() ? progress.value() : null;
        if (finalStatus != null && finalProgress == null) {
            finalProgress = switch (finalStatus) {
                case 0 -> 0;
                case 1 -> 50;
                case 2 -> 100;
                default -> task.getProgress() == null ? 0 : task.getProgress();
            };
        } else if (finalProgress != null && finalStatus == null) {
            finalStatus = finalProgress >= 100 ? 2 : (finalProgress > 0 ? 1 : 0);
        }

        // ---- 4) 落库 ----
        boolean changed = false;
        if (name.present()) {
            task.setName(name.value());
            changed = true;
        }
        if (ownerId.present()) {
            task.setOwnerId(ownerId.value());
            changed = true;
        }
        if (hours.present()) {
            task.setHours(hours.value());
            changed = true;
        }
        if (weekStart.present() || weekEnd.present()) {
            task.setWeekStart(ws);
            task.setWeekEnd(we);
            changed = true;
        }
        if (body.has("story_ref")) {
            task.setStoryRef(normalizedStoryRef);
            changed = true;
        }
        if (hasKanban) {
            task.setKanbanCardId(kanban);
            changed = true;
        }
        if (estimatedHours.present()) {
            task.setEstimatedHours(estimatedHours.value());
            changed = true;
        }
        if (taskType.present()) {
            task.setTaskType(taskType.value());
            changed = true;
        }
        if (body.has("depends_on")) {
            task.setDependsOn(normalizedDependsOn);
            changed = true;
        }
        if (finalStatus != null) {
            task.setStatus(finalStatus);
            task.setProgress(finalProgress);
            changed = true;
        }
        if (blocked.present()) {
            task.setBlocked(Boolean.TRUE.equals(blocked.value()) ? 1 : 0);
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
