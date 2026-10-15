package com.aicap.contract;

import com.aicap.entity.ProjectMember;
import com.aicap.entity.Task;
import com.aicap.mapper.ProjectMemberMapper;
import com.aicap.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Project-scoped fixtures for the legacy profile contracts. */
abstract class ProfileProjectContractSupport extends ContractTestSupport {
    @Autowired private ProjectMemberMapper projectMembers;
    @Autowired private TaskMapper tasks;
    private final List<Long> insertedMembers = new ArrayList<>();
    private final List<Long> insertedTasks = new ArrayList<>();

    @BeforeAll
    void prepareProfileProject() {
        addMember(3, "member");
        addMember(4, "member");
        addMember(5, "viewer");
        addTask("TPROF1", 3, 1, null);
        addTask("TPROF2", 4, 2, "TPROF1");
    }

    private void addMember(int userId, String role) {
        if (projectMembers.selectCount(new QueryWrapper<ProjectMember>()
                .eq("project_id", TEST_PROJECT_ID).eq("user_id", userId)) > 0) return;
        ProjectMember member = new ProjectMember();
        member.setProjectId(TEST_PROJECT_ID);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setCapacityHours(60);
        member.setJoinedAt(LocalDateTime.now());
        projectMembers.insert(member);
        insertedMembers.add(member.getId());
    }

    private void addTask(String code, int ownerId, int status, String dependsOn) {
        if (tasks.selectCount(new QueryWrapper<Task>()
                .eq("project_id", TEST_PROJECT_ID).eq("code", code)) > 0) return;
        Task task = new Task();
        task.setProjectId(TEST_PROJECT_ID);
        task.setId(code);
        task.setName("画像契约任务 " + code);
        task.setOwnerId(ownerId);
        task.setHours(8);
        task.setWeekStart(1);
        task.setWeekEnd(2);
        task.setEstimatedHours(8);
        task.setTaskType("feature");
        task.setStatus(status);
        task.setDependsOn(dependsOn);
        task.setPriority("Should");
        task.setProgress(status == 2 ? 100 : 50);
        task.setBlocked(0);
        tasks.insert(task);
        insertedTasks.add(task.getInternalId());
    }

    @AfterAll
    void removeProfileProject() {
        insertedTasks.forEach(tasks::deleteById);
        insertedMembers.forEach(projectMembers::deleteById);
    }
}
