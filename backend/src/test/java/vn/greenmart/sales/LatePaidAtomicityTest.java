package vn.greenmart.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.service.OnlineOrderService;
import vn.greenmart.sales.service.PosIdempotencyService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "greenmart.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class LatePaidAtomicityTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ChannelSignatures signatures;
    @Autowired OnlineOrderService orders;
    @MockitoBean PosIdempotencyService posIdempotency;

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user,promotion CASCADE");
        jdbc.execute("TRUNCATE audit_log,outbox_event");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(428,'GM-428','Atomicity')");
        jdbc.update("INSERT INTO category(id,name) VALUES(428,'Atomicity')");
        jdbc.update("INSERT INTO sales_channel(code,name,safety_stock,webhook_secret) VALUES('WEB','Web',0,'test-only-428-webhook-secret')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES
                (4281,'ATOMIC-1','First','hop',428,10000,5000),(4282,'ATOMIC-2','Second','hop',428,10000,5000)
                """);
        jdbc.update("""
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,version) VALUES
                (428,4281,5,0,1),(428,4282,5,0,1)
                """);
    }

    @AfterEach void removeFault() {
        jdbc.execute("DROP FUNCTION IF EXISTS issue428_fault() CASCADE");
    }

    private long expiredOrder(String externalId) {
        long id = jdbc.queryForObject("""
                INSERT INTO sales_order(code,channel_id,external_order_id,branch_id,status,subtotal,total,reserved_until)
                SELECT ?,id,?,428,'RESERVED',40000,40000,now()-interval '1 minute'
                FROM sales_channel WHERE code='WEB' RETURNING id
                """, Long.class, "WEB-" + externalId, externalId);
        jdbc.update("""
                INSERT INTO order_item(order_id,product_id,quantity,unit_price,line_total) VALUES
                (?,4281,2,10000,20000),(?,4282,2,10000,20000)
                """, id, id);
        jdbc.update("UPDATE inventory SET reserved=2 WHERE branch_id=428");
        assertThat(orders.expireOverdueOrders()).isEqualTo(1);
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertReserved(0);
        return id;
    }

    private MockHttpServletRequestBuilder paid(String externalId) throws Exception {
        String body = json.writeValueAsString(Map.of("type", "PAID", "occurredAt", OffsetDateTime.now().toString(),
                "providerRef", "PAY-" + externalId));
        return post("/api/v1/integrations/WEB/orders/{id}/events", externalId)
                .header("X-Signature", signatures.sign("WEB", body.getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?", String.class, id);
    }
    private void assertReserved(int expected) {
        assertThat(jdbc.queryForList("SELECT reserved FROM inventory WHERE branch_id=428 ORDER BY product_id", Integer.class))
                .containsExactly(expected, expected);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void failAt(String stage, long orderId) {
        String table;
        String condition;
        String operation = "INSERT";
        switch (stage) {
            case "payment" -> { table = "payment"; condition = "NEW.order_id=" + orderId; }
            case "order" -> { table = "sales_order"; operation = "UPDATE"; condition = "NEW.id=" + orderId + " AND NEW.status='CONFIRMED'"; }
            case "outbox" -> { table = "outbox_event"; condition = "NEW.aggregate_type='order' AND NEW.aggregate_id='" + orderId + "' AND NEW.event_type='order.confirmed'"; }
            case "ledger" -> { table = "inventory_transaction"; condition = "NEW.ref_id=" + orderId + " AND NEW.type='RESERVE' AND NEW.product_id=4282"; }
            case "audit" -> { table = "audit_log"; condition = "NEW.entity='order' AND NEW.entity_id='" + orderId + "'"; }
            default -> throw new IllegalArgumentException(stage);
        }
        // Fault injection is restricted to this order in this test's disposable PostgreSQL container.
        jdbc.execute("CREATE FUNCTION issue428_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + condition
                + " THEN RAISE EXCEPTION 'issue428 injected failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER issue428_fault BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION issue428_fault()");
    }

    @ParameterizedTest @ValueSource(strings = {"payment", "order", "outbox", "ledger"})
    void failureAfterReReserveRollsBackAllEffectsAndRetrySucceeds(String stage) throws Exception {
        long id = expiredOrder("FAIL-" + stage);
        var inventory = jdbc.queryForList("SELECT * FROM inventory WHERE branch_id=428 ORDER BY product_id");
        int ledger = count("inventory_transaction"), outbox = count("outbox_event"), audit = count("audit_log");
        failAt(stage, id);
        mvc.perform(paid("FAIL-" + stage)).andExpect(status().isInternalServerError());
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForList("SELECT * FROM inventory WHERE branch_id=428 ORDER BY product_id")).isEqualTo(inventory);
        assertThat(count("inventory_transaction")).isEqualTo(ledger);
        assertThat(count("outbox_event")).isEqualTo(outbox);
        assertThat(count("audit_log")).isEqualTo(audit);
        assertThat(count("payment")).isZero();
        removeFault();
        mvc.perform(paid("FAIL-" + stage)).andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("CONFIRMED");
        assertReserved(2);
        assertThat(count("payment")).isEqualTo(1);
    }

    @Test void secondSkuShortageDoesNotReserveTheFirstAndRefundIsAtomic() throws Exception {
        long id = expiredOrder("SHORT");
        jdbc.update("UPDATE inventory SET on_hand=1 WHERE branch_id=428 AND product_id=4282");
        int ledger = count("inventory_transaction");
        failAt("audit", id);
        mvc.perform(paid("SHORT")).andExpect(status().isInternalServerError());
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertReserved(0);
        assertThat(count("payment")).isZero();
        assertThat(count("inventory_transaction")).isEqualTo(ledger);
        removeFault();
        mvc.perform(paid("SHORT")).andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertReserved(0);
        assertThat(count("inventory_transaction")).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id=?", String.class, id)).isEqualTo("REFUNDED");
    }

    @Test void duplicateAndConcurrentPaidCallbacksHaveOneReservationAndPayment() throws Exception {
        long id = expiredOrder("DUPLICATE");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return mvc.perform(paid("DUPLICATE")).andReturn().getResponse().getStatus(); });
            var second = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return mvc.perform(paid("DUPLICATE")).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        mvc.perform(paid("DUPLICATE")).andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("CONFIRMED");
        assertReserved(2);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction WHERE ref_id=? AND type='RESERVE'", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND event_type='order.confirmed'", Integer.class, Long.toString(id))).isEqualTo(1);
    }

    @Test void refundPreservesTheOriginalPaymentMethodAndDoesNotCreateAnotherPayment() throws Exception {
        long id = expiredOrder("CARD-REFUND");
        jdbc.update("UPDATE inventory SET on_hand=0 WHERE branch_id=428 AND product_id=4282");
        jdbc.update("INSERT INTO payment(order_id,method,amount,status,provider_ref) VALUES(?,'CARD',40000,'PENDING','PAY-CARD-REFUND')", id);
        mvc.perform(paid("CARD-REFUND")).andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertReserved(0);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT method,status,amount FROM payment WHERE order_id=?", id))
                .containsEntry("method", "CARD").containsEntry("status", "REFUNDED").containsEntry("amount", 40000L);
    }

    @Test void competingLatePaymentsCannotOverReserveTheLastStock() throws Exception {
        long first = expiredOrder("COMPETE-A"), second = expiredOrder("COMPETE-B");
        jdbc.update("UPDATE inventory SET on_hand=2 WHERE branch_id=428");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return mvc.perform(paid("COMPETE-A")).andReturn().getResponse().getStatus(); });
            var b = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return mvc.perform(paid("COMPETE-B")).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(List.of(orderStatus(first), orderStatus(second))).containsExactlyInAnyOrder("CONFIRMED", "CANCELLED");
        assertReserved(2);
        assertThat(count("payment")).isEqualTo(2);
    }
}
