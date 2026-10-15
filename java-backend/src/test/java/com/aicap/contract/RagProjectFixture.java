package com.aicap.contract;

import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.LocalDateTime;

/** A small explicit project fixture for RAG; production no longer seeds P001 on every start. */
final class RagProjectFixture {
    private RagProjectFixture() { }

    static void ensure(StoryMapper stories, TaskMapper tasks) {
        story(stories, "US01", "项目与成员范围");
        story(stories, "US02", "角色与权限边界");
        task(tasks, "T01", "启动会、基线、六项材料初稿", "US01,US03,US04", null);
        task(tasks, "T03", "项目、成员、自定义权限", "US01,US02", "US01");
        task(tasks, "T04", "成员批量导入", "US02", null);
    }

    private static void story(StoryMapper mapper, String code, String title) {
        if (mapper.selectCount(new QueryWrapper<Story>().eq("project_id", ContractTestSupport.TEST_PROJECT_ID)
                .eq("code", code)) > 0) return;
        Story row = new Story();
        row.setProjectId(ContractTestSupport.TEST_PROJECT_ID);
        row.setId(code);
        row.setTitle(title);
        row.setDescription("作为管理员，我希望" + title + "，以便团队协作");
        row.setAcceptance("项目成员可查看对应的规划资料");
        row.setPriority("Must");
        row.setSprint(1);
        row.setActivity(1);
        row.setStatus(0);
        row.setOwnerId(1);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
    }

    private static void task(TaskMapper mapper, String code, String name, String refs, String card) {
        if (mapper.selectCount(new QueryWrapper<Task>().eq("project_id", ContractTestSupport.TEST_PROJECT_ID)
                .eq("code", code)) > 0) return;
        Task row = new Task();
        row.setProjectId(ContractTestSupport.TEST_PROJECT_ID);
        row.setId(code);
        row.setName(name);
        row.setOwnerId(1);
        row.setHours(12);
        row.setWeekStart(1);
        row.setWeekEnd(2);
        row.setStoryRef(refs);
        row.setKanbanCardId(card);
        row.setEstimatedHours(12);
        row.setTaskType("feature");
        row.setStatus(0);
        row.setDependsOn("");
        row.setProgress(0);
        row.setBlocked(0);
        mapper.insert(row);
    }
}
