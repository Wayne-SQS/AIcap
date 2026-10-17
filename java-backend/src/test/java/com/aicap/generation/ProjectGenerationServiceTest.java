package com.aicap.generation;

import com.aicap.agent.AgentProperties;
import com.aicap.common.ApiException;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.entity.User;
import com.aicap.mapper.ProjectGenerationEventMapper;
import com.aicap.mapper.ProjectGenerationRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
        run.setProjectId("P001");
        run.setDraftJson("{\"stories\":[],\"tasks\":[]}");
        actor = new User();
        actor.setId(1);
        actor.setRole("owner");
        lenient().when(runs.selectById("run-1")).thenReturn(run);
        lenient().when(runs.update(any(), any())).thenReturn(1);
    }

    @Test
    void confirmRecordsAuditableSuccessSequence() {
        service.confirm("run-1", false, actor);

        InOrder order = inOrder(jobs, execution, runs);
        order.verify(jobs).event(run, "confirmed", "用户确认执行项目图");
        order.verify(jobs).event(run, "action_started", "开始写入项目图业务数据");
        order.verify(execution).execute("P001", run.getDraftJson(), run.getStrategy(), actor);
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

        verify(execution).execute("P001", run.getDraftJson(), run.getStrategy(), actor);
        verify(jobs).event(run, "confirmed", "用户确认执行项目图");
    }

    @Test
    void cancelRecordsCancelledAndCannotBeConfirmed() {
        Map<String, Object> result = service.cancel("run-1", actor);

        assertEquals("cancelled", result.get("status"));
        verify(jobs).event(run, "cancelled", "用户取消，未修改项目数据");
        assertThrows(ApiException.class, () -> service.confirm("run-1", false, actor));
        verify(execution, never()).execute(anyString(), anyString(), anyString(), any());
    }

    @Test
    void failedExecutionHasNoCompletionEvent() {
        RuntimeException failure = new RuntimeException("写库失败");
        doThrow(failure).when(execution).execute("P001", run.getDraftJson(), run.getStrategy(), actor);

        assertThrows(RuntimeException.class, () -> service.confirm("run-1", false, actor));

        verify(jobs).event(run, "confirmed", "用户确认执行项目图");
        verify(jobs).event(run, "action_started", "开始写入项目图业务数据");
        verify(jobs).event(run, "failed", "写库失败");
        verify(jobs, never()).event(run, "action_completed", "项目图业务数据写入完成");
        verify(jobs, never()).event(run, "completed", "项目图已写入统一项目数据");
        assertEquals("failed", run.getStatus());
    }

    @Test
    void createNewUsesTemporaryScopeWithoutRequiringProjectId() {
        when(props.settingsReady()).thenReturn(true);
        ArgumentCaptor<ProjectGenerationRun> created = ArgumentCaptor.forClass(ProjectGenerationRun.class);

        Map<String, Object> result = service.createNew(
                "detailed", "生成校园二手交易平台规划", "校园二手交易平台", "校内闲置物品交易", 3, actor);

        verify(runs).insert(created.capture());
        assertTrue(created.getValue().getProjectId().startsWith("NEW-"));
        assertEquals("create", created.getValue().getStrategy());
        assertNull(result.get("project_id"));
        assertEquals("queued", result.get("status"));
    }

    @Test
    void excelSupplementFailureStillReturnsEditableWaitingConfirmationDraft() {
        ObjectNode imported = new ObjectMapper().createObjectNode();
        imported.withArray("stories").add(new ObjectMapper().createObjectNode()
                .put("id", "US01").put("title", "用户导入的 Story").put("sprint", 1));
        when(importer.parse(any())).thenReturn(imported);
        when(jobs.prepareImport(any(ProjectGenerationRun.class), any(ObjectNode.class))).thenAnswer(invocation -> {
            ProjectGenerationRun importedRun = invocation.getArgument(0);
            importedRun.setErrorCode("ai_supplement_incomplete");
            importedRun.setErrorMessage("Excel/CSV 已解析成功，但 AI 补全未完成；已保留原始导入草案供人工审查");
            ObjectNode draft = imported.deepCopy();
            draft.set("completeness", new ObjectMapper().createObjectNode()
                    .put("sufficient", false).put("aiSupplementCompleted", false));
            return draft;
        });
        MockMultipartFile file = new MockMultipartFile("file", "plan.csv", "text/csv",
                "title,sprint\n用户导入的 Story,1\n".getBytes(StandardCharsets.UTF_8));

        Map<String,Object> result = service.importNew(file,"导入项目","",3,actor);

        assertEquals("waiting_confirmation",result.get("status"));
        assertEquals("ai_supplement_incomplete",result.get("error_code"));
        assertTrue(String.valueOf(result.get("draft")).contains("用户导入的 Story"));
        verify(execution,never()).createProjectAndExecute(anyString(),any());
    }
}
