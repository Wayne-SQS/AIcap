package com.aicap.generation;

import com.aicap.worker.WorkerTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 项目生成的后台任务(自注册到 {@link com.aicap.worker.AgentWorker})。 */
@Component
@RequiredArgsConstructor
public class GenerationWorkerTask implements WorkerTask {

    private final ProjectGenerationJobs jobs;

    @Override
    public String name() {
        return "project-generation";
    }

    @Override
    public boolean runNext() {
        return jobs.runNext();
    }
}
