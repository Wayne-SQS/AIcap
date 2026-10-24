package com.aicap.service;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EfficiencyAnalysisServiceTest {
    JdbcTemplate jdbc;
    ProjectAccessService access;
    EfficiencyAnalysisService service;
    ModelClient model;
    LlmProperties properties;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE projects(id varchar(20),start_date date,end_date date,total_sprints int)");
        jdbc.execute("CREATE TABLE stories(internal_id bigint,project_id varchar(20),code varchar(20))");
        jdbc.execute("CREATE TABLE tasks(internal_id bigint,project_id varchar(20),code varchar(20),name varchar(200),owner_id int,status int,progress int,hours int,week_start int,week_end int,blocked int,story_internal_id bigint)");
        jdbc.execute("CREATE TABLE task_dependencies(project_id varchar(20),predecessor_task_internal_id bigint,successor_task_internal_id bigint)");
        jdbc.execute("CREATE TABLE task_logs(id bigint auto_increment primary key,project_id varchar(20),task_id varchar(20),old_status int,new_status int,created_at timestamp)");
        jdbc.execute("CREATE TABLE activity_records(id int,project_id varchar(20),user_id int,task_id varchar(20),module varchar(100),activity_type varchar(20),source varchar(20),title varchar(200),happened_at timestamp)");
        jdbc.execute("CREATE TABLE project_members(project_id varchar(20),user_id int,weekly_capacity_hours int,capacity_hours int,project_role varchar(20))");
        jdbc.execute("CREATE TABLE users(id int,display_name varchar(50))");
        jdbc.execute("CREATE TABLE member_profiles(user_id int,tech_stack text,capabilities text,process_domains text)");
        jdbc.execute("CREATE TABLE efficiency_analysis_runs(id varchar(36),project_id varchar(20),requested_by int,status varchar(30),result_json text,created_at timestamp,updated_at timestamp)");
        jdbc.execute("CREATE TABLE efficiency_proposals(id varchar(36),project_id varchar(20),analysis_id varchar(36),recommendation_id varchar(40),proposal_json text,status varchar(30),submitted_by int,created_at timestamp,reviewed_by int,reviewed_at timestamp,reason varchar(1000),unique(project_id,analysis_id,recommendation_id))");
        jdbc.execute("CREATE TABLE efficiency_proposal_checks(id varchar(36),project_id varchar(20),proposal_id varchar(36),metric_json text,measured_value decimal(12,4),outcome varchar(30),checked_by int,checked_at timestamp)");
        jdbc.execute("CREATE TABLE meetings(id varchar(36),project_id varchar(20))");
        jdbc.execute("CREATE TABLE suggestions(id varchar(10),status varchar(20),created_at timestamp)");
        jdbc.execute("CREATE TABLE meeting_suggestion_records(id int,suggestion_id varchar(10),meeting_id varchar(36),reviewed_at timestamp)");
        access=mock(ProjectAccessService.class);
        model=mock(ModelClient.class); properties=new LlmProperties();
        service=new EfficiencyAnalysisService(jdbc,new ObjectMapper(),model,properties,access);
    }
    @Test void calculateUsesProjectScopedRealInputsAndReportsMissingActualHours() {
        jdbc.update("INSERT INTO projects VALUES('P001','2026-10-01','2026-10-31',2)");
        jdbc.update("INSERT INTO projects VALUES('P002','2026-10-01','2026-10-31',2)");
        jdbc.update("INSERT INTO stories VALUES(11,'P001','US01')");
        jdbc.update("INSERT INTO tasks VALUES(1,'P001','T01','API',1,1,40,8,1,1,1,11)");
        jdbc.update("INSERT INTO tasks VALUES(2,'P001','T02','页面',1,0,0,5,1,2,0,11)");
        jdbc.update("INSERT INTO tasks VALUES(3,'P002','T01','Other project',2,0,0,40,1,2,0,null)");
        jdbc.update("INSERT INTO task_dependencies VALUES('P001',1,2)");
        jdbc.update("INSERT INTO task_dependencies VALUES('P002',3,3)");
        jdbc.update("INSERT INTO users VALUES(1,'A')");
        jdbc.update("INSERT INTO project_members VALUES('P001',1,12,72,'member')");
        var result=service.calculate("P001",java.time.LocalDate.of(2026,10,24));
        assertEquals("P001",result.path("audit").path("projectId").asText());
        assertEquals(2,result.path("coverage").path("taskCount").asInt());
        assertEquals(1,result.path("coverage").path("dependencyCount").asInt());
        assertFalse(result.path("coverage").path("actualHoursAvailable").asBoolean());
        assertFalse(result.path("actualHoursEvidence").path("available").asBoolean());
        assertEquals("TASK_BLOCKING",result.path("bottlenecks").get(0).path("type").asText());
        assertEquals(1,result.path("bottlenecks").get(0).path("impactChainLength").asInt());
        assertTrue(result.path("audit").path("missingEvidence").toString().contains("actual_hours"));
    }
    @Test void calculateSurfacesBacklogGrowthOnlyFromCreationAndStatusLogs() {
        jdbc.update("INSERT INTO projects VALUES('P001','2026-09-01','2026-12-31',4)");
        for (int i=1;i<=3;i++) {
            jdbc.update("INSERT INTO tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",i,"P001","T0"+i,"Work "+i,1,0,0,8,1,1,0,null);
            String created = switch (i) { case 1 -> "2026-09-19 09:00:00"; case 2 -> "2026-10-03 09:00:00"; default -> "2026-10-17 09:00:00"; };
            jdbc.update("INSERT INTO task_logs(project_id,task_id,old_status,new_status,created_at) VALUES('P001',?,NULL,0,?)",
                "T0"+i,java.sql.Timestamp.valueOf(created));
        }
        var result=service.calculate("P001",java.time.LocalDate.of(2026,10,24));
        assertTrue(result.path("coverage").path("backlogHistoryAvailable").asBoolean());
        assertEquals(1,result.path("backlogHistory").path("snapshots").get(0).path("openTasks").asInt());
        assertTrue(result.path("bottlenecks").toString().contains("TASK_BACKLOG_GROWTH"));
        assertEquals(2,result.path("bottlenecks").get(0).path("quantifiedImpact").path("backlogIncrease").asInt());
    }
    @Test void unavailableLlmDoesNotDiscardRuleAnalysis() throws Exception {
        properties.setApiKey("configured-for-test");
        when(model.complete(anyList(),anyList(),eq(true))).thenThrow(new com.aicap.common.AgentError("provider_unreachable","offline"));
        var rules=JsonNodeFactory.instance.objectNode();
        rules.putArray("bottlenecks").addObject().put("id","B1").putArray("evidence").add("tasks:T01.blocked=true");
        var result=service.explain(rules);
        assertEquals(1,result.path("bottlenecks").size());
        assertEquals("unavailable_or_invalid_rules_preserved",result.path("aiStatus").asText());
        assertFalse(result.path("audit").path("aiExplanationUsed").asBoolean());
    }
    @Test void proposalsAreProjectScopedAndApprovalDoesNotMutateTasks() {
        jdbc.update("INSERT INTO efficiency_analysis_runs VALUES('run-1','P001',1,'succeeded',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            """
            {"bottlenecks":[{"id":"B1","type":"WORKLOAD_IMBALANCE","recommendations":[{"id":"R1","description":"复核容量"}]}]}
            """);
        jdbc.update("INSERT INTO tasks VALUES(1,'P001','T01','原任务',7,0,0,8,1,1,0,null)");
        jdbc.update("INSERT INTO tasks VALUES(2,'P002','T01','其他项目',9,0,0,10,1,1,0,null)");
        var submitted=service.submit("P001","run-1","R1",1);
        String id=submitted.get("id").toString();
        when(access.requireOwner("P001")).thenReturn(null);
        service.review("P001",id,"approve","经理复核",2);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM efficiency_proposals WHERE status='approved'",Integer.class));
        assertEquals(7,jdbc.queryForObject("SELECT owner_id FROM tasks WHERE project_id='P001'",Integer.class));
        assertEquals(9,jdbc.queryForObject("SELECT owner_id FROM tasks WHERE project_id='P002'",Integer.class));
        assertThrows(com.aicap.common.ApiException.class,()->service.get("P002","run-1"));
        assertThrows(com.aicap.common.ApiException.class,()->service.proposal("P002",id));
    }
    @Test void approvedProposalCanPersistProjectScopedRecheckWithoutChangingTasks() {
        jdbc.update("INSERT INTO projects VALUES('P001','2026-10-01','2026-10-31',2)");
        jdbc.update("INSERT INTO efficiency_proposals VALUES('proposal-1','P001','run-1','R1',?,'approved',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP,'ok')",
            "{\"type\":\"TASK_BLOCKING\",\"affectedTaskCodes\":[\"T01\"],\"affectedMemberIds\":[],\"recheckMetric\":{\"metric\":\"same_bottleneck_rule_active\",\"baseline\":1,\"target\":0,\"reviewWindowDays\":14}}");
        var check=service.recordReviewCheck("P001","proposal-1",1);
        assertEquals("signal_reduced",check.get("outcome"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM efficiency_proposal_checks WHERE project_id='P001'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class));
        assertThrows(com.aicap.common.ApiException.class,()->service.recordReviewCheck("P002","proposal-1",1));
    }
}
