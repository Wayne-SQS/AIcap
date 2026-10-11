package com.aicap.planning;

import com.aicap.worker.WorkerTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** planning Agent 的后台任务(自注册到 {@link com.aicap.worker.AgentWorker})。 */
@Component
@RequiredArgsConstructor
public class PlanningWorkerTask implements WorkerTask {

    private final PlanningAgentJobs jobs;

    @Override
    public String name() {
        return "planning-agent";
    }

    @Override
    public boolean runNext() {
        return jobs.runNext();
    }
}
