package vn.greenmart.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db,redis",
        "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=1s", "greenmart.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class RedisOutageApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user",()->"forecast_ro");
        registry.add("spring.data.redis.host",REDIS::getHost);
        registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password",()->"");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;

    @Test void stoppedRedisBlocksAuthenticatedPosButLivenessRemainsAvailable() throws Exception {
        jdbc.execute("TRUNCATE app_user CASCADE");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(7,'outage.cashier',?,'Test Cashier','CASHIER')",
                passwords.encode("Demo@123"));
        String login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username","outage.cashier","password","Demo@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access=json.readTree(login).path("accessToken").asText();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isOk());
        // Only this test's Redis is stopped; no shared Compose service is touched.
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        String response=mvc.perform(post("/api/v1/orders/quote").header("Authorization","Bearer "+access)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(access,"RedisConnectionFailureException","Demo@123");
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
    }
}
