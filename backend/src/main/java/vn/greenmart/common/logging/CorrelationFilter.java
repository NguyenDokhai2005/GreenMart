package vn.greenmart.common.logging;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    private static final String ATTRIBUTE = CorrelationFilter.class.getName() + ".traceId";
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("traceId");
        Object stored = request.getAttribute(ATTRIBUTE);
        String traceId = TraceContext.normalize(stored == null ? request.getHeader(TraceContext.HEADER) : stored);
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        MDC.put("traceId", traceId);
        try { chain.doFilter(request, response); }
        finally {
            log.info("HTTP request method={} status={}", request.getMethod(), response.getStatus());
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }
}
