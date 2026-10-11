package com.aicap.generation;

import com.aicap.agent.AgentProperties;
import com.aicap.common.ApiException;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.entity.User;
import com.aicap.mapper.ProjectGenerationEventMapper;
import com.aicap.mapper.ProjectGenerationRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectGenerationServiceTest {
    @Mock private ProjectGenerationRunMapper runs;
    @Mock private ProjectGenerationEventMapper events;
    @Mock private ProjectGenerationJobs jobs;
    @Mock private ProjectGenerationValidator validator;
    @Mock private ProjectGenerationImportService importer;
    @Mock private ProjectGenerationExecutionService execution;
    @Mock private AgentProperties props;

    private ProjectGenerationService service;
    private ProjectGenerationRun run;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new ProjectGenerationService(runs, events, jobs, validator, importer,
                execution, props, new ObjectMapper());
        run = new ProjectGenerationRun();
        run.setId("run-1");
        run.setStatus("waiting_confirmation");
        run.setStrategy("merge");
        run.setDraftJson("{\"stories\":[],\"tasks\":[]}");
        actor = new User();
        actor.setId(1);
        actor.setRole("owner");
        when(runs.selectById("run-1")).thenReturn(run);
        when(runs.update(any(), any())).thenReturn(1);
    }

    @Test
    void confirmRecordsAuditableSuccessSequence() {
        service.confirm("run-1", false, actor);

        InOrder order = inOrder(jobs, execution, runs);
        order.verify(jobs).event(run, "confirmed", "用户确认执行项目图");
        order.verify(jobs).event(run, "action_started", "开始写入项目图业务数据");
        order.verify(execution).execute(run.getDraftJson(), run.getStrategy(), actor);
        order.verify(jobs).event(run, "action_completed", "项目图业务数据写入完成");
        order.verify(runs).updateById(run);
        order.verify(jobs).event(run, "completed", "项目图已写入统一项目数据");
        assertEquals("completed", run.getStatus());
    }

    @Test
    void repeatedConfirmIsRejectedWithoutSecondExecution() {
        when(runs.update(any(), any())).thenReturn(1, 0);

        service.confirm("run-1", false, actor);
        assertThrows(ApiException.class, () -> service.confirm("run-1", false, actor));

        verify(execution).execute(run.getDraftJson(), run.getStrategy(), actor);
        verify(jobs).event(run, "confirmed", "用户确认执行项目图");
    }

    @Test
    void cancelRecordsCancelledAndCannotBeConfirmed() {
        Map<String, Object> result = service.cancel("run-1", actor);

        assertEquals("cancelled", result.get("status"));
        verify(jobs).event(run, "cancelled", "用户取消，未修改项目数据");
        assertThrows(ApiException.class, () -> service.confirm("run-1", false, actor));
        verify(execution, never()).execute(anyString(), anyString(), any());
    }

    @Test
    void failedExecutionHasNoCompletionEvent() {
        RuntimeException failure = new RuntimeException("写库失败");
        doThrow(failure).when(execution).execute(run.getDraftJson(), run.getStrategy(), actor);

        assertThrows(RuntimeException.class, () -> service.confirm("run-1", false, actor));

        verify(jobs).event(run, "confirmed", "用户确认执行项目图");
        verify(jobs).event(run, "action_started", "开始写入项目图业务数据");
        verify(jobs).event(run, "failed", "写库失败");
        verify(jobs, never()).event(run, "action_completed", "项目图业务数据写入完成");
        verify(jobs, never()).event(run, "completed", "项目图已写入统一项目数据");
        assertEquals("failed", run.getStatus());
    }
}
