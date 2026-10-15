package com.aicap.profile;

import com.aicap.worker.WorkerTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 画像 Agent 的后台任务(自注册到 {@link com.aicap.worker.AgentWorker})。
 *
 * <p>它兑现的是 {@code PlatformToolCatalog#START_PROFILE_ANALYSIS} 这枚触发型工具的承诺:
 * 工具只建 run 就返回,真正的分析在这里跑。没有它时该工具只能"暂缓"——
 * 因为同步执行会吃穿编排层的有界循环预算。
 */
@Component
@RequiredArgsConstructor
public class ProfileWorkerTask implements WorkerTask {

    private final ProfileAgentJobs jobs;

    @Override
    public String name() {
        return "profile-agent";
    }

    @Override
    public boolean runNext() {
        return jobs.runNext();
    }
}
