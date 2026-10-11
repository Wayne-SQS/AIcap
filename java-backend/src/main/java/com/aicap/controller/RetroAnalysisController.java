package com.aicap.controller;

import com.aicap.service.RetroAnalysisService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 复盘域分析记录:路径是本域唯一的东西,处理器与权限口径继承自 {@link AbstractMeetingAnalysisController}。 */
@RestController
@RequestMapping("/api/meetings/{meetingId}/retro-analyses")
public class RetroAnalysisController extends AbstractMeetingAnalysisController {

    private final RetroAnalysisService delegate;

    public RetroAnalysisController(RetroAnalysisService delegate) {
        this.delegate = delegate;
    }

    @Override
    protected RetroAnalysisService service() {
        return delegate;
    }
}
