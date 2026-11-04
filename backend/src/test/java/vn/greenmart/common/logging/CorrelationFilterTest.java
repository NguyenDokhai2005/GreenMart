package vn.greenmart.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.greenmart.common.exception.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CorrelationFilterTest {
    @AfterEach void clear() { MDC.clear(); }
    @Test void errorBodyAndHeaderShareRequestTraceId() throws Exception {
        MockMvcBuilders.standaloneSetup(new FailureController()).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationFilter()).build().perform(get("/failure").header(TraceContext.HEADER, "trace-129"))
                .andExpect(status().isForbidden()).andExpect(header().string(TraceContext.HEADER, "trace-129"))
                .andExpect(jsonPath("$.traceId").value("trace-129"));
        assertThat(MDC.get("traceId")).isNull();
    }
    @Test void invalidIdIsReplacedAndMdcRestoredEvenOnException() {
        MDC.put("traceId", "outer");
        var request = new MockHttpServletRequest(); request.addHeader(TraceContext.HEADER, "bad\nheader");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new CorrelationFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("traceId")).matches("[a-zA-Z0-9_-]{1,64}").isNotEqualTo("outer");
            throw new jakarta.servlet.ServletException("test");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.get("traceId")).isEqualTo("outer");
        assertThat(response.getHeader(TraceContext.HEADER)).doesNotContain("\n");
    }
    @Test void separateRequestsGetDifferentTraceIds() throws Exception {
        var first = new MockHttpServletResponse(); var second = new MockHttpServletResponse();
        var filter = new CorrelationFilter();
        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> {});
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {});
        assertThat(first.getHeader(TraceContext.HEADER)).isNotEqualTo(second.getHeader(TraceContext.HEADER));
        assertThat(MDC.get("traceId")).isNull();
    }
    @RestController static class FailureController {
        @GetMapping("/failure") void failure() { throw new AppException(ErrorCode.FORBIDDEN); }
    }
}
