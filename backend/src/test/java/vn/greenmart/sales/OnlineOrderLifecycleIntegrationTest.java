package vn.greenmart.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.identity.security.JwtTokens;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.dto.CancelOrderRequest;
import vn.greenmart.sales.dto.CancelReason;
import vn.greenmart.sales.service.OnlineOrderService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "management.health.redis.enabled=false",
        "CHANNEL_WEB_SECRET=test-web-secret-key-123456"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Issue #320 (#40, #41, #42): Vòng đời đơn online & 4 đường nhả giữ chỗ")
class OnlineOrderLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokens tokens;

    @Autowired
    private ChannelSignatures signatures;

    @Autowired
    private OnlineOrderService onlineOrderService;

    private Long branch1Id;
    private Long branch2Id;
    private Long webChannelId;
    private Long posChannelId;
    private Long productId;

    private static final long MANAGER_CN1_ID = 901L;
    private static final long STOREKEEPER_CN1_ID = 902L;
    private static final long MANAGER_CN2_ID = 903L;

    @BeforeEach
    void setUp() {
        cleanTestData();

        jdbc.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-ONL-1', 'GreenMart Online 1', '123 Lê Lợi', true),
                   ('GM-ONL-2', 'GreenMart Online 2', '456 Hai Bà Trưng', true)
            ON CONFLICT (code) DO NOTHING
        """);
        branch1Id = jdbc.queryForObject("SELECT id FROM branch WHERE code = 'GM-ONL-1'", Long.class);
        branch2Id = jdbc.queryForObject("SELECT id FROM branch WHERE code = 'GM-ONL-2'", Long.class);

        webChannelId = jdbc.queryForObject("SELECT id FROM sales_channel WHERE code = 'WEB'", Long.class);
        posChannelId = jdbc.queryForObject("SELECT id FROM sales_channel WHERE code = 'POS'", Long.class);
        jdbc.update("UPDATE sales_channel SET webhook_secret = 'test-web-secret-key-123456' WHERE id = ?", webChannelId);

        jdbc.execute("INSERT INTO category (name) VALUES ('Danh mục Online') ON CONFLICT DO NOTHING");
        Long catId = jdbc.queryForObject("SELECT id FROM category LIMIT 1", Long.class);

        jdbc.execute(String.format("""
            INSERT INTO product (sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES ('ONL-SP-01', 'Sản phẩm Online 1', 'hộp', %d, 50000, 35000, true)
            ON CONFLICT (sku) DO NOTHING
        """, catId));
        productId = jdbc.queryForObject("SELECT id FROM product WHERE sku = 'ONL-SP-01'", Long.class);

        jdbc.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (%d, %d, 10, 3, 1, NOW())
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 10, reserved = 3
        """, branch1Id, productId));

        jdbc.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'ql_onl_1', 'hash', 'QL CN1', 'BRANCH_MANAGER', true),
                   (%d, 'tk_onl_1', 'hash', 'Thủ Kho CN1', 'STOREKEEPER', true),
                   (%d, 'ql_onl_2', 'hash', 'QL CN2', 'BRANCH_MANAGER', true)
            ON CONFLICT (id) DO NOTHING
        """, MANAGER_CN1_ID, STOREKEEPER_CN1_ID, MANAGER_CN2_ID));

        jdbc.execute(String.format("""
            INSERT INTO user_branch (user_id, branch_id)
            VALUES (%d, %d), (%d, %d), (%d, %d)
            ON CONFLICT DO NOTHING
        """, MANAGER_CN1_ID, branch1Id, STOREKEEPER_CN1_ID, branch1Id, MANAGER_CN2_ID, branch2Id));
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        jdbc.execute("TRUNCATE TABLE audit_log CASCADE");
        jdbc.execute("TRUNCATE TABLE inventory_transaction CASCADE");
        jdbc.execute("DELETE FROM outbox_event");
        jdbc.execute("DELETE FROM payment");
        jdbc.execute("DELETE FROM order_item");
        jdbc.execute("DELETE FROM sales_order");
        jdbc.execute("DELETE FROM inventory");
        jdbc.execute(String.format("DELETE FROM user_branch WHERE user_id IN (%d, %d, %d)",
                MANAGER_CN1_ID, STOREKEEPER_CN1_ID, MANAGER_CN2_ID));
        jdbc.execute(String.format("DELETE FROM app_user WHERE id IN (%d, %d, %d)",
                MANAGER_CN1_ID, STOREKEEPER_CN1_ID, MANAGER_CN2_ID));
    }

    private Long insertOnlineOrder(String code, String extId, String status, int qty, String reservedIntervalSql) {
        long total = qty * 50000L;
        Long orderId = jdbc.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, external_order_id, branch_id, status, subtotal, discount, total, reserved_until, created_at)
            VALUES ('%s', %d, '%s', %d, '%s', %d, 0, %d, %s, NOW())
            RETURNING id
        """, code, webChannelId, extId, branch1Id, status, total, total, reservedIntervalSql), Long.class);

        jdbc.update("INSERT INTO order_item (order_id, product_id, quantity, unit_price, discount, line_total) VALUES (?, ?, ?, 50000, 0, ?)",
                orderId, productId, qty, total);
        return orderId;
    }

    @Test
    @DisplayName("Đường 1 (EX-05): Job hết hạn 15 phút -> RESERVED quá hạn chuyển EXPIRED, nhả reserved và phát order.expired")
    void testExpiryJob_ReleasesReservedStockAndPublishesOrderExpired() {
        Long expiredOrderId = insertOnlineOrder("WEB-EXP-001", "WEB-EXT-EXP-1", "RESERVED", 3, "NOW() - INTERVAL '1 minute'");

        int processed = onlineOrderService.expireOverdueOrders();
        assertThat(processed).isEqualTo(1);

        String status = jdbc.queryForObject("SELECT status FROM sales_order WHERE id = ?", String.class, expiredOrderId);
        assertThat(status).isEqualTo("EXPIRED");

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, branch1Id, productId);
        assertThat(reserved).isEqualTo(0);

        Integer releaseTxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE ref_id = ? AND type = 'RELEASE' AND delta_reserved = -3",
                Integer.class, expiredOrderId);
        assertThat(releaseTxCount).isEqualTo(1);

        Integer outboxExpiredCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'order.expired'",
                Integer.class, expiredOrderId.toString());
        assertThat(outboxExpiredCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Đường 2 (UC-02): POST /orders/{id}/complete -> CONFIRMED sang COMPLETED, trừ on_hand và reserved")
    void testCompleteOnlineOrder_ConfirmedToCompleted_CommitsSale() throws Exception {
        Long orderId = insertOnlineOrder("WEB-CMP-001", "WEB-EXT-CMP-1", "CONFIRMED", 3, "NULL");
        jdbc.update("INSERT INTO payment (order_id, method, amount, status, paid_at) VALUES (?, 'QR', 150000, 'PAID', NOW())", orderId);

        mvc.perform(post("/api/v1/orders/" + orderId + "/complete")
                        .header("Authorization", "Bearer " + tokens.issue(STOREKEEPER_CN1_ID, "STOREKEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        var inv = jdbc.queryForMap("SELECT on_hand, reserved FROM inventory WHERE branch_id = ? AND product_id = ?", branch1Id, productId);
        assertThat(((Number) inv.get("on_hand")).intValue()).isEqualTo(7);
        assertThat(((Number) inv.get("reserved")).intValue()).isEqualTo(0);

        Integer saleTxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE ref_id = ? AND type = 'SALE' AND delta_on_hand = -3 AND delta_reserved = -3",
                Integer.class, orderId);
        assertThat(saleTxCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Đường 3 (UC-03): POST /orders/{id}/cancel -> Nhả giữ chỗ, hoàn tiền REFUNDED, ghi audit_log và phát order.cancelled")
    void testCancelOnlineOrder_ReleasesStockRefundsPaymentAndAudits() throws Exception {
        Long orderId = insertOnlineOrder("WEB-CAN-001", "WEB-EXT-CAN-1", "CONFIRMED", 3, "NULL");
        jdbc.update("INSERT INTO payment (order_id, method, amount, status, paid_at) VALUES (?, 'QR', 150000, 'PAID', NOW())", orderId);

        CancelOrderRequest cancelReq = new CancelOrderRequest(CancelReason.CUSTOMER_REQUEST, null);

        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + tokens.issue(MANAGER_CN1_ID, "BRANCH_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.statusReason").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.payments[0].status").value("REFUNDED"));

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, branch1Id, productId);
        assertThat(reserved).isEqualTo(0);

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity = 'order' AND entity_id = ? AND action = 'CANCEL_ORDER'",
                Integer.class, orderId.toString());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Đường 4 (SYNC-02, BA E2): Webhook events PAID & CANCELLED xử lý đúng chữ ký HMAC và đơn đã EXPIRED")
    void testChannelOrderEventsWebhook_PaidAndLatePaidAfterExpired() throws Exception {
        // Case A: Đơn RESERVED nhận PAID hợp lệ -> CONFIRMED
        Long resOrderId = insertOnlineOrder("WEB-EVT-001", "EXT-EVT-001", "RESERVED", 3, "NOW() + INTERVAL '10 minutes'");
        String paidBody = objectMapper.writeValueAsString(Map.of(
                "type", "PAID",
                "occurredAt", "2026-11-13T10:00:00+07:00",
                "providerRef", "VNP-999"
        ));
        String validSig = signatures.sign("WEB", paidBody.getBytes(StandardCharsets.UTF_8));

        // Sai chữ ký -> 401
        mvc.perform(post("/api/v1/integrations/WEB/orders/EXT-EVT-001/events")
                        .header("X-Signature", "0000000000000000000000000000000000000000000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        // Đúng chữ ký -> 200 CONFIRMED
        mvc.perform(post("/api/v1/integrations/WEB/orders/EXT-EVT-001/events")
                        .header("X-Signature", validSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Case B: Đơn đã EXPIRED, kho hết hàng (on_hand=3, reserved=3 -> available=0), nhận PAID muộn -> CANCELLED + REFUNDED
        jdbc.update("UPDATE inventory SET on_hand = 3, reserved = 3 WHERE branch_id = ? AND product_id = ?", branch1Id, productId);
        Long expOrderId = insertOnlineOrder("WEB-EVT-002", "EXT-EVT-002", "EXPIRED", 2, "NOW() - INTERVAL '5 minutes'");

        mvc.perform(post("/api/v1/integrations/WEB/orders/EXT-EVT-002/events")
                        .header("X-Signature", validSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.rejectReason").value("OUT_OF_STOCK"));

        String payStatus = jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, expOrderId);
        assertThat(payStatus).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("Đồng thời (Race condition): Job hết hạn và webhook PAID chạy song song trên cùng 1 đơn -> không âm reserved, chỉ ghi 1 trạng thái")
    void testConcurrentExpiryJobAndPaidEvent_NoDoubleReleaseOrNegativeReserved() throws Exception {
        Long orderId = insertOnlineOrder("WEB-RACE-001", "EXT-RACE-001", "RESERVED", 3, "NOW() - INTERVAL '5 seconds'");
        String paidBody = objectMapper.writeValueAsString(Map.of(
                "type", "PAID",
                "occurredAt", "2026-11-14T10:00:00+07:00",
                "providerRef", "VNP-RACE-1"
        ));
        String validSig = signatures.sign("WEB", paidBody.getBytes(StandardCharsets.UTF_8));

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try {
            var expiryFuture = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return onlineOrderService.expireOverdueOrders();
            });

            var paidFuture = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/integrations/WEB/orders/EXT-RACE-001/events")
                                .header("X-Signature", validSig)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(paidBody))
                        .andReturn().getResponse().getStatus();
            });

            expiryFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
            int httpStatus = paidFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(httpStatus).isEqualTo(200);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        String finalStatus = jdbc.queryForObject("SELECT status FROM sales_order WHERE id = ?", String.class, orderId);
        assertThat(finalStatus).isIn("EXPIRED", "CONFIRMED");

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, branch1Id, productId);
        assertThat(reserved).isNotNegative();

        Integer expiredEventCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'order.expired'
                """, Integer.class, orderId.toString());
        Integer confirmedEventCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'order.confirmed'
                """, Integer.class, orderId.toString());

        if ("EXPIRED".equals(finalStatus)) {
            assertThat(reserved).isEqualTo(0);
            assertThat(expiredEventCount).isEqualTo(1);
            assertThat(confirmedEventCount).isEqualTo(0);
        } else {
            assertThat(reserved).isEqualTo(3);
            assertThat(expiredEventCount).isBetween(0, 1);
            assertThat(confirmedEventCount).isEqualTo(1);
        }
    }
}
