package com.aicap.service;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        access=mock(ProjectAccessService.class);
        service=new EfficiencyAnalysisService(jdbc,new ObjectMapper(),mock(ModelClient.class),new LlmProperties(),access);
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
}
