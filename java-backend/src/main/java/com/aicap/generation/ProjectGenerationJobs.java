package com.aicap.generation;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.aicap.common.AgentError;
import com.aicap.entity.ProjectGenerationEvent;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.mapper.ProjectGenerationEventMapper;
import com.aicap.mapper.ProjectGenerationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectGenerationJobs {
    private final ProjectGenerationRunMapper runs;
    private final ProjectGenerationEventMapper events;
    private final ProjectGenerationRunner runner;
    private final ProjectGenerationValidator validator;
    private final ModelClient client;
    private final LlmProperties props;
    private final ObjectMapper mapper;

    /**
     * 租约时长(秒):须大于最坏运行时间(MAX_STEPS 6 × 单次模型超时 45s = 270s),
     * 对齐 planning / 会议侧的 300s。租约短于实际运行时间会让别的进程抢走还在跑的 run。
     */
    public static final long LEASE_SECONDS = 300;
    private static final String INTERRUPTED_MESSAGE = "生成进程中断或超时，可重试";

    /**
     * 过期 running(租约超时)→ failed + interrupted 事件(读/写路由进入时也会先调用)。
     * 判定:① 有租约且已到期;② 无租约且 updated_at 也已陈旧(补列前留下的孤儿)。
     * 对无租约要留宽限期 —— 导入路径在请求线程里也会置 running,一律当过期会误杀进行中的导入
     * (见 {@code ProjectGenerationService#importFile})。
     */
    public void expireRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<ProjectGenerationRun> stale = runs.selectList(new QueryWrapper<ProjectGenerationRun>()
                .eq("status", "running")
                .and(w -> w.lt("lease_until", now)
                        .or()
                        .isNull("lease_until")
                        .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
        for (ProjectGenerationRun run : stale) {
            int claimed = runs.update(null, new UpdateWrapper<ProjectGenerationRun>()
                    .set("status", "failed")
                    .set("worker_token", null)
                    .set("lease_until", null)
                    .set("error_code", "interrupted")
                    .set("error_message", INTERRUPTED_MESSAGE)
                    .set("updated_at", now)
                    .eq("id", run.getId())
                    .eq("status", "running")
                    .and(w -> w.lt("lease_until", now)
                            .or()
                            .isNull("lease_until")
                            .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
            if (claimed > 0) event(run, "failed", INTERRUPTED_MESSAGE);
        }
    }

    public boolean runNext() {
        expireRuns();
        ProjectGenerationRun run = runs.selectOne(new QueryWrapper<ProjectGenerationRun>()
                .eq("status", "queued").orderByAsc("created_at").last("LIMIT 1"));
        if (run == null) return false;
        String token = UUID.randomUUID().toString();
        int claimed = runs.update(null, new UpdateWrapper<ProjectGenerationRun>()
                .set("status", "running")
                .set("worker_token", token)
                .set("lease_until", LocalDateTime.now().plusSeconds(LEASE_SECONDS))
                .set("updated_at", LocalDateTime.now())
                .eq("id", run.getId()).eq("status", "queued"));
        if (claimed == 0) return true;
        event(run, "running", "开始生成项目规划草案");
        try {
            client.setModelOverride(run.getModel());
            var raw = runner.generate(run.getRequestText(), run.getMode(), client);
            var draft = validator.normalize(raw, run.getStrategy());
            run.setDraftJson(draft.toString());
            run.setStatus("waiting_confirmation");
            // 租约若已过期且被别的实例认领,放弃收尾,不用陈旧草案覆盖新 owner
            if (!finish(run, token, null, null)) return true;
            event(run, "draft_generated", "已生成并校验统一项目草案");
            event(run, "waiting_confirmation", "草案已生成，等待人工确认");
        } catch (AgentError e) {
            fail(run, token, e.getCode(), e.getMessage());
        } catch (Exception e) {
            // 原始异常文案可能带 SQL / 约束名:只进服务端日志,落库与回显都用分类文案
            log.warn("generation run {} 生成失败", run.getId(), e);
            fail(run, token, "generation_failed", "项目规划生成失败，请稍后重试");
        } finally {
            client.clearModelOverride();
        }
        return true;
    }

    public void event(ProjectGenerationRun run,String kind,String message){ProjectGenerationEvent e=new ProjectGenerationEvent();e.setRunId(run.getId());e.setAttempt(run.getAttempt());e.setKind(kind);e.setDetailJson(mapper.createObjectNode().put("message",message==null?"":message).toString());e.setCreatedAt(LocalDateTime.now());events.insert(e);}

    private void fail(ProjectGenerationRun run,String token,String code,String message){run.setStatus("failed");if(!finish(run,token,code,message))return;event(run,"failed",message);}

    /** 收尾写库:按 worker_token 守卫;成功时清空租约列,让 run 回到"无 owner"的终态 */
    private boolean finish(ProjectGenerationRun run,String token,String code,String message){
        UpdateWrapper<ProjectGenerationRun> update=new UpdateWrapper<ProjectGenerationRun>().set("status",run.getStatus()).set("worker_token",null).set("lease_until",null).set("updated_at",LocalDateTime.now()).eq("id",run.getId()).eq("status","running").eq("worker_token",token);
        if(code!=null)update.set("error_code",code).set("error_message",message);else update.set("draft_json",run.getDraftJson());
        return runs.update(null,update)>0;
    }
}
