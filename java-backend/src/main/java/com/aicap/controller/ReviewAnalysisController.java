package com.aicap.controller;

import com.aicap.service.MeetingAccessGuard;
import com.aicap.service.ReviewAnalysisService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 评审域分析记录:路径是本域唯一的东西,处理器与权限口径继承自 {@link AbstractMeetingAnalysisController}。 */
@RestController
@RequestMapping("/api/meetings/{meetingId}/review-analyses")
public class ReviewAnalysisController extends AbstractMeetingAnalysisController {

    private final ReviewAnalysisService delegate;

    public ReviewAnalysisController(ReviewAnalysisService delegate, MeetingAccessGuard guard) {
        super(guard);
        this.delegate = delegate;
    }

    @Override
    protected ReviewAnalysisService service() {
        return delegate;
    }
}
