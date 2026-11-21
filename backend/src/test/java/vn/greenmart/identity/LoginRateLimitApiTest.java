package vn.greenmart.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.identity.repository.AuthUserRepository;
import vn.greenmart.identity.security.AuthRateLimiter;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db,redis",
        "greenmart.security.login.trusted-proxies=127.0.0.1/32"
})
@AutoConfigureMockMvc
@Testcontainers
class LoginRateLimitApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean AuthUserRepository users;
    @MockitoSpyBean AuditLogRepository audit;
    @MockitoSpyBean AuthRateLimiter limiter;

    @BeforeEach
    void fixtures() {
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        jdbc.execute("TRUNCATE branch,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(1,'GM-001','Branch')");
        String hash = passwords.encode("Demo@123");
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role,is_active) VALUES
                (1,'owner',?,'Owner','OWNER',true),(2,'inactive',?,'Inactive','OWNER',false),
                (3,'locked',?,'Locked','OWNER',true)
                """, hash, hash, hash);
        jdbc.update("UPDATE app_user SET failed_login_count=5,locked_until=? WHERE id=3", OffsetDateTime.now().plusMinutes(15));
    }

    private MockHttpServletRequestBuilder request(String ip, String username, String password) throws Exception {
        return post("/api/v1/auth/login").with(value -> { value.setRemoteAddr(ip); return value; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", username, "password", password)));
    }

    private void fill(String ip) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(request(ip, "missing-" + attempt, "wrong")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void twentyFirstRequestStopsBeforeDatabaseAndBodyValidation() throws Exception {
        fill("192.0.2.1");
        clearInvocations(users, audit);
        var response = mvc.perform(post("/api/v1/auth/login")
                        .with(value -> { value.setRemoteAddr("192.0.2.1"); return value; })
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andReturn().getResponse();
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verifyNoInteractions(users, audit);
        mvc.perform(request("192.0.2.2", "missing", "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void validLoginsCountAndDoNotResetIpLimit() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(request("192.0.2.3", "owner", "Demo@123")).andExpect(status().isOk());
        }
        mvc.perform(request("192.0.2.3", "owner", "Demo@123")).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgedForwardingHeadersCannotRotateUntrustedClientIdentity() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(request("192.0.2.4", "missing", "wrong")
                    .header("X-Real-IP", "203.0.113." + attempt)
                    .header("X-Forwarded-For", "203.0.113." + attempt)).andExpect(status().isUnauthorized());
        }
        mvc.perform(request("192.0.2.4", "missing", "wrong").header("X-Real-IP", "203.0.113.250"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void trustedProxyPreservesSeparateClientBuckets() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(request("127.0.0.1", "missing", "wrong").header("X-Real-IP", "192.0.2.5"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(request("127.0.0.1", "missing", "wrong").header("X-Real-IP", "192.0.2.5"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(request("127.0.0.1", "missing", "wrong").header("X-Real-IP", "192.0.2.6"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void encodedLoginPathCannotBypassLimit() throws Exception {
        fill("192.0.2.7");
        clearInvocations(users, audit);
        mvc.perform(post(URI.create("/api/v1/auth/%6cogin"))
                        .with(value -> { value.setRemoteAddr("192.0.2.7"); return value; })
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(users, audit);
    }

    @Test
    void publicLoginErrorsFollowLeadsLockoutContractWithoutLeakingInactiveStatus() throws Exception {
        for (var credentials : new String[][]{{"missing", "wrong"}, {"owner", "wrong"},
                {"inactive", "Demo@123"}, {"locked", "Demo@123"}}) {
            var response = mvc.perform(request("192.0.2.8", credentials[0], credentials[1]))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse();
            var error = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(response.getContentAsString());
            assertThat(error.path("traceId").asText()).isNotBlank();
            if("locked".equals(credentials[0])) {
                assertThat(error.path("code").asText()).isEqualTo("ACCOUNT_LOCKED");
                assertThat(error.at("/details/lockedUntil").asText()).isNotBlank();
            } else {
                assertThat(error.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
                assertThat(error.path("message").asText()).isEqualTo("Sai tên đăng nhập hoặc mật khẩu");
                if("owner".equals(credentials[0])) assertThat(error.at("/details/remainingAttempts").asInt()).isEqualTo(4);
                else assertThat(error.has("details")).isFalse();
            }
        }
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM app_user WHERE id=3", Integer.class)).isEqualTo(5);
    }

    @Test
    void storeFailureReturnsUnavailableBeforeDatabaseAndOtherRoutesStillWork() throws Exception {
        doThrow(new RedisConnectionFailureException("private Redis diagnostic"))
                .when(limiter).retryAfterSeconds(anyString());
        clearInvocations(users, audit);
        var response = mvc.perform(request("192.0.2.9", "owner", "Demo@123"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private Redis diagnostic");
        verifyNoInteractions(users, audit);
        clearInvocations(limiter);
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        verifyNoInteractions(limiter);
    }
}
