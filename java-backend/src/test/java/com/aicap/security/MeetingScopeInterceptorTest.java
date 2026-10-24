package com.aicap.security;

import com.aicap.service.MeetingAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MeetingScopeInterceptorTest {
    private final MeetingAccessGuard guard = mock(MeetingAccessGuard.class);
    private final MeetingScopeInterceptor interceptor = new MeetingScopeInterceptor(guard);

    @Test void nestedMeetingReadsRequireActualMeetingMembership() {
        var request = new MockHttpServletRequest("GET", "/api/meetings/m2/planning-analyses/a1/proposal-reviews");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("meetingId", "m2"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(guard).requireMember("m2");
        verifyNoMoreInteractions(guard);
    }

    @Test void nestedMeetingWritesRequireActualMeetingWriteAccess() {
        var request = new MockHttpServletRequest("POST", "/api/meetings/m2/retro-analyses/a1/proposal-executions");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("meetingId", "m2"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(guard).requireWriter("m2");
        verifyNoMoreInteractions(guard);
    }

    @Test void baseMeetingRouteKeepsControllerRoleCheckFirst() {
        var request = new MockHttpServletRequest("DELETE", "/api/meetings/missing");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("meetingId", "missing"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(guard);
    }
}
