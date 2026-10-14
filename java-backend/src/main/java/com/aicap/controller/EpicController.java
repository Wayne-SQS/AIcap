package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.Epic;
import com.aicap.entity.Story;
import com.aicap.mapper.EpicMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.service.ProjectAccessService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 项目内的史诗；删除前必须解除所有故事的归属。 */
@RestController
@RequestMapping("/api/epics")
@RequiredArgsConstructor
public class EpicController {
    private final EpicMapper epics;
    private final StoryMapper stories;
    private final ProjectAccessService projectAccess;

    public record EpicIn(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) { }
    public record EpicOut(Long id, String code, String name, String description, long storyCount) { }

    @GetMapping
    public List<EpicOut> list(@RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        return epics.selectList(new QueryWrapper<Epic>().eq("project_id", scoped).orderByAsc("id"))
                .stream().map(epic -> out(epic, scoped)).toList();
    }

    @PostMapping
    @Transactional
    public EpicOut create(@Valid @RequestBody EpicIn body, @RequestParam String projectId) {
        projectAccess.requireOwner(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        String name = body.name().trim();
        requireUnique(scoped, name, null);
        Epic epic = new Epic();
        epic.setProjectId(scoped);
        epic.setName(name);
        epic.setDescription(body.description() == null ? "" : body.description().trim());
        epic.setCreatedAt(LocalDateTime.now());
        epics.insert(epic);
        return out(epic, scoped);
    }

    @PatchMapping("/{id}")
    @Transactional
    public EpicOut patch(@PathVariable Long id, @Valid @RequestBody EpicIn body, @RequestParam String projectId) {
        projectAccess.requireOwner(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        Epic epic = current(scoped, id);
        String name = body.name().trim();
        requireUnique(scoped, name, id);
        epic.setName(name);
        epic.setDescription(body.description() == null ? "" : body.description().trim());
        epics.updateById(epic);
        return out(epic, scoped);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Boolean> delete(@PathVariable Long id, @RequestParam String projectId) {
        projectAccess.requireOwner(projectId);
        String scoped = ProjectAccessService.normalize(projectId);
        current(scoped, id);
        if (stories.selectCount(new QueryWrapper<Story>().eq("project_id", scoped).eq("epic_id", id)) > 0)
            throw ApiException.conflict("该史诗仍有关联故事，请先调整故事归属");
        epics.delete(new QueryWrapper<Epic>().eq("project_id", scoped).eq("id", id));
        return Map.of("ok", true);
    }

    private Epic current(String projectId, Long id) {
        Epic epic = epics.selectOne(new QueryWrapper<Epic>().eq("project_id", projectId).eq("id", id));
        if (epic == null) throw ApiException.notFound("史诗不存在");
        return epic;
    }

    private void requireUnique(String projectId, String name, Long except) {
        for (Epic epic : epics.selectList(new QueryWrapper<Epic>().eq("project_id", projectId))) {
            if (!epic.getId().equals(except) && epic.getName().equalsIgnoreCase(name))
                throw ApiException.conflict("项目内已有同名史诗");
        }
    }

    private EpicOut out(Epic epic, String projectId) {
        long count = stories.selectCount(new QueryWrapper<Story>().eq("project_id", projectId).eq("epic_id", epic.getId()));
        return new EpicOut(epic.getId(), "E" + epic.getId(), epic.getName(), epic.getDescription(), count);
    }
}
