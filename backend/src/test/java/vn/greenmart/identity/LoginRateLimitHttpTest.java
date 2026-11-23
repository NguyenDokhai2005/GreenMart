package vn.greenmart.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.identity.repository.AuthUserRepository;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db,redis",
        "greenmart.security.login.trusted-proxies="
})
@Testcontainers
class LoginRateLimitHttpTest {
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

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AuthUserRepository users;

    @Test
    void realServletFilterBlocksBeforeAuthenticationEvenWithEncodedPathAndForgedHeaders() throws Exception {
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        String root = "http://127.0.0.1:" + port;
        for (int attempt = 0; attempt < 20; attempt++) {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Real-IP", "203.0.113." + attempt);
            headers.set("X-Forwarded-For", "198.51.100." + attempt);
            var entity = new HttpEntity<>("{\"username\":\"missing\",\"password\":\"wrong\"}", headers);
            assertThat(http.exchange(URI.create(root + "/api/v1/auth/login"), HttpMethod.POST, entity, String.class)
                    .getStatusCode().value()).isEqualTo(401);
        }
        clearInvocations(users);
        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/auth/%6cogin"}) {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = http.exchange(URI.create(root + path), HttpMethod.POST,
                    new HttpEntity<>("{broken", headers), String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getHeaders().getFirst("Retry-After")).isNotBlank();
            assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("LOGIN_RATE_LIMITED");
        }
        verifyNoInteractions(users);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='LOGIN_FAILED'", Integer.class))
                .isEqualTo(20);
    }
}
