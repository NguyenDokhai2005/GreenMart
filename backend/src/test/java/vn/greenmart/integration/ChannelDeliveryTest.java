package vn.greenmart.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.outbox.OutboxService;

import static org.assertj.core.api.Assertions.assertThat;
import vn.greenmart.integration.delivery.ChannelDelivery;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChannelDelivery.class, vn.greenmart.integration.channel.ChannelSignatures.class, OutboxService.class, ChannelDeliveryTest.Json.class})
@Testcontainers
@DirtiesContext
class ChannelDeliveryTest {
    @TestConfiguration static class Json {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("MOCK_CONNECTOR_URL", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        r.add("CHANNEL_WEB_SECRET", () -> "channel-delivery-test-secret");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelDelivery publisher;
    @Autowired ObjectMapper json;

    static final java.util.concurrent.atomic.AtomicInteger httpStatus = new java.util.concurrent.atomic.AtomicInteger(503);
    static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<String> signature = new java.util.concurrent.atomic.AtomicReference<>();
    static final com.sun.net.httpserver.HttpServer server = startHttp();
    @org.junit.jupiter.api.AfterAll static void stopHttp() { server.stop(0); }
    @org.junit.jupiter.api.BeforeEach void resetHttp() {
        httpStatus.set(503);
        calls.set(0);
        signature.set(null);
    }

    static com.sun.net.httpserver.HttpServer startHttp() {
        try {
            var fixture = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            fixture.createContext("/mock/WEB/stock", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    signature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(httpStatus.get(), -1);
                }
            });
            fixture.start();
            return fixture;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test void retriesFiveTimesThenRecordsFailureAndQueuesDeadLetter() throws Exception {
        publisher.accept(event("100"));
        publisher.accept(event("100"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_delivery", Integer.class)).isEqualTo(1);
        int[] delays = {1,2,4,8,16};
        for (int attempt = 1; attempt <= delays.length + 1; attempt++) {
            publisher.deliverOne();
            assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log", Integer.class)).isEqualTo(attempt);
            if (attempt <= delays.length) {
                assertThat(jdbc.queryForObject("SELECT status FROM sync_log", String.class)).isEqualTo("RETRYING");
                double remaining = jdbc.queryForObject("""
                        SELECT extract(epoch FROM (next_attempt_at - clock_timestamp())) FROM channel_delivery
                        """, Double.class);
                assertThat(remaining).isBetween(delays[attempt - 1] - 0.75, (double) delays[attempt - 1]);
                jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'");
            }
        }
        assertThat(calls.get()).isEqualTo(6);
        assertThat(signature.get()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'eventId' FROM outbox_event WHERE event_type='channel.delivery.failed'
                """, String.class)).isEqualTo("100");
        publisher.deliverOne();
        assertThat(calls.get()).isEqualTo(6);
    }

    @Test void retriesTransientFailureButNotInvalidSignature() throws Exception {
        publisher.accept(event("101"));
        publisher.deliverOne();
        jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE event_id='101'");
        httpStatus.set(204);
        publisher.deliverOne();
        assertThat(jdbc.queryForMap("SELECT attempts,status FROM sync_log WHERE id=(SELECT sync_log_id FROM channel_delivery WHERE event_id='101')"))
                .containsEntry("attempts", 2).containsEntry("status", "SUCCESS");
        httpStatus.set(401);
        publisher.accept(event("102"));
        publisher.deliverOne();
        assertThat(jdbc.queryForMap("SELECT attempts,status FROM sync_log WHERE id=(SELECT sync_log_id FROM channel_delivery WHERE event_id='102')"))
                .containsEntry("attempts", 1).containsEntry("status", "FAILED");
        publisher.deliverOne();
        assertThat(calls.get()).isEqualTo(3);
    }

    private Message event(String id) {
        var props = new MessageProperties();
        props.setMessageId(id);
        props.setReceivedRoutingKey("channel.stock");
        String envelope = "{\"channelCode\":\"WEB\",\"body\":{\"items\":[{\"externalSku\":\"W-TEST\",\"available\":1,\"version\":1}]}}";
        return new Message(envelope.getBytes(StandardCharsets.UTF_8), props);
    }
}
