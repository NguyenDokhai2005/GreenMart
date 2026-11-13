package vn.greenmart.sales.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.dto.OrderItemResponse;
import vn.greenmart.sales.dto.OrderLineRequest;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PosOrderRequest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "greenmart.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class PosOrderDeadlockTest {
    private static final long BRANCH = 81L;
    private static final long CASHIER = 810L;
    private static final long PRODUCT_A = 81L;
    private static final long PRODUCT_B = 82L;
    private static final int ROUNDS = 50;
    private static final int INITIAL_STOCK = ROUNDS * 2;
    private static final long ORDER_TOTAL = 20_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE branch, category, app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES (?, 'B81', 'Branch 81')", BRANCH);
        jdbc.update("INSERT INTO sales_channel(code,name) VALUES ('POS','POS')");
        jdbc.update("INSERT INTO category(id,name) VALUES (81,'Test')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES
                (?, 'SKU-A', 'A', 'hop', 81, 10000, 5000),
                (?, 'SKU-B', 'B', 'hop', 81, 10000, 5000)
                """, PRODUCT_A, PRODUCT_B);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role)
                VALUES (?, 'cashier', 'hash', 'Cashier', 'CASHIER')
                """, CASHIER);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES (?,?)", CASHIER, BRANCH);
        for (long product : List.of(PRODUCT_A, PRODUCT_B)) {
            jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES (?,?,?,0)",
                    BRANCH, product, INITIAL_STOCK);
        }
    }

    @Test
    void fiftyConcurrentPairsWithReversedItemsWriteStockInAscendingProductOrder() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                var start = new CyclicBarrier(2);
                var forward = workers.submit(() -> sell(start, PRODUCT_A, PRODUCT_B));
                var reverse = workers.submit(() -> sell(start, PRODUCT_B, PRODUCT_A));

                var forwardOrder = forward.get(30, TimeUnit.SECONDS);
                var reverseOrder = reverse.get(30, TimeUnit.SECONDS);
                assertOrder(forwardOrder, round, PRODUCT_A, PRODUCT_B);
                assertOrder(reverseOrder, round, PRODUCT_B, PRODUCT_A);
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(25, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE status='COMPLETED'", Integer.class))
                .isEqualTo(ROUNDS * 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class))
                .isEqualTo(ROUNDS * 4);
        for (long product : List.of(PRODUCT_A, PRODUCT_B)) {
            var versions = jdbc.queryForList("""
                    SELECT (payload->>'version')::bigint FROM outbox_event
                    WHERE event_type='stock.changed' AND aggregate_id=?
                    ORDER BY id
                    """, Long.class, BRANCH + ":" + product);
            assertThat(versions).containsExactlyElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, ROUNDS * 2L).boxed().toList());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='stock.changed'", Integer.class))
                .isEqualTo(ROUNDS * 4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='order.completed'", Integer.class))
                .isEqualTo(ROUNDS * 2);
        assertThat(jdbc.queryForList("SELECT on_hand FROM inventory ORDER BY product_id", Integer.class))
                .containsExactly(0, 0);
    }

    private OrderResponse sell(CyclicBarrier start, long first, long second) throws Exception {
        var request = new PosOrderRequest(BRANCH, null,
                List.of(new OrderLineRequest(first, 1), new OrderLineRequest(second, 1)),
                null, new PaymentRequest(PaymentMethod.CASH, ORDER_TOTAL, null), ORDER_TOTAL);
        var httpRequest = post("/api/v1/orders/pos")
                .with(user(Long.toString(CASHIER)).roles("CASHIER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request));

        awaitBothRequests(start);
        var response = mvc.perform(httpRequest).andExpect(status().isCreated()).andReturn().getResponse();
        return mapper.readValue(response.getContentAsByteArray(), OrderResponse.class);
    }

    private void assertOrder(OrderResponse order, int round, long first, long second) {
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.items()).extracting(OrderItemResponse::productId).containsExactly(first, second);

        // The branch order counter can serialize requests before stock writes.
        // Check the ledger sequence so that serialization cannot hide reversed stock locks.
        assertThat(jdbc.queryForList("""
                SELECT product_id FROM inventory_transaction
                WHERE branch_id=? AND type='SALE' AND ref_type='ORDER' AND ref_id=?
                ORDER BY id
                """, Long.class, BRANCH, order.id()))
                .as("round %s, order %s, input [%s, %s]", round, order.id(), first, second)
                .containsExactly(PRODUCT_A, PRODUCT_B);
    }

    private static void awaitBothRequests(CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to send both POS orders", error);
        } catch (BrokenBarrierException | TimeoutException error) {
            throw new IllegalStateException("Both POS requests must be ready before dispatch", error);
        }
    }
}
