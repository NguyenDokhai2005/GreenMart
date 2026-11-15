package vn.greenmart.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.identity.security.JwtTokens;
import vn.greenmart.identity.security.JwtAuthenticationFilter;
import vn.greenmart.identity.service.AuthService;

@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokens tokens, AuthService service, ObjectMapper mapper) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable)
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request,response,ex)->SecurityErrors.write(response,mapper,ErrorCode.UNAUTHORIZED))
                    .accessDeniedHandler((request,response,ex)->SecurityErrors.write(response,mapper,ErrorCode.FORBIDDEN)))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.POST, "/api/v1/integrations/*/orders").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/integrations/*/orders/*/events").permitAll()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/integrations/*/products/*").hasRole("ADMIN")
                    .requestMatchers("/api/v1/auth/login","/api/v1/auth/refresh","/actuator/health","/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/reports/**").hasAnyRole("OWNER", "BRANCH_MANAGER")
                    .requestMatchers("/api/v1/inventory/transactions").hasAnyRole("OWNER", "BRANCH_MANAGER", "STOREKEEPER")
                    .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(tokens,service,mapper),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
