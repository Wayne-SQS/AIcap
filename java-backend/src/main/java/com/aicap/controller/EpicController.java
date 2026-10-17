package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.Epic;
import com.aicap.entity.Story;
import com.aicap.mapper.EpicMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.security.AuthContext;
import com.aicap.security.Roles;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 项目内的史诗名称和故事归属。删除已有故事的史诗前须先调整故事。 */
@RestController
@RequestMapping("/api/epics")
@RequiredArgsConstructor
public class EpicController {
    private final EpicMapper epics;
    private final StoryMapper stories;

    public record EpicIn(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) { }
    public record EpicOut(Long id, String code, String name, String description, long storyCount) { }

    @GetMapping
    public List<EpicOut> list() {
        Roles.any();
        return epics.selectList(new QueryWrapper<Epic>().eq("project_id", AuthContext.projectId())
                .orderByAsc("id")).stream().map(this::out).toList();
    }

    @PostMapping
    @Transactional
    public EpicOut create(@Valid @RequestBody EpicIn body) {
        Roles.reviewer();
        String name = body.name().trim();
        requireUnique(name, null);
        Epic row = new Epic();
        row.setProjectId(AuthContext.projectId());
        row.setName(name);
        row.setDescription(body.description() == null ? "" : body.description().trim());
        row.setCreatedAt(LocalDateTime.now());
        epics.insert(row);
        return out(row);
    }

    @PatchMapping("/{id}")
    @Transactional
    public EpicOut patch(@PathVariable Long id, @Valid @RequestBody EpicIn body) {
        Roles.reviewer();
        Epic row = current(id);
        String name = body.name().trim();
        requireUnique(name, id);
        row.setName(name);
        row.setDescription(body.description() == null ? "" : body.description().trim());
        epics.updateById(row);
        return out(row);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public java.util.Map<String, Boolean> delete(@PathVariable Long id) {
        Roles.reviewer();
        current(id);
        if (stories.selectCount(new QueryWrapper<Story>().eq("project_id", AuthContext.projectId())
                .eq("epic_id", id)) > 0)
            throw ApiException.conflict("该史诗仍有关联故事，请先调整故事归属");
        epics.deleteById(id);
        return java.util.Map.of("ok", true);
    }

    private Epic current(Long id) {
        Epic row = epics.selectById(id);
        if (row == null || row.getProjectId() == null || row.getProjectId() != AuthContext.projectId())
            throw ApiException.notFound("史诗不存在");
        return row;
    }

    private void requireUnique(String name, Long except) {
        for (Epic row : epics.selectList(new QueryWrapper<Epic>().eq("project_id", AuthContext.projectId())))
            if (!row.getId().equals(except) && row.getName().equalsIgnoreCase(name))
                throw ApiException.conflict("项目内已有同名史诗");
    }

    private EpicOut out(Epic row) {
        long count = stories.selectCount(new QueryWrapper<Story>().eq("project_id", AuthContext.projectId())
                .eq("epic_id", row.getId()));
        return new EpicOut(row.getId(), "E" + row.getId(), row.getName(), row.getDescription(), count);
    }
}
