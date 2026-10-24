package com.aicap.controller;

import com.aicap.service.MeetingAccessGuard;
import com.aicap.service.PlanningAnalysisService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 计划域分析记录:路径是本域唯一的东西,处理器与权限口径继承自 {@link AbstractMeetingAnalysisController}。 */
@RestController
@RequestMapping("/api/meetings/{meetingId}/planning-analyses")
public class PlanningAnalysisController extends AbstractMeetingAnalysisController {

    private final PlanningAnalysisService delegate;

    public PlanningAnalysisController(PlanningAnalysisService delegate, MeetingAccessGuard guard) {
        super(guard);
        this.delegate = delegate;
    }

    @Override
    protected PlanningAnalysisService service() {
        return delegate;
    }
}
