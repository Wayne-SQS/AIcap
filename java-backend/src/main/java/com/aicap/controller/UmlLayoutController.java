package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UML 布局的跨设备保存：每个项目存一份 JSON。
 *
 * <p>只存视觉坐标（actors / usecases / participants），不修改故事、任务、成员等业务数据；
 * 任何项目成员可读，具备写权限的成员可保存。表 {@code uml_layouts} 由 schema.sql 自举。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/uml-layout")
@RequiredArgsConstructor
public class UmlLayoutController {

    /** 布局 JSON 上限：坐标数组远小于该值，设上限避免误传大对象。 */
    private static final int MAX_LAYOUT_CHARS = 65535;

    private final JdbcTemplate jdbc;
    private final ProjectAccessService projectAccess;
    private final ObjectMapper mapper;

    @GetMapping
    public Map<String, Object> get(@PathVariable String projectId) {
        projectAccess.requireMember(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT layout_json, updated_by, updated_at FROM uml_layouts WHERE project_id = ?", scoped);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("layout", null);
            return out;
        }
        Map<String, Object> row = rows.get(0);
        out.put("layout", readTree(String.valueOf(row.get("layout_json"))));
        out.put("updated_by", row.get("updated_by"));
        out.put("updated_at", row.get("updated_at"));
        return out;
    }

    @PutMapping
    @Transactional
    public Map<String, Object> save(@PathVariable String projectId, @RequestBody JsonNode body) {
        User actor = Roles.writer();
        projectAccess.requireWriter(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        JsonNode layout = body != null && body.has("layout") ? body.get("layout") : body;
        if (layout == null || !layout.isObject()) {
            throw ApiException.unprocessable("layout 必须为 JSON 对象");
        }
        String json = layout.toString();
        if (json.length() > MAX_LAYOUT_CHARS) {
            throw ApiException.unprocessable("布局数据过大（上限 64KB）");
        }
        jdbc.update(
                "INSERT INTO uml_layouts(project_id, layout_json, updated_by, updated_at) VALUES (?,?,?,NOW()) "
                        + "ON DUPLICATE KEY UPDATE layout_json=VALUES(layout_json), updated_by=VALUES(updated_by), updated_at=NOW()",
                scoped, json, actor.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private Object readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
