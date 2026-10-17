package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic XLSX/CSV adapter into the shared draft format. */
@Service
@RequiredArgsConstructor
public class ProjectGenerationImportService {
    private final ObjectMapper mapper;
    private final UserMapper users;

    public ObjectNode parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("导入文件不能为空");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv") && !name.endsWith(".xlsx")) throw ApiException.badRequest("仅支持 .xlsx 和 .csv");
        try { return name.endsWith(".xlsx") ? xlsx(file) : csv(file); }
        catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.badRequest("无法解析导入文件，请检查表头和格式"); }
    }

    private ObjectNode csv(MultipartFile file) throws Exception {
        DraftRows draft = new DraftRows(mapper);
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true).build().parse(reader)) {
            for (CSVRecord row : parser) {
                Map<String, String> values = new HashMap<>();
                row.toMap().forEach((key, value) -> values.put(normalize(key), value));
                addRow(values, draft);
            }
        }
        return root(draft);
    }

    private ObjectNode xlsx(MultipartFile file) throws Exception {
        DraftRows draft = new DraftRows(mapper);
        DataFormatter formatter = new DataFormatter();
        try (var workbook = WorkbookFactory.create(file.getInputStream())) {
            for (var sheet : workbook) {
                Row header = sheet.getRow(0);
                if (header == null) continue;
                Map<Integer, String> headers = new HashMap<>();
                for (int column = 0; column < header.getLastCellNum(); column++) {
                    headers.put(column, normalize(formatter.formatCellValue(header.getCell(column))));
                }
                for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                    Row row = sheet.getRow(index);
                    if (row == null) continue;
                    Map<String, String> values = new HashMap<>();
                    headers.forEach((column, name) -> values.put(name, formatter.formatCellValue(row.getCell(column))));
                    addRow(values, draft);
                }
            }
        }
        return root(draft);
    }

    private void addRow(Map<String, String> values, DraftRows draft) {
        String epic = first(values, "epic", "module", "epicmodule", "模块", "史诗");
        int epicActivity = 0;
        if (!epic.isBlank()) {
            epicActivity = draft.epicActivities.computeIfAbsent(epic, ignored -> {
                int activity = Math.min(5, draft.epicActivities.size() + 1);
                draft.epics.add(mapper.createObjectNode().put("id", "E" + activity).put("title", epic).put("activity", activity));
                return activity;
            });
        }
        String milestoneId = first(values, "milestoneid", "milestonecode", "里程碑编号", "里程碑代码");
        String milestoneName = first(values, "milestonename", "milestonetitle", "里程碑名称", "里程碑标题");
        if (!milestoneId.isBlank() || !milestoneName.isBlank()) {
            ObjectNode milestone = mapper.createObjectNode();
            milestone.put("id", blankDefault(milestoneId, "M" + (draft.milestones.size() + 1)));
            milestone.put("name", blankDefault(milestoneName, "未命名里程碑"));
            milestone.put("week", number(first(values, "week", "weekdate", "周次", "周"), 1));
            milestone.put("description", first(values, "description", "描述", "里程碑描述"));
            milestone.put("related_task_ids", first(values, "relatedtaskids", "relatedtaskcodes", "关联任务", "关联任务编号"));
            milestone.put("status", blankDefault(first(values, "status", "状态"), "planned"));
            draft.milestones.add(milestone);
            return;
        }
        String storyId = first(values, "storyid", "故事id", "故事编号");
        String storyName = first(values, "storyname", "故事名称", "用户故事", "需求名称");
        String taskId = first(values, "taskid", "任务id", "任务编号");
        String taskName = first(values, "taskname", "任务名称");
        String genericId = first(values, "id", "编号");
        String genericName = first(values, "title", "name", "名称");
        String hours = first(values, "estimatedhours", "hours", "预估工时", "计划工时", "工时");
        if (storyName.isBlank() && taskName.isBlank()) {
            if (hours.isBlank()) { storyId = blankDefault(storyId, genericId); storyName = genericName; }
            else { taskId = blankDefault(taskId, genericId); taskName = genericName; }
        }

        if (!storyName.isBlank() || !storyId.isBlank()) {
            String sourceId = blankDefault(storyId, "story-row-" + (draft.stories.size() + 1));
            if (draft.storyKeys.add(sourceId.toUpperCase(Locale.ROOT))) {
                ObjectNode story = mapper.createObjectNode();
                String[] storyParts = splitCodeTitle(sourceId, storyName, "US");
                sourceId = storyParts[0];
                story.put("id", sourceId);
                story.put("title", blankDefault(storyParts[1], "未命名故事"));
                story.put("description", first(values, "description", "描述", "需求描述"));
                story.put("acceptance", first(values, "acceptancecriteria", "acceptance", "验收标准", "验收条件"));
                String priority = first(values, "priority", "优先级");
                if (!priority.isBlank()) {
                    story.put("priority", priority);
                    story.put("_import_priority_explicit", true);
                } else {
                    story.put("priority", "");
                    story.put("_import_priority_explicit", false);
                }
                String sprint = first(values, "sprint", "迭代");
                if (sprint.isBlank()) {
                    story.put("_import_sprint_explicit", false);
                } else {
                    story.put("sprint", sprintNumber(sprint, sourceId));
                    story.put("_import_sprint_explicit", true);
                }
                String activityValue = first(values, "activity", "活动", "功能分组");
                story.put("activity", epicActivity > 0 ? epicActivity : number(activityValue, 1));
                story.put("_import_activity_explicit", epicActivity > 0 || !activityValue.isBlank());
                if (!epic.isBlank()) story.put("epic", epic);
                Integer owner = resolveOwnerId(values);
                if (owner != null) {
                    story.put("owner_id", owner);
                    story.put("_import_owner_explicit", true);
                }
                draft.stories.add(story);
            }
        }

        if (!taskName.isBlank() || !taskId.isBlank() || !hours.isBlank()) {
            ObjectNode task = mapper.createObjectNode();
            String rawTaskId = blankDefault(taskId, blankDefault(genericId, "task-row-" + (draft.tasks.size() + 1)));
            String[] taskParts = splitCodeTitle(rawTaskId, taskName.isBlank() ? genericName : taskName, "T");
            task.put("id", taskParts[0]);
            task.put("name", blankDefault(taskParts[1], "未命名任务"));
            Integer owner = resolveOwnerId(values);
            if (owner != null) {
                task.put("owner_id", owner);
                task.put("_import_owner_explicit", true);
            }
            task.put("hours", number(hours, 1));
            String startWeek = first(values, "startweek", "weekstart", "开始周");
            String endWeek = first(values, "endweek", "weekend", "结束周");
            task.put("week_start", number(startWeek, 1));
            task.put("week_end", number(endWeek, task.path("week_start").asInt()));
            task.put("_import_schedule_explicit", !startWeek.isBlank() || !endWeek.isBlank());
            task.put("_import_hours_explicit", !hours.isBlank());
            task.put("story_ref", blankDefault(first(values, "storyref", "关联故事", "故事引用"), storyId));
            String dependency = first(values, "dependency", "dependson", "依赖", "前置任务");
            task.put("depends_on", dependency);
            task.put("_import_dependencies_explicit", !dependency.isBlank());
            draft.tasks.add(task);
        }

    }

    private Integer resolveOwnerId(Map<String, String> values) {
        String rawId = first(values, "ownerid", "负责人编号", "成员编号");
        String name = first(values, "ownername", "owner", "member", "负责人姓名", "负责人", "成员");
        if (!rawId.isBlank()) {
            int id = strictNumber(rawId, "负责人编号");
            User user = users.selectById(id);
            if (user == null) throw ApiException.badRequest("负责人不存在：" + rawId.trim());
            if (!name.isBlank()) {
                User named = resolveOwnerName(name);
                if (named.getId() != id) {
                    throw ApiException.badRequest("负责人编号与姓名不一致：" + rawId.trim() + " / " + name.trim());
                }
            }
            return id;
        }
        if (name.isBlank()) return null;
        return resolveOwnerName(name).getId();
    }

    private String[] splitCodeTitle(String rawId, String rawTitle, String prefix) {
        String id = rawId == null ? "" : rawId.trim().toUpperCase(Locale.ROOT);
        String title = rawTitle == null ? "" : rawTitle.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(" + prefix + "\\d+)\\s*(?:[-:：、.]\\s*|\\s+)(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(title);
        if (matcher.matches()) {
            if (id.isBlank() || id.startsWith(prefix)) id = matcher.group(1).toUpperCase(Locale.ROOT);
            title = matcher.group(2).trim();
        }
        return new String[]{id, title};
    }

    private User resolveOwnerName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        var matches = users.selectList(new QueryWrapper<User>().eq("display_name", name));
        if (matches.isEmpty()) {
            throw ApiException.badRequest("负责人不存在：" + name);
        }
        if (matches.size() > 1) {
            throw ApiException.badRequest("存在多个同名成员，请使用owner_id：" + name);
        }
        return matches.get(0);
    }

    private ObjectNode root(DraftRows draft) {
        ObjectNode root = mapper.createObjectNode();
        root.set("stories", draft.stories);
        root.set("tasks", draft.tasks);
        ArrayNode activities = mapper.createArrayNode();
        for (JsonNode epic : draft.epics) {
            int activity = epic.path("activity").asInt(activities.size() + 1);
            activities.add(mapper.createObjectNode().put("id", activity).put("name", epic.path("title").asText(epic.path("name").asText(""))));
        }
        root.set("activities", activities);
        root.set("epics", draft.epics);
        root.set("milestones", draft.milestones);
        root.set("project", mapper.createObjectNode());
        return root;
    }

    private String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(normalize(key));
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
    private String normalize(String value) { return String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "").replace("/", ""); }
    private String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private int number(String value, int fallback) { try { return value == null || value.isBlank() ? fallback : Integer.parseInt(value.replaceAll("[^0-9-]", "")); } catch (Exception e) { return fallback; } }
    private int strictNumber(String value, String field) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception e) { throw ApiException.badRequest(field + "不合法：" + value.trim()); }
    }
    private int sprintNumber(String value, String storyId) {
        String normalized = value == null ? "" : value.trim().replaceFirst("(?i)^sprint\\s*", "").trim();
        if (!normalized.matches("[1-9]\\d*")) {
            throw ApiException.badRequest(storyId + " 的 Sprint 不合法：" + value.trim());
        }
        try { return Integer.parseInt(normalized); }
        catch (NumberFormatException e) { throw ApiException.badRequest(storyId + " 的 Sprint 不合法：" + value.trim()); }
    }

    private static final class DraftRows {
        private final ArrayNode stories;
        private final ArrayNode tasks;
        private final ArrayNode epics;
        private final ArrayNode milestones;
        private final Set<String> storyKeys = new HashSet<>();
        private final Map<String, Integer> epicActivities = new java.util.LinkedHashMap<>();
        private DraftRows(ObjectMapper mapper) {
            stories = mapper.createArrayNode();
            tasks = mapper.createArrayNode();
            epics = mapper.createArrayNode();
            milestones = mapper.createArrayNode();
        }
    }
}
