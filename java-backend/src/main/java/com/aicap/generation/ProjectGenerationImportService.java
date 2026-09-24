package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                story.put("id", sourceId);
                story.put("title", blankDefault(storyName, "未命名故事"));
                story.put("description", first(values, "description", "描述", "需求描述"));
                story.put("acceptance", first(values, "acceptancecriteria", "acceptance", "验收标准", "验收条件"));
                story.put("priority", blankDefault(first(values, "priority", "优先级"), "Should"));
                story.put("sprint", number(first(values, "sprint", "迭代"), 1));
                story.put("activity", number(first(values, "activity", "活动", "功能分组"), 1));
                Integer owner = resolveOwnerId(values);
                if (owner != null) story.put("owner_id", owner);
                draft.stories.add(story);
            }
        }

        if (!taskName.isBlank() || !taskId.isBlank() || !hours.isBlank()) {
            ObjectNode task = mapper.createObjectNode();
            task.put("id", blankDefault(taskId, blankDefault(genericId, "task-row-" + (draft.tasks.size() + 1))));
            task.put("name", blankDefault(taskName, blankDefault(genericName, "未命名任务")));
            Integer owner = resolveOwnerId(values);
            if (owner != null) task.put("owner_id", owner);
            task.put("hours", number(hours, 1));
            task.put("week_start", number(first(values, "startweek", "weekstart", "开始周"), 1));
            task.put("week_end", number(first(values, "endweek", "weekend", "结束周"), task.path("week_start").asInt()));
            task.put("story_ref", blankDefault(first(values, "storyref", "关联故事", "故事引用"), storyId));
            task.put("depends_on", first(values, "dependency", "dependson", "依赖", "前置任务"));
            draft.tasks.add(task);
        }

        String epic = first(values, "epic", "module", "epicmodule", "模块", "史诗");
        if (!epic.isBlank() && draft.epicKeys.add(epic)) draft.epics.add(mapper.createObjectNode().put("name", epic));
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
        root.set("activities", mapper.createArrayNode());
        root.set("epics", draft.epics);
        root.set("milestones", mapper.createArrayNode());
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

    private static final class DraftRows {
        private final ArrayNode stories;
        private final ArrayNode tasks;
        private final ArrayNode epics;
        private final Set<String> storyKeys = new HashSet<>();
        private final Set<String> epicKeys = new HashSet<>();
        private DraftRows(ObjectMapper mapper) { stories = mapper.createArrayNode(); tasks = mapper.createArrayNode(); epics = mapper.createArrayNode(); }
    }
}
