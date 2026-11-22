package vn.greenmart.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityErrors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final ObjectProvider<AuthRateLimiter> limiter;
    private final ObjectProvider<LoginClientIpResolver> clientIps;
    private final ObjectMapper mapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || bucket(request) == null;
    }

    private AuthRateLimiter.Bucket bucket(HttpServletRequest request) {
        return switch (UrlPathHelper.defaultInstance.getPathWithinApplication(request)) {
            case "/api/v1/auth/login" -> AuthRateLimiter.Bucket.LOGIN_IP;
            case "/api/v1/auth/refresh" -> AuthRateLimiter.Bucket.REFRESH_IP;
            case "/api/v1/auth/change-password" -> AuthRateLimiter.Bucket.PASSWORD_IP;
            default -> null;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        long retryAfter;
        var scope = bucket(request);
        try {
            String clientIp = clientIps.getObject().resolve(request);
            retryAfter = scope == AuthRateLimiter.Bucket.LOGIN_IP
                    ? limiter.getObject().retryAfterSeconds(clientIp)
                    : limiter.getObject().retryAfterSeconds(scope, clientIp);
        } catch (DataAccessException exception) {
            SecurityErrors.write(response, mapper, scope == AuthRateLimiter.Bucket.LOGIN_IP
                    ? ErrorCode.LOGIN_UNAVAILABLE : ErrorCode.AUTH_UNAVAILABLE);
            return;
        }
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            SecurityErrors.write(response, mapper, switch (scope) {
                case LOGIN_IP -> ErrorCode.LOGIN_RATE_LIMITED;
                case REFRESH_IP -> ErrorCode.REFRESH_RATE_LIMITED;
                default -> ErrorCode.PASSWORD_RATE_LIMITED;
            });
            return;
        }
        chain.doFilter(request, response);
    }
}
