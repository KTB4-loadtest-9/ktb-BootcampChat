package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    @Test
    void unauthorizedRequest_logsMethodUriAndStatusWithoutToken(CapturedOutput output) throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        ReflectionTestUtils.setField(filter, "profile", "production");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/files/view/example.png");
        request.addHeader("Authorization", "Bearer must-not-be-logged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response,
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse).setStatus(401));

        assertThat(output)
                .contains("method=GET")
                .contains("uri=/api/files/view/example.png")
                .contains("status=401")
                .doesNotContain("must-not-be-logged");
    }
}
