package com.aicap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 五类会议分析记录(计划/评审/复盘/状态/细化)的公共读写面。
 *
 * <p>五个域的实现<b>逐字同形</b>:同 4 个方法、同样的参数与返回。提出这个基类是为了让
 * REST 面只写一份 —— 那里恰好是<b>权限口径</b>的落点({@code Roles.writer()} 写、
 * {@code Roles.any()} 读),复制五份意味着改一次权限要改五个地方,而它们必然漂移。
 *
 * <p><b>为什么是抽象类而不是接口</b>:这几个 Service 上有 {@code @Transactional},
 * 一旦它们<b>实现接口</b>,Spring 在 {@code @EnableTransactionManagement} 下会改用
 * <b>JDK 动态代理</b>,bean 的运行时类型就不再是具体类 —— 所有按具体类型注入/获取的地方
 * (包括 5 个 H2 存储测试里的 {@code context.getBean(XAnalysisService.class)} 与
 * {@code new XAnalysisController(service)})会一起失效。抽象基类不触发这条切换,
 * 代理仍是 CGLIB 子类,按具体类型拿 bean 依旧成立。(这不是假想:换接口那一版真的
 * 打红了 49 个测试。)
 */
public abstract class AbstractMeetingAnalysisService {

    public abstract ObjectNode save(String meetingId, JsonNode body, int userId);

    public abstract List<ObjectNode> list(String meetingId);

    public abstract ObjectNode findByRequest(String meetingId, String requestId, int userId);

    public abstract ObjectNode get(String meetingId, String id);
}
