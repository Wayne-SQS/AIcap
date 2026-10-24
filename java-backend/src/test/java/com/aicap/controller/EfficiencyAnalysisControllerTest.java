package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.planning.PlanningAgentService;
import com.aicap.security.AuthContext;
import com.aicap.service.EfficiencyAnalysisService;
import com.aicap.service.ProjectAccessService;
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
                .thenReturn(Map.of("status", "approved", "proposal", Map.of("title", "复核阻塞")));
        when(planning.create(eq("P001"), any(), any())).thenReturn(Map.of("id", "plan-1", "status", "queued"));

        var result = controller.planApproved("p001", "proposal-1");

        assertEquals("plan-1", result.get("id"));
        verify(access).requireOwner("P001");
        verify(planning).create(eq("P001"), startsWith("基于已审核的US23项目效率建议生成一份正式规划草案"), any());
        assertTrue(result.containsKey("status"));
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
