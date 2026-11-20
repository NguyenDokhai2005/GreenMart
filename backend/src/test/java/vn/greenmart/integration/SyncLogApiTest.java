package vn.greenmart.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
class SyncLogApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @org.junit.jupiter.api.BeforeEach
    void configureMvc() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
    @Autowired JdbcTemplate jdbc;

    @Test @WithMockUser(roles = "ADMIN")
    void failedDeliveryIsQueuedOnceAndFiltersValidate() throws Exception {
        long id = jdbc.queryForObject("INSERT INTO sync_log(channel_id,direction,status,attempts) "
                + "VALUES ((SELECT id FROM sales_channel WHERE code='WEB'),'OUT','FAILED',6) RETURNING id", Long.class);
        String event = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO channel_delivery(event_id,sync_log_id,routing_key,channel_code,body,trace_id,next_attempt_at) "
                + "VALUES (?,?,'channel.stock','WEB','{}'::jsonb,'test',NULL)", event,id);
        mvc.perform(get("/api/v1/integrations/sync-logs?channelCode=WEB&status=FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].channelCode").value("WEB"));
        mvc.perform(post("/api/v1/integrations/sync-logs/{id}/retry",id)).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/integrations/sync-logs/{id}/retry",id)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log WHERE id=?",String.class,id)).isEqualTo("RETRYING");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NOT NULL FROM channel_delivery WHERE sync_log_id=?",Boolean.class,id)).isTrue();
        mvc.perform(get("/api/v1/integrations/sync-logs?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/integrations/sync-logs?channelCode=UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/integrations/sync-logs/999999/retry")).andExpect(status().isNotFound());
    }
    @Test @WithMockUser(roles="OWNER") void ownerCanReadButNotRetry() throws Exception {
        mvc.perform(get("/api/v1/integrations/sync-logs")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/integrations/sync-logs/1/retry")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="CASHIER") void cashierCannotRead() throws Exception {
        mvc.perform(get("/api/v1/integrations/sync-logs")).andExpect(status().isForbidden());
    }
    @Test void anonymousCannotRead() throws Exception {
        mvc.perform(get("/api/v1/integrations/sync-logs")).andExpect(status().isUnauthorized());
    }
}
