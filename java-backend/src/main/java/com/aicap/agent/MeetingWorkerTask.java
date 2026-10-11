package com.aicap.agent;

import com.aicap.worker.WorkerTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会议 Agent 的后台任务。**随 {@code com.aicap.agent} 一起退役** ——
 * 它消失时,调度器只会少一个任务,planning / generation 的后台消费不受影响。
 */
@Component
@RequiredArgsConstructor
public class MeetingWorkerTask implements WorkerTask {

    private final AgentJobs jobs;

    @Override
    public String name() {
        return "meeting-agent";
    }

    @Override
    public boolean runNext() {
        return jobs.runNext();
    }
}
