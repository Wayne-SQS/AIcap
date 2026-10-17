package com.aicap.controller;

import com.aicap.entity.Milestone;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import org.springframework.web.bind.annotation.RequestParam;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 甘特图和 Planning Agent 共用的真实里程碑只读接口。 */
@RestController
@RequestMapping("/api/milestones")
@RequiredArgsConstructor
public class MilestoneController {
    private final MilestoneMapper milestones;
    private final ProjectAccessService projectAccess;

    @GetMapping
    public List<Milestone> list(@RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        return milestones.selectList(new QueryWrapper<Milestone>().eq("project_id", ProjectAccessService.normalize(projectId)).orderByAsc("week").orderByAsc("code"));
    }
}
