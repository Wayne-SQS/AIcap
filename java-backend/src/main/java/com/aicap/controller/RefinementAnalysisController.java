package com.aicap.controller;

import com.aicap.service.MeetingAccessGuard;
import com.aicap.service.RefinementAnalysisService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 细化域分析记录:路径是本域唯一的东西,处理器与权限口径继承自 {@link AbstractMeetingAnalysisController}。 */
@RestController
@RequestMapping("/api/meetings/{meetingId}/refinement-analyses")
public class RefinementAnalysisController extends AbstractMeetingAnalysisController {

    private final RefinementAnalysisService delegate;

    public RefinementAnalysisController(RefinementAnalysisService delegate, MeetingAccessGuard guard) {
        super(guard);
        this.delegate = delegate;
    }

    @Override
    protected RefinementAnalysisService service() {
        return delegate;
    }
}
