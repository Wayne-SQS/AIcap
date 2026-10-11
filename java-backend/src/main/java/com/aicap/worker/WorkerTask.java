package com.aicap.worker;

/**
 * 一个可被 {@link AgentWorker} 轮询的后台任务。
 *
 * <p>实现类留在各自域包里(会议在 {@code com.aicap.agent}、planning 在
 * {@code com.aicap.planning}、生成在 {@code com.aicap.generation}),
 * 调度器只按 {@link #name()} 起线程、按 {@link #name()} 出状态,
 * <b>不认识任何具体域</b> —— 否则共享调度层就会反向依赖可退役的包。
 */
public interface WorkerTask {

    /** 状态快照里的 key(如 {@code meeting-agent}) */
    String name();

    /** 跑一轮;有活干返回 true(调度器据此决定休眠长短) */
    boolean runNext();
}
