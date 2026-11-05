package vn.greenmart.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityErrors;
import vn.greenmart.identity.service.AuthService;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokens tokens;
    private final AuthService auth;
    private final ObjectMapper mapper;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        if (path.isEmpty()) path=request.getRequestURI();
        return path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/refresh")
                || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header=request.getHeader("Authorization");
        if (header!=null) {
            try {
                if (!header.regionMatches(true,0,"Bearer ",0,7)) throw new JwtException("Bearer required");
                var jwt=tokens.decode(header.substring(7));
                long id=Long.parseLong(jwt.getSubject());
                String role=auth.activeRole(id);
                if (!role.equals(jwt.getClaimAsString("role"))) throw new JwtException("Role changed");
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(id,null,
                        List.of(new SimpleGrantedAuthority("ROLE_"+role))));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException | AppException ex) {
                SecurityContextHolder.clearContext();
                SecurityErrors.write(response,mapper,ErrorCode.UNAUTHORIZED); return;
            }
        }
        chain.doFilter(request,response);
    }
}
