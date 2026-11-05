package vn.greenmart.integration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.outbox.OutboxRelay;
import vn.greenmart.integration.outbox.OutboxService;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    "greenmart.outbox.enabled=false", "management.endpoint.health.group.readiness.include=readinessState,db",
    "spring.rabbitmq.listener.simple.retry.initial-interval=10ms",
    "spring.rabbitmq.listener.simple.retry.max-interval=20ms"
})
@Testcontainers
@DirtiesContext
@Import(OutboxIntegrationTest.ConsumerConfig.class)
class OutboxIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:3.13.7-management-alpine")
            .withExposedPorts(5672).withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "test");
        r.add("spring.rabbitmq.password", () -> "test");
        r.add("spring.data.redis.password", () -> "test");
    }
    @Autowired OutboxService outbox;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired PlatformTransactionManager manager;
    @Autowired PoisonConsumer poison;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM outbox_event");
        var admin = new RabbitAdmin(rabbitTemplate);
        admin.purgeQueue("greenmart.integration.events");
        admin.purgeQueue("greenmart.integration.dlq");
    }

    long enqueue(String type) {
        return new TransactionTemplate(manager).execute(tx -> outbox.enqueue("ORDER", "123", type, Map.of("id", 123)));
    }

    @Test void businessRollbackRemovesEventAndTransactionIsRequired() {
        assertThatThrownBy(() -> outbox.enqueue("ORDER", "1", "order.created", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            outbox.enqueue("ORDER", "1", "order.created", Map.of());
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test void confirmedPersistentEventBecomesSent() {
        long id;
        try (var ignored = MDC.putCloseable("traceId", "trace-127")) { id = enqueue("order.completed"); }
        assertThat(relay.publishNext()).isTrue();
        assertThat(status(id)).isEqualTo("SENT");
        Message message = rabbitTemplate.receive("greenmart.integration.events", 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(Long.toString(id));
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getHeaders()).containsEntry("X-Correlation-ID", "trace-127");
    }

    @Test void unroutableEventRemainsPending() {
        long id = enqueue("unrouted.event");
        relay.publishNext();
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void brokerOutageKeepsPendingAndRecoverySendsIt() throws Exception {
        long id = enqueue("stock.changed");
        rabbit.execInContainer("rabbitmqctl", "stop_app");
        try {
            relay.publishNext();
            assertThat(status(id)).isEqualTo("PENDING");
        } finally {
            assertThat(rabbit.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            jdbc.update("UPDATE outbox_event SET next_attempt_at=now() WHERE id=?", id);
            relay.publishNext();
            assertThat(status(id)).isEqualTo("SENT");
        });
    }

    @Test void poisonMessageRetriesFiveTimesThenReachesDlq() {
        poison.attempts.set(0);
        rabbitTemplate.convertAndSend("greenmart.events", "test.poison", "poison");
        Message dead = rabbitTemplate.receive("greenmart.integration.dlq", 10000);
        assertThat(dead).isNotNull();
        assertThat(poison.attempts.get()).isEqualTo(5);
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    String status(long id) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, id); }

    @TestConfiguration static class ConsumerConfig {
        @Bean Declarables failureQueue() {
            var queue = QueueBuilder.durable("greenmart.test.failure")
                    .deadLetterExchange("greenmart.dlx").deadLetterRoutingKey("integration.failed").build();
            return new Declarables(queue, new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                    "greenmart.events", "test.poison", Map.of()));
        }
        @Bean PoisonConsumer poisonConsumer() { return new PoisonConsumer(); }
    }
    static class PoisonConsumer {
        final AtomicInteger attempts = new AtomicInteger();
        @RabbitListener(queues = "greenmart.test.failure") public void receive(Message message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("Test consumer failure");
        }
    }
}
