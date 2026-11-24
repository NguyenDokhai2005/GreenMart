package vn.greenmart.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
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
@Import({ChannelDelivery.class, vn.greenmart.integration.delivery.ChannelRecovery.class,
        vn.greenmart.integration.delivery.SyncLogService.class,
        vn.greenmart.integration.channel.ChannelSignatures.class, OutboxService.class, ChannelDeliveryTest.Json.class})
@Testcontainers
@DirtiesContext
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
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
    @Autowired vn.greenmart.integration.delivery.ChannelRecovery recovery;
    @Autowired vn.greenmart.integration.delivery.SyncLogService syncLogs;

    static final java.util.concurrent.atomic.AtomicInteger httpStatus = new java.util.concurrent.atomic.AtomicInteger(503);
    static final java.util.concurrent.atomic.AtomicInteger orderStatus = new java.util.concurrent.atomic.AtomicInteger(-1);
    static final java.util.concurrent.atomic.AtomicReference<Runnable> probeHook = new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<String> signature = new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final com.sun.net.httpserver.HttpServer server = startHttp();
    @org.junit.jupiter.api.AfterAll static void stopHttp() { server.stop(0); }
    @org.junit.jupiter.api.BeforeEach void resetHttp() {
        // This class owns its isolated Testcontainers database. Commit fixtures so recovery's short
        // independent transactions and real concurrency are exercised, rather than joining a test tx.
        jdbc.update("DELETE FROM channel_delivery");
        jdbc.update("DELETE FROM sync_log");
        jdbc.update("DELETE FROM channel_stock_snapshot");
        jdbc.update("DELETE FROM outbox_event");
        openProbeGate();
        httpStatus.set(503);
        orderStatus.set(-1);
        probeHook.set(null);
        calls.set(0);
        signature.set(null);
        bodies.clear();
    }

    static com.sun.net.httpserver.HttpServer startHttp() {
        try {
            var fixture = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            fixture.createContext("/mock/WEB/", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    signature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
                    String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                    bodies.add(body);
                    if ("{\"items\":[]}".equals(body) && probeHook.get()!=null) probeHook.get().run();
                    int status=exchange.getRequestURI().getPath().contains("/orders/") && orderStatus.get()>=0
                            ? orderStatus.get() : httpStatus.get();
                    exchange.sendResponseHeaders(status, -1);
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

    @Test void recoversAfterOutageAndGatesRepeatedProbes() throws Exception {
        exhaust("201");
        assertThat(recovery.recover("WEB")).isZero();
        httpStatus.set(204);
        assertThat(recovery.recover("WEB")).isZero(); // persisted 30s gate
        openProbeGate();
        assertThat(recovery.recover("WEB")).isEqualTo(1);
        assertThat(bodies.getLast()).isEqualTo("{\"items\":[]}");
        assertThat(recovery.recover("WEB")).isZero();
        publisher.deliverOne();
        assertThat(jdbc.queryForMap("SELECT status,attempts FROM sync_log"))
                .containsEntry("status","SUCCESS").containsEntry("attempts",7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_delivery",Integer.class)).isEqualTo(1);
    }

    @Test void doesNotAutomaticallyRetryPermanentFailureButAllowsManualRetry() throws Exception {
        httpStatus.set(401);
        publisher.accept(event("202")); publisher.deliverOne();
        assertThat(recovery.recover("WEB")).isZero();
        assertThat(calls.get()).isEqualTo(1);
        syncLogs.retry(jdbc.queryForObject("SELECT id FROM sync_log",Long.class));
        httpStatus.set(204); publisher.deliverOne();
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log",String.class)).isEqualTo("SUCCESS");
    }

    @Test void recoveredStockUsesNewestVersionWhileOriginalPayloadIsRetained() throws Exception {
        exhaust("203");
        var newer=event("204");
        newer=new Message(new String(newer.getBody(),StandardCharsets.UTF_8)
                .replace("\"version\":1","\"version\":9").replace("\"available\":1","\"available\":77")
                .getBytes(StandardCharsets.UTF_8),newer.getMessageProperties());
        publisher.accept(newer);
        httpStatus.set(204); publisher.deliverOne();
        assertThat(recovery.recover("WEB")).isEqualTo(1);
        publisher.deliverOne();
        assertThat(json.readTree(bodies.getLast()).path("items").get(0).path("version").asInt()).isEqualTo(9);
        assertThat(json.readTree(bodies.getLast()).path("items").get(0).path("available").asInt()).isEqualTo(77);
        assertThat(jdbc.queryForObject("SELECT body->'items'->0->>'version' FROM channel_delivery WHERE event_id='203'",String.class)).isEqualTo("1");
    }

    @Test void lifecycleEventsWaitForEarlierFailedDelivery() throws Exception {
        publisher.accept(statusEvent("205","CONFIRMED"));
        exhaustExisting();
        publisher.accept(statusEvent("206","PAID"));
        httpStatus.set(204);
        publisher.deliverOne();
        assertThat(calls.get()).isEqualTo(6); // later lifecycle callback is blocked
        assertThat(recovery.recover("WEB")).isEqualTo(1);
        publisher.deliverOne(); publisher.deliverOne();
        assertThat(bodies.get(bodies.size()-2)).contains("CONFIRMED");
        assertThat(bodies.getLast()).contains("PAID");
    }

    @Test void recoveryIsBoundedAndOverdueFailuresAreFlaggedWithoutDeletion() throws Exception {
        for (int n=0;n<21;n++) publisher.accept(event("batch-"+n));
        jdbc.update("UPDATE sync_log SET status='FAILED',error='HTTP 503',attempts=6");
        jdbc.update("UPDATE channel_delivery SET next_attempt_at=NULL,first_failed_at=clock_timestamp()-interval '25 hours'");
        httpStatus.set(204);
        assertThat(recovery.recover("WEB")).isEqualTo(20);
        assertThat(recovery.recover("WEB")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_delivery WHERE last_recovery_warning_at IS NOT NULL",Integer.class)).isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_log WHERE status='FAILED'",Integer.class)).isEqualTo(1);
        openProbeGate();
        assertThat(recovery.recover("WEB")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_delivery",Integer.class)).isEqualTo(21);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void manualAndAutomaticRecoveryRaceQueuesOnlyOneDelivery() throws Exception {
        exhaust("race-419");
        long id=jdbc.queryForObject("SELECT sync_log_id FROM channel_delivery WHERE event_id='race-419'",Long.class);
        httpStatus.set(204);
        var start=new java.util.concurrent.CountDownLatch(1);
        try (var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var auto=threads.submit(() -> { start.await(); return recovery.recover("WEB"); });
            var manual=threads.submit(() -> {
                start.await();
                try { syncLogs.retry(id); }
                catch (vn.greenmart.common.exception.AppException ex) {
                    assertThat(ex.getCode()).isEqualTo("INVALID_STATUS");
                }
                return null;
            });
            start.countDown();
            auto.get(15,java.util.concurrent.TimeUnit.SECONDS);
            manual.get(15,java.util.concurrent.TimeUnit.SECONDS);
            int before=calls.get();
            publisher.deliverOne(); publisher.deliverOne();
            assertThat(calls.get()).isEqualTo(before+1);
            assertThat(jdbc.queryForMap("SELECT status,attempts FROM sync_log WHERE id=?",id))
                    .containsEntry("status","SUCCESS").containsEntry("attempts",7);
        } finally {
            jdbc.update("DELETE FROM channel_delivery WHERE event_id='race-419'");
            jdbc.update("DELETE FROM sync_log WHERE id=?",id);
            jdbc.update("DELETE FROM outbox_event WHERE event_type='channel.delivery.failed' AND payload->>'eventId'='race-419'");
            openProbeGate();
        }
    }

    @Test void healthyStockProbeDoesNotRetryBrokenOrderEndpointForever() throws Exception {
        httpStatus.set(204); // stock/probe is healthy, but this order's endpoint remains broken
        orderStatus.set(503);
        publisher.accept(statusEvent("bounded-419","PAID"));
        exhaustExisting();
        for (int cycle=1;cycle<=3;cycle++) {
            openProbeGate();
            assertThat(recovery.recover("WEB")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log",Integer.class)).isEqualTo(6*cycle);
            assertThat(jdbc.queryForObject("SELECT recovery_count FROM channel_delivery",Integer.class)).isEqualTo(cycle);
            for (int attempt=1;attempt<=6;attempt++) {
                jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'");
                publisher.deliverOne();
                assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log",Integer.class)).isEqualTo(6*cycle+attempt);
                assertThat(jdbc.queryForObject("SELECT cycle_attempts FROM channel_delivery",Integer.class)).isEqualTo(attempt);
                assertThat(jdbc.queryForObject("SELECT status FROM sync_log",String.class))
                        .isEqualTo(attempt==6 ? "FAILED" : "RETRYING");
            }
        }
        int before=calls.get();
        for (int n=0;n<4;n++) {
            openProbeGate();
            assertThat(recovery.recover("WEB")).isZero();
            publisher.deliverOne();
        }
        assertThat(calls.get()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL FROM channel_delivery",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log",Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='channel.delivery.failed'",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT body->>'status' FROM channel_delivery",String.class)).isEqualTo("PAID");

        // Exhausting auto recovery must not disable the explicit ADMIN recovery path.
        syncLogs.retry(jdbc.queryForObject("SELECT id FROM sync_log",Long.class));
        assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log",Integer.class)).isEqualTo(24);
        orderStatus.set(204);
        publisher.deliverOne();
        assertThat(jdbc.queryForMap("SELECT status,attempts FROM sync_log"))
                .containsEntry("status","SUCCESS").containsEntry("attempts",25);
        assertThat(jdbc.queryForObject("SELECT recovery_count FROM channel_delivery",Integer.class)).isEqualTo(3);
    }

    @Test void slowProbeDoesNotHoldJobLocksAndManualRetryWins() throws Exception {
        exhaust("slow-probe-419");
        httpStatus.set(204);
        var probing=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        probeHook.set(() -> {
            probing.countDown();
            try {
                if (!release.await(4,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Probe test timed out");
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        try (var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var auto=threads.submit(() -> recovery.recover("WEB"));
            try {
                assertThat(probing.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(recovery.recover("WEB")).isZero(); // gate was committed before HTTP
                long id=jdbc.queryForObject("SELECT id FROM sync_log",Long.class);
                threads.submit(() -> syncLogs.retry(id)).get(2,java.util.concurrent.TimeUnit.SECONDS);
            } finally { release.countDown(); }
            assertThat(auto.get(5,java.util.concurrent.TimeUnit.SECONDS)).isZero();
            int before=calls.get();
            publisher.deliverOne(); publisher.deliverOne();
            assertThat(calls.get()).isEqualTo(before+1);
            assertThat(jdbc.queryForMap("SELECT status,attempts FROM sync_log"))
                    .containsEntry("status","SUCCESS").containsEntry("attempts",7);
            assertThat(jdbc.queryForObject("SELECT recovery_count FROM channel_delivery",Integer.class)).isZero();
        } finally { release.countDown(); probeHook.set(null); }
    }

    @Test void expiredProbeCannotDrainJobsFromNewerClaim() throws Exception {
        exhaust("expired-probe-419");
        httpStatus.set(204);
        probeHook.set(() -> jdbc.update("UPDATE channel_recovery SET probe_token=? WHERE channel_code='WEB'",
                java.util.UUID.randomUUID()));
        assertThat(recovery.recover("WEB")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT recovery_count FROM channel_delivery",Integer.class)).isZero();
    }

    private void exhaust(String id) throws Exception { publisher.accept(event(id)); exhaustExisting(); }
    private void exhaustExisting() {
        for (int n=0;n<6;n++) {
            jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'");
            publisher.deliverOne();
        }
    }
    private void openProbeGate() {
        jdbc.update("UPDATE channel_recovery SET next_probe_at=clock_timestamp()-interval '1 second'");
    }
    private Message statusEvent(String id,String status) {
        var props=new MessageProperties(); props.setMessageId(id); props.setReceivedRoutingKey("channel.order.status");
        return new Message(("{\"channelCode\":\"WEB\",\"externalId\":\"ORDER-1\",\"body\":{\"status\":\""+status+"\"}}")
                .getBytes(StandardCharsets.UTF_8),props);
    }

    private Message event(String id) {
        var props = new MessageProperties();
        props.setMessageId(id);
        props.setReceivedRoutingKey("channel.stock");
        String envelope = "{\"channelCode\":\"WEB\",\"body\":{\"items\":[{\"externalSku\":\"W-TEST\",\"available\":1,\"version\":1}]}}";
        return new Message(envelope.getBytes(StandardCharsets.UTF_8), props);
    }
}
