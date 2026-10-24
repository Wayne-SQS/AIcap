package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.Roles;
import com.aicap.service.EfficiencyAnalysisService;
import com.aicap.service.ProjectAccessService;
import com.aicap.planning.PlanningAgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/efficiency-analysis")
public class EfficiencyAnalysisController {
    private final ProjectAccessService access;
    private final EfficiencyAnalysisService service;
    private final PlanningAgentService planning;
    private String scope(String projectId, boolean write) {
        String id=ProjectAccessService.normalize(projectId);
        if(write) { Roles.writer(); access.requireWriter(id); } else { Roles.any(); access.requireMember(id); }
        return id;
    }
    @PostMapping("/runs")
    public ObjectNode run(@PathVariable String projectId) { return service.run(scope(projectId,true),Roles.writer().getId()); }
    @GetMapping("/latest")
    public ObjectNode latest(@PathVariable String projectId) { return service.latest(scope(projectId,false)); }
    @GetMapping("/runs/{id}")
    public ObjectNode get(@PathVariable String projectId,@PathVariable String id) { return service.get(scope(projectId,false),id); }
    @PostMapping("/runs/{id}/recommendations/{recommendation}/submit")
    public Map<String,Object> submit(@PathVariable String projectId,@PathVariable String id,@PathVariable String recommendation) {
        return service.submit(scope(projectId,true),id,recommendation,Roles.writer().getId());
    }
    @GetMapping("/proposals/{id}")
    public Map<String,Object> proposal(@PathVariable String projectId,@PathVariable String id) { return service.proposal(scope(projectId,false),id); }
    @GetMapping("/runs/{id}/proposals")
    public java.util.List<Map<String,Object>> proposals(@PathVariable String projectId,@PathVariable String id) {
        return service.proposalsForAnalysis(scope(projectId,false),id);
    }
    @PostMapping("/proposals/{id}/review")
    public Map<String,Object> review(@PathVariable String projectId,@PathVariable String id,@RequestBody JsonNode body) {
        String scoped=scope(projectId,true);
        if(body==null || !body.isObject() || body.size()!=2 || !body.path("decision").isTextual() || !body.path("reason").isTextual())
            throw ApiException.unprocessable("审核请求只能包含decision与reason");
        return service.review(scoped,id,body.path("decision").asText(),body.path("reason").asText(),Roles.writer().getId());
    }
    @PostMapping("/proposals/{id}/planning-run")
    public Map<String,Object> planApproved(@PathVariable String projectId,@PathVariable String id) {
        String scoped=scope(projectId,true);
        access.requireOwner(scoped);
        Map<String,Object> proposal=service.proposal(scoped,id);
        if (!"approved".equals(proposal.get("status"))) throw ApiException.conflict("仅已批准的效率建议可以交给规划智能体");
        JsonNode source = (JsonNode) proposal.get("proposal");
        JsonNode selected = null;
        for (JsonNode item : source.path("recommendations"))
            if (String.valueOf(proposal.get("recommendation_id")).equals(item.path("id").asText())) { selected = item; break; }
        if (selected == null) throw ApiException.conflict("已批准的建议内容已失效，请重新分析");
        String request = "基于已批准的US23效率建议生成规划草案。只读取项目" + scoped + "当前真实数据，不得直接执行；"+
            "仅提出有依据的变更，列明目标和影响，等待项目经理再次确认。提案" + id +
            "；瓶颈类型=" + compact(source.path("type"), 40) +
            "；标题=" + compact(source.path("title"), 80) +
            "；建议=" + compact(selected.path("description"), 280) +
            "；目标任务=" + compactList(selected.path("targetTaskIds"), 8, 20) +
            "；目标成员=" + compactList(selected.path("targetMemberIds"), 8, 12) +
            "；受影响任务=" + compactList(source.path("affectedTaskCodes"), 8, 20) +
            "；证据=" + compactList(source.path("evidence"), 3, 100) +
            "。以上为有界摘要，必要时查询项目实时数据核实，不能从省略内容推断事实。";
        if (request.length() > 2000) throw ApiException.unprocessable("建议内容超过规划输入限制");
        return planning.create(scoped,request,Roles.any());
    }
    private static String compact(JsonNode node, int max) {
        String value = node.asText("");
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
    private static String compactList(JsonNode node, int maxItems, int maxLength) {
        if (!node.isArray()) return "[]";
        List<String> values = new ArrayList<>();
        int count = 0;
        for (JsonNode item : node) {
            if (count++ >= maxItems) break;
            values.add(compact(item, maxLength));
        }
        return values + (node.size() > maxItems ? "（还有" + (node.size() - maxItems) + "项，需查询项目数据）" : "");
    }
}
