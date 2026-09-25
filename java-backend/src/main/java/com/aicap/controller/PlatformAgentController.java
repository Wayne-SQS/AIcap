package com.aicap.controller;

import com.aicap.entity.User;
import com.aicap.platform.PlatformToolCatalog;
import com.aicap.platform.PlatformToolService;
import com.aicap.security.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台工具层对外接口(S3):供独立进程的编排层经 HTTP 调用。
 *
 * <p>两个端点,权限分开:
 * <ul>
 *   <li>{@code /catalog} —— <b>只读元数据</b>,任何登录用户可读。它回答"平台能做什么",
 *       不含任何业务字段,所以能开;分开两个端点正是为了能开(规划 §3.2 要求可审计);</li>
 *   <li>{@code /tools/{name}} —— 需要写权限(admin/owner/member)。工具会取真实数据,
 *       门槛与"触发 AI 分析"一致;viewer 调用 → 403。</li>
 * </ul>
 *
 * <p><b>未知工具返回 404,而不是 200 + ok=false</b>:前者是"你调了一个不存在的名字",
 * 属于调用方接错了契约,应该在开发期就炸出来;后者留给"工具存在但这次执行失败",
 * 那种失败模型能自我纠正。两者混在一起,契约写错就会被当成模型犯错。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class PlatformAgentController {

    private final PlatformToolCatalog catalog;
    private final PlatformToolService toolService;

    /** 能力清单:工具名、说明、参数 schema、限制声明。只有元数据 */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Roles.any();
        return catalog.catalogJson();
    }

    /**
     * 统一工具调用。
     *
     * <p>响应恒为 200(含业务失败),理由见 {@link PlatformToolService}。
     */
    @PostMapping("/tools/{toolName}")
    public PlatformToolService.ToolOutcome invoke(@PathVariable String toolName,
                                                  @RequestBody(required = false) JsonNode args) {
        User caller = Roles.writer();
        return toolService.invoke(toolName, args, caller);
    }
}
