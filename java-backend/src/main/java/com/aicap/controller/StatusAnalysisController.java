package com.aicap.controller;

import com.aicap.service.MeetingAccessGuard;
import com.aicap.service.StatusAnalysisService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 状态域分析记录:路径是本域唯一的东西,处理器与权限口径继承自 {@link AbstractMeetingAnalysisController}。 */
@RestController
@RequestMapping("/api/meetings/{meetingId}/status-analyses")
public class StatusAnalysisController extends AbstractMeetingAnalysisController {

    private final StatusAnalysisService delegate;

    public StatusAnalysisController(StatusAnalysisService delegate, MeetingAccessGuard guard) {
        super(guard);
        this.delegate = delegate;
    }

    @Override
    protected StatusAnalysisService service() {
        return delegate;
    }
}
