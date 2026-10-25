package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.planning.PlanningAgentService;
import com.aicap.security.AuthContext;
import com.aicap.service.EfficiencyAnalysisService;
import com.aicap.service.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EfficiencyAnalysisControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ProjectAccessService access;
    private EfficiencyAnalysisService service;
    private PlanningAgentService planning;
    private EfficiencyAnalysisController controller;

    @BeforeEach void setup() {
        access = mock(ProjectAccessService.class);
        service = mock(EfficiencyAnalysisService.class);
        planning = mock(PlanningAgentService.class);
        controller = new EfficiencyAnalysisController(access, service, planning);
        var user = new User(); user.setId(7); user.setRole("owner"); AuthContext.set(user);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test void approvedProposalOnlyCreatesAPlanningDraftForTheSameProject() {
        when(service.proposal("P001", "proposal-1"))
                .thenReturn(Map.of("status", "approved", "recommendation_id", "R1", "proposal",
                        mapper.valueToTree(Map.of("title", "复核阻塞", "recommendations", java.util.List.of(Map.of("id", "R1", "description", "复核阻塞原因"))))));
        when(planning.create(eq("P001"), any(), any())).thenReturn(Map.of("id", "plan-1", "status", "queued"));

        var result = controller.planApproved("p001", "proposal-1");

        assertEquals("plan-1", result.get("id"));
        verify(access).requireOwner("P001");
        verify(planning).create(eq("P001"), startsWith("基于已批准的US23效率建议生成规划草案"), any());
        verify(service).linkPlanningRun("P001", "proposal-1", "plan-1");
        assertTrue(result.containsKey("status"));
    }

    @Test void repeatedPlanningRequestReturnsExistingRun() {
        when(service.proposal("P001", "proposal-1"))
                .thenReturn(Map.of("status", "approved"));
        when(service.latestPlanningRun("P001", "proposal-1"))
                .thenReturn(Map.of("id", "plan-1", "status", "waiting_confirmation", "action_count", 1));
        when(planning.get("plan-1", "P001")).thenReturn(Map.of("id", "plan-1", "status", "waiting_confirmation"));

        assertEquals("plan-1", controller.planApproved("P001", "proposal-1").get("id"));
        verify(planning, never()).create(any(), any(), any());
    }

    @Test void longEvidenceIsBoundedBeforeSavingPlanningRequest() {
        var source = mapper.createObjectNode();
        source.put("type", "WORKLOAD_IMBALANCE");
        source.put("title", "计划负载".repeat(500));
        var affected = source.putArray("affectedTaskCodes");
        var evidence = source.putArray("evidence");
        var recommendation = source.putArray("recommendations")
                .addObject().put("id", "R1").put("description", "复核容量并调整排期".repeat(500));
        var targets = recommendation.putArray("targetTaskIds");
        var members = recommendation.putArray("targetMemberIds");
        for (int i = 0; i < 50; i++) {
            affected.add("T01" + i); targets.add("T01" + i); members.add(i);
            evidence.add("容量证据".repeat(1000));
        }
        when(service.proposal("P001", "proposal-1"))
                .thenReturn(Map.of("status", "approved", "recommendation_id", "R1", "proposal", source));
        when(planning.create(eq("P001"), any(), any())).thenReturn(Map.of("id", "plan-1"));

        controller.planApproved("P001", "proposal-1");

        var request = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(planning).create(eq("P001"), request.capture(), any());
        assertTrue(request.getValue().length() <= 2000);
        assertTrue(request.getValue().contains("T010"));
        assertTrue(request.getValue().contains("还有42项"));
        assertTrue(request.getValue().contains("查询项目实时数据"));
    }

    @Test void pendingProposalCannotCreateAPlanningRun() {
        when(service.proposal("P001", "proposal-1"))
                .thenReturn(Map.of("status", "pending", "proposal", Map.of("title", "复核阻塞")));

        assertEquals(409, assertThrows(ApiException.class,
                () -> controller.planApproved("P001", "proposal-1")).getStatus());
        verify(planning, never()).create(any(), any(), any());
    }

    @Test void projectMemberWithoutManagePermissionCannotCreateAPlanningRun() {
        doThrow(ApiException.forbidden("仅项目负责人可执行此操作")).when(access).requireOwner("P001");

        assertEquals(403, assertThrows(ApiException.class,
                () -> controller.planApproved("P001", "proposal-1")).getStatus());
        verify(service, never()).proposal(any(), any());
        verify(planning, never()).create(any(), any(), any());
    }
}
