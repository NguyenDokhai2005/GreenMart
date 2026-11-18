package vn.greenmart.forecast;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.greenmart.common.exception.AppException;
import static org.assertj.core.api.Assertions.*;

class ForecastClientTest {
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
            try (var client = new ForecastClient(base, token); var missing = new ForecastClient(base, "")) {
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
