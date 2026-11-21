package vn.greenmart.forecast;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.greenmart.common.exception.AppException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ForecastClientTest {
    @Test void runningConflictIncludesRunIdFromDatabase() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(java.util.List.of(901L));
        assertConflict("{\"code\":\"CONFLICT\",\"message\":\"private-upstream-detail\"}",
                jdbc, "FORECAST_RUNNING", 409, java.util.Map.of("runId", 901L));
        verify(jdbc).queryForList(contains("WHERE status='RUNNING'"), eq(Long.class));
    }

    @Test void emptyDailySalesIsDistinctFromRunningConflict() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        assertConflict("{\"code\":\"EMPTY_DAILY_SALES\"}", jdbc, "FORECAST_NO_DATA", 409, null);
        verifyNoInteractions(jdbc);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"code\":\"OTHER\",\"message\":\"private-upstream-detail\"}",
            "not-json", "null", "[]", "{}"
    })
    void unexpectedConflictBodyIsSanitized(String body) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        assertConflict(body, jdbc, "FORECAST_UNAVAILABLE", 503, null);
        verifyNoInteractions(jdbc);
    }

    @Test void runningConflictWithoutCurrentRunDoesNotInventAnId() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(java.util.List.of());
        assertConflict("{\"code\":\"CONFLICT\"}", jdbc, "FORECAST_UNAVAILABLE", 503, null);
    }

    @Test void databaseFailureIsSanitized() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("private-database-detail"));
        assertConflict("{\"code\":\"CONFLICT\"}", jdbc, "FORECAST_UNAVAILABLE", 503, null);
    }

    private void assertConflict(String body, JdbcTemplate jdbc, String code, int status, Object details) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast/run", exchange -> {
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, raw.length);
            exchange.getResponseBody().write(raw);
            exchange.close();
        });
        server.start();
        String token = java.util.UUID.randomUUID().toString();
        try (var client = new ForecastClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), token, jdbc)) {
            assertThatThrownBy(client::start).isInstanceOfSatisfying(AppException.class, ex -> {
                assertThat(ex.getCode()).isEqualTo(code);
                assertThat(ex.getStatus().value()).isEqualTo(status);
                assertThat(ex.getDetails()).isEqualTo(details);
            }).hasMessageNotContaining(token).hasMessageNotContaining("private-");
        } finally {
            server.stop(0);
        }
    }

    @Test void sendsBearerOnBothOperationsAndSanitizesUnauthorizedResponse() throws Exception {
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean rejected = exchange.getRequestURI().getPath().endsWith("/99");
            byte[] body = (rejected ? "secret-upstream-detail" : "{\"runId\":1}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(rejected ? 401 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            String token = java.util.UUID.randomUUID().toString();
            var jdbc = mock(JdbcTemplate.class);
            try (var client = new ForecastClient(base, token, jdbc); var missing = new ForecastClient(base, "", jdbc)) {
            assertThat(client.start().path("runId").asInt()).isEqualTo(1);
            assertThat(authorization.get()).isEqualTo("Bearer " + token);
            assertThat(client.get(1).path("runId").asInt()).isEqualTo(1);
            assertThat(authorization.get()).isEqualTo("Bearer " + token);
            assertThatThrownBy(() -> client.get(99)).isInstanceOfSatisfying(AppException.class,
                    ex -> assertThat(ex.getStatus().value()).isEqualTo(503))
                    .hasMessageNotContaining("secret-upstream-detail").hasMessageNotContaining(token);
            assertThatThrownBy(() -> missing.start())
                    .isInstanceOf(AppException.class);
            }
        } finally { server.stop(0); }
    }
}
