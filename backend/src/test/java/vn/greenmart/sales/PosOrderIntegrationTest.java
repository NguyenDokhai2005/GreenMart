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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import vn.greenmart.inventory.exception.InsufficientStockException;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosIdempotencyService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Issue #36: Integration test luồng POS trên PostgreSQL Testcontainers (ORD-01, NFR)")
class PosOrderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            vn.greenmart.inventory.PostgresTestImage.IMAGE
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PosIdempotencyService posIdempotencyService;

    @MockitoSpyBean
    private InventoryService inventoryService;

    private static final long CASHIER_ID = 8888L;
    private static final long BRANCH_ID = 1L;
    private static final long PRODUCT_A_ID = 101L;
    private static final long PRODUCT_B_ID = 102L;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        when(posIdempotencyService.execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<OrderResponse> action = invocation.getArgument(2);
                    return new IdempotencyResult(true, action.get());
                });

        // Kênh POS mặc định
        jdbcTemplate.execute("""
            INSERT INTO sales_channel (code, name, safety_stock)
            VALUES ('POS', 'Bán tại quầy', 0)
            ON CONFLICT (code) DO NOTHING
        """);

        // Chi nhánh GM-001
        jdbcTemplate.execute(String.format("""
            INSERT INTO branch (id, code, name, address, is_active)
            VALUES (%d, 'GM-001', 'GreenMart Quận 1', '123 Lê Lợi', true)
            ON CONFLICT (id) DO NOTHING
        """, BRANCH_ID));

        // User thu ngân và phân quyền chi nhánh
        jdbcTemplate.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'cashier_8888', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân Test', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """, CASHIER_ID));

        jdbcTemplate.execute(String.format("""
            INSERT INTO user_branch (user_id, branch_id)
            VALUES (%d, %d)
            ON CONFLICT DO NOTHING
        """, CASHIER_ID, BRANCH_ID));

        // Danh mục & Sản phẩm A (20.000 đ), Sản phẩm B (30.000 đ)
        jdbcTemplate.execute("""
            INSERT INTO category (id, name)
            VALUES (1, 'Hàng tiêu dùng')
            ON CONFLICT (id) DO NOTHING
        """);

        jdbcTemplate.execute(String.format("""
            INSERT INTO product (id, sku, barcode, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES (%d, 'SP-A-TEST', '8930000000101', 'Sản phẩm A', 'gói', 1, 20000, 15000, true)
            ON CONFLICT (id) DO NOTHING
        """, PRODUCT_A_ID));

        jdbcTemplate.execute(String.format("""
            INSERT INTO product (id, sku, barcode, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES (%d, 'SP-B-TEST', '8930000000102', 'Sản phẩm B', 'hộp', 1, 30000, 22000, true)
            ON CONFLICT (id) DO NOTHING
        """, PRODUCT_B_ID));
    }

    @AfterEach
    void tearDown() {
        reset(inventoryService);
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE inventory_transaction CASCADE");
        jdbcTemplate.execute("DELETE FROM outbox_event");
        jdbcTemplate.execute("DELETE FROM payment");
        jdbcTemplate.execute("DELETE FROM order_item");
        jdbcTemplate.execute("DELETE FROM sales_order");
        jdbcTemplate.execute("DELETE FROM order_sequence");
        jdbcTemplate.execute("DELETE FROM inventory");
        jdbcTemplate.execute("DELETE FROM product");
        jdbcTemplate.execute("DELETE FROM category");
        jdbcTemplate.execute("DELETE FROM user_branch WHERE user_id = " + CASHIER_ID);
        jdbcTemplate.execute("DELETE FROM app_user WHERE id = " + CASHIER_ID);
        jdbcTemplate.execute("DELETE FROM branch WHERE id = " + BRANCH_ID);
    }

    @Test
    @WithMockUser(username = "8888", roles = "CASHIER")
    @DisplayName("Kịch bản 1: Luồng thành công Quote -> POS -> Ghi đủ 5 bảng trong 1 Transaction")
    void testPosOrder_Success_AllTablesUpdatedAtomically() throws Exception {
        // Given: Tồn kho ban đầu SP A = 10, SP B = 5
        jdbcTemplate.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, reorder_point, version, updated_at)
            VALUES (%d, %d, 10, 0, 2, 1, NOW()),
                   (%d, %d, 5, 0, 2, 1, NOW())
        """, BRANCH_ID, PRODUCT_A_ID, BRANCH_ID, PRODUCT_B_ID));

        // Gọi POST /api/v1/orders/quote với 2 SP A (40k) và 1 SP B (30k) -> Tổng 70k
        QuoteRequest quoteRequest = new QuoteRequest(
                BRANCH_ID,
                null,
                List.of(
                        new OrderLineRequest(PRODUCT_A_ID, 2),
                        new OrderLineRequest(PRODUCT_B_ID, 1)
                ),
                null
        );

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quoteRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(70000))
                .andExpect(jsonPath("$.total").value(70000))
                .andExpect(jsonPath("$.canComplete").value(true));

        // Gọi POST /api/v1/orders/pos hoàn tất đơn bằng tiền mặt 100k
        PosOrderRequest posRequest = new PosOrderRequest(
                BRANCH_ID,
                null,
                List.of(
                        new OrderLineRequest(PRODUCT_A_ID, 2),
                        new OrderLineRequest(PRODUCT_B_ID, 1)
                ),
                null,
                new PaymentRequest(PaymentMethod.CASH, 100000L, PaymentSimulation.SUCCESS),
                70000L
        );

        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/orders/pos")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(posRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.total").value(70000))
                .andExpect(jsonPath("$.payments[0].status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].change").value(30000));

        // Xác minh trực tiếp trong CSDL PostgreSQL của container:

        // Bảng sales_order
        var orderMap = jdbcTemplate.queryForMap("SELECT * FROM sales_order LIMIT 1");
        assertThat(orderMap.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) orderMap.get("total")).longValue()).isEqualTo(70000L);
        assertThat(((Number) orderMap.get("branch_id")).longValue()).isEqualTo(BRANCH_ID);
        assertThat(((Number) orderMap.get("created_by")).longValue()).isEqualTo(CASHIER_ID);
        Long orderId = ((Number) orderMap.get("id")).longValue();

        // Bảng order_item (đủ 2 dòng, chụp đúng unit_price lúc bán theo BR-12)
        var items = jdbcTemplate.queryForList(
                "SELECT * FROM order_item WHERE order_id = ? ORDER BY product_id ASC", orderId);
        assertThat(items).hasSize(2);
        assertThat(((Number) items.get(0).get("product_id")).longValue()).isEqualTo(PRODUCT_A_ID);
        assertThat(((Number) items.get(0).get("quantity")).intValue()).isEqualTo(2);
        assertThat(((Number) items.get(0).get("unit_price")).longValue()).isEqualTo(20000L);
        assertThat(((Number) items.get(0).get("line_total")).longValue()).isEqualTo(40000L);

        assertThat(((Number) items.get(1).get("product_id")).longValue()).isEqualTo(PRODUCT_B_ID);
        assertThat(((Number) items.get(1).get("quantity")).intValue()).isEqualTo(1);
        assertThat(((Number) items.get(1).get("unit_price")).longValue()).isEqualTo(30000L);
        assertThat(((Number) items.get(1).get("line_total")).longValue()).isEqualTo(30000L);

        // Bảng payment
        var paymentMap = jdbcTemplate.queryForMap(
                "SELECT * FROM payment WHERE order_id = ?", orderId);
        assertThat(paymentMap.get("status")).isEqualTo("PAID");
        assertThat(paymentMap.get("method")).isEqualTo("CASH");
        assertThat(((Number) paymentMap.get("amount")).longValue()).isEqualTo(70000L);

        // Bảng inventory: on_hand giảm đúng (A giảm 2, B giảm 1), reserved giữ nguyên (BR-02)
        var invA = jdbcTemplate.queryForMap(
                "SELECT * FROM inventory WHERE branch_id = ? AND product_id = ?", BRANCH_ID, PRODUCT_A_ID);
        assertThat(((Number) invA.get("on_hand")).intValue()).isEqualTo(8);
        assertThat(((Number) invA.get("reserved")).intValue()).isEqualTo(0);

        var invB = jdbcTemplate.queryForMap(
                "SELECT * FROM inventory WHERE branch_id = ? AND product_id = ?", BRANCH_ID, PRODUCT_B_ID);
        assertThat(((Number) invB.get("on_hand")).intValue()).isEqualTo(4);
        assertThat(((Number) invB.get("reserved")).intValue()).isEqualTo(0);

        // Bảng inventory_transaction: 2 dòng SALE, delta âm (INV-06, BR-06)
        var txs = jdbcTemplate.queryForList(
                "SELECT * FROM inventory_transaction WHERE ref_id = ? AND ref_type = 'ORDER' ORDER BY product_id ASC", orderId);
        assertThat(txs).hasSize(2);
        assertThat(txs.get(0).get("type")).isEqualTo("SALE");
        assertThat(((Number) txs.get(0).get("delta_on_hand")).intValue()).isEqualTo(-2);
        assertThat(((Number) txs.get(0).get("on_hand_after")).intValue()).isEqualTo(8);

        assertThat(txs.get(1).get("type")).isEqualTo("SALE");
        assertThat(((Number) txs.get(1).get("delta_on_hand")).intValue()).isEqualTo(-1);
        assertThat(((Number) txs.get(1).get("on_hand_after")).intValue()).isEqualTo(4);

        // Một sự kiện hoàn tất đơn và một sự kiện cho mỗi dòng tồn đã cập nhật (#59).
        var outboxEvents = jdbcTemplate.queryForList(
                "SELECT * FROM outbox_event ORDER BY id ASC");
        assertThat(outboxEvents.stream().map(m -> m.get("event_type")).toList())
                .containsExactlyInAnyOrder("order.completed", "stock.changed", "stock.changed");
        assertThat(outboxEvents.stream().map(m -> m.get("status")).toList())
                .containsOnly("PENDING");
        var stockEvents = jdbcTemplate.queryForList("""
                SELECT (payload->>'branchId')::bigint AS branch_id,
                       (payload->>'productId')::bigint AS product_id,
                       (payload->>'version')::bigint AS version
                FROM outbox_event WHERE event_type='stock.changed'
                ORDER BY product_id
                """);
        assertThat(stockEvents.get(0)).containsEntry("branch_id", BRANCH_ID)
                .containsEntry("product_id", PRODUCT_A_ID).containsEntry("version", invA.get("version"));
        assertThat(stockEvents.get(1)).containsEntry("branch_id", BRANCH_ID)
                .containsEntry("product_id", PRODUCT_B_ID).containsEntry("version", invB.get("version"));
    }

    @Test
    @WithMockUser(username = "8888", roles = "CASHIER")
    @DisplayName("Kịch bản 2: Lỗi xảy ra ở dòng thứ 2 khi ghi -> Rollback 100%, dòng 1 hoàn tác không bị trừ tồn")
    void testPosOrder_RollbackOnInsufficientStock_AtomicityMaintained() throws Exception {
        // Given: Tồn kho ban đầu cả 2 SP đều đủ để qua bước kiểm tra getAvailable ban đầu
        jdbcTemplate.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, reorder_point, version, updated_at)
            VALUES (%d, %d, 10, 0, 2, 1, NOW()),
                   (%d, %d, 5, 0, 2, 1, NOW())
        """, BRANCH_ID, PRODUCT_A_ID, BRANCH_ID, PRODUCT_B_ID));

        // Dùng AopTestUtils để stubbing trên target object gốc, tránh trigger kiểm tra @Transactional(MANDATORY) của Spring Proxy ngoài transaction
        InventoryService targetInventoryService = AopTestUtils.getTargetObject(inventoryService);
        doThrow(new InsufficientStockException(BRANCH_ID, PRODUCT_B_ID, "SP-B-TEST", 2, 1))
                .when(targetInventoryService).sellDirect(eq(BRANCH_ID), eq(PRODUCT_B_ID), anyInt(), anyLong());

        PosOrderRequest posRequest = new PosOrderRequest(
                BRANCH_ID,
                null,
                List.of(
                        new OrderLineRequest(PRODUCT_A_ID, 2),
                        new OrderLineRequest(PRODUCT_B_ID, 2)
                ),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, PaymentSimulation.SUCCESS),
                100000L // 2*20k + 2*30k = 100k
        );

        // When & Then: API trả về 409 INSUFFICIENT_STOCK
        mockMvc.perform(post("/api/v1/orders/pos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(posRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        // Xác minh ROLLBACK toàn bộ trong PostgreSQL thật:
        // Không lưu sales_order
        Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class);
        assertThat(orderCount).isEqualTo(0);

        // Không lưu order_item
        Integer itemCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class);
        assertThat(itemCount).isEqualTo(0);

        // Không lưu payment
        Integer paymentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
        assertThat(paymentCount).isEqualTo(0);

        // Tồn kho SP A (dòng 1) ĐÃ ĐƯỢC HOÀN TÁC TRỌN VẸN, vẫn giữ nguyên 10
        Integer onHandA = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, BRANCH_ID, PRODUCT_A_ID);
        assertThat(onHandA).isEqualTo(10);

        Integer onHandB = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, BRANCH_ID, PRODUCT_B_ID);
        assertThat(onHandB).isEqualTo(5);

        // Không có dòng inventory_transaction nào còn sót lại
        Integer txCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Integer.class);
        assertThat(txCount).isEqualTo(0);

        // Không có sự kiện outbox nào
        Integer outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
        assertThat(outboxCount).isEqualTo(0);
    }

    @Test
    @WithMockUser(username = "8888", roles = "CASHIER")
    @DisplayName("Kịch bản phụ: Chặn ở bước kiểm tra tồn ban đầu (Fail-fast trước khi ghi DB)")
    void testPosOrder_FailFast_WhenInsufficientStock() throws Exception {
        // Given: SP A có on_hand = 10; SP B chỉ có on_hand = 1
        jdbcTemplate.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, reorder_point, version, updated_at)
            VALUES (%d, %d, 10, 0, 2, 1, NOW()),
                   (%d, %d, 1, 0, 2, 1, NOW())
        """, BRANCH_ID, PRODUCT_A_ID, BRANCH_ID, PRODUCT_B_ID));

        // When: Mua 2 SP A (đủ) và 3 SP B (thiếu: cần 3 nhưng chỉ có 1)
        PosOrderRequest posRequest = new PosOrderRequest(
                BRANCH_ID,
                null,
                List.of(
                        new OrderLineRequest(PRODUCT_A_ID, 2),
                        new OrderLineRequest(PRODUCT_B_ID, 3)
                ),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, PaymentSimulation.SUCCESS),
                130000L
        );

        // Then: API chặn fail-fast trả về 409 INSUFFICIENT_STOCK
        mockMvc.perform(post("/api/v1/orders/pos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(posRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.lines", hasSize(1)))
                .andExpect(jsonPath("$.details.lines[0].productId").value(PRODUCT_B_ID))
                .andExpect(jsonPath("$.details.lines[0].requested").value(3))
                .andExpect(jsonPath("$.details.lines[0].available").value(1));

        Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class);
        assertThat(orderCount).isEqualTo(0);
    }
}
