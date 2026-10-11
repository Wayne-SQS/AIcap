package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.AbstractMeetingAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 五类会议分析记录的公共 REST 面。
 *
 * <p>五个域原先各有一份<b>逐字同形</b>的控制器(只差一个路径与一个服务类型),
 * 而这里正是<b>权限口径</b>的落点 —— 写要 {@code Roles.writer()}(含发起人身份下传)、
 * 读是 {@code Roles.any()}。复制五份的代价不是"多敲几行",而是改一次权限要改五处、
 * 且五处必然漂移;权限是漏一处就等于开了个口子的东西,所以只留一份。
 *
 * <p>子类只声明自己的 {@code @RequestMapping} 路径与 {@link #service()} 实现,
 * 处理器方法由本类继承(Spring 会沿父类查找方法级注解)。
 */
public abstract class AbstractMeetingAnalysisController {

    /** 子类给出自己的域服务(协变返回,可直接返回具体类型) */
    protected abstract AbstractMeetingAnalysisService service();

    @PostMapping
    public ObjectNode save(@PathVariable String meetingId, @RequestBody JsonNode body) {
        return service().save(meetingId, body, Roles.writer().getId());
    }

    @GetMapping
    public List<ObjectNode> list(@PathVariable String meetingId) {
        Roles.any();
        return service().list(meetingId);
    }

    @GetMapping("/by-request/{clientRequestId}")
    public ObjectNode findByRequest(@PathVariable String meetingId, @PathVariable String clientRequestId) {
        return service().findByRequest(meetingId, clientRequestId, Roles.writer().getId());
    }

    @GetMapping("/{analysisId}")
    public ObjectNode get(@PathVariable String meetingId, @PathVariable String analysisId) {
        Roles.any();
        return service().get(meetingId, analysisId);
    }
}
