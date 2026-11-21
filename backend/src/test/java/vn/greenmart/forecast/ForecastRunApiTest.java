package vn.greenmart.forecast;

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

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
class ForecastRunApiTest {
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
    @MockitoBean ForecastClient forecasts;

    @Test @WithMockUser(roles="ADMIN")
    void runningConflictUsesRealLookupAndReturnsContractRunId() throws Exception {
        long runId = jdbc.queryForObject("""
                INSERT INTO forecast_run(status,model_version) VALUES ('RUNNING','conflict-test') RETURNING id
                """, Long.class);
        try {
            assertUpstreamConflict("{\"code\":\"CONFLICT\"}", "FORECAST_RUNNING", runId);
        } finally {
            jdbc.update("DELETE FROM forecast_run WHERE id=?", runId);
        }
    }

    @Test @WithMockUser(roles="ADMIN")
    void emptyDailySalesReturnsContractNoDataError() throws Exception {
        assertUpstreamConflict("{\"code\":\"EMPTY_DAILY_SALES\"}", "FORECAST_NO_DATA", null);
    }

    private void assertUpstreamConflict(String body, String code, Long runId) throws Exception {
        long auditsBefore = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='FORECAST_RUN'", Long.class);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast/run", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var client = new ForecastClient(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture-only-token", jdbc)) {
            when(forecasts.start()).thenAnswer(invocation -> client.start());
            var response = mvc.perform(post("/api/v1/forecasts/runs"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
            if (runId == null) response.andExpect(jsonPath("$.details").doesNotExist());
            else response.andExpect(jsonPath("$.details.runId").value(runId));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='FORECAST_RUN'", Long.class))
                    .isEqualTo(auditsBefore);
            verify(forecasts, never()).get(anyLong());
        } finally {
            server.stop(0);
        }
    }

    @Test @WithMockUser(roles="ADMIN") void startUsesInternalClientNormalizesContractAndAudits() throws Exception {
        var json = new ObjectMapper();
        when(forecasts.start()).thenReturn(json.readTree("{\"runId\":901}"));
        when(forecasts.get(901)).thenReturn(json.readTree("{\"runId\":901,\"status\":\"RUNNING\",\"modelVersion\":\"demo\"}"));
        mvc.perform(post("/api/v1/forecasts/runs")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(901)).andExpect(jsonPath("$.runId").doesNotExist());
        verify(forecasts).start();
        verify(forecasts).get(901);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='FORECAST_RUN' AND entity_id='901'",Integer.class)).isEqualTo(1);
    }
    @Test @WithMockUser(roles="OWNER") void readUsesInternalClientAndPreservesStatus() throws Exception {
        when(forecasts.get(902)).thenReturn(new ObjectMapper().readTree("{\"runId\":902,\"status\":\"DONE\"}"));
        mvc.perform(get("/api/v1/forecasts/runs/902")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(902)).andExpect(jsonPath("$.status").value("DONE"));
    }
    @Test @WithMockUser(roles="CASHIER") void cashierCannotStart() throws Exception {
        mvc.perform(post("/api/v1/forecasts/runs")).andExpect(status().isForbidden());
        verifyNoInteractions(forecasts);
    }
}
