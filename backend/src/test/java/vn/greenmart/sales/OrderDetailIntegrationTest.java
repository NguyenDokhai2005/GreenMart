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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.identity.security.JwtTokens;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosIdempotencyService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Issue #259 & #333: [ORD-06/ORD-07] GET /orders và GET /orders/{orderId}")
class OrderDetailIntegrationTest {

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

    @MockitoBean
    private PosIdempotencyService posIdempotencyService;

    private Long branch1Id;
    private Long branch2Id;
    private Long product101Id;
    private Long posChannelId;
    private Long order1Id;
    private Long order2Id;

    private static final long CASHIER_CN1_ID = 701L;
    private static final long CASHIER_CN2_ID = 702L;
    private static final long OWNER_ID = 703L;
    private static final long ADMIN_ID = 704L;
    private static final long MANAGER_CN1_ID = 705L;

    @BeforeEach
    void setupFixtures() {
        cleanTestData();

        // Chi nhánh
        jdbc.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-ORD-CN1', 'GreenMart Quận 1', '123 Lê Lợi', true)
            ON CONFLICT (code) DO NOTHING
        """);
        jdbc.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-ORD-CN2', 'GreenMart Quận 2', '456 Hai Bà Trưng', true)
            ON CONFLICT (code) DO NOTHING
        """);
        branch1Id = jdbc.queryForObject("SELECT id FROM branch WHERE code = 'GM-ORD-CN1'", Long.class);
        branch2Id = jdbc.queryForObject("SELECT id FROM branch WHERE code = 'GM-ORD-CN2'", Long.class);

        // Kênh bán
        jdbc.execute("""
            INSERT INTO sales_channel (code, name, safety_stock)
            VALUES ('POS', 'Bán tại quầy', 0)
            ON CONFLICT (code) DO NOTHING
        """);
        posChannelId = jdbc.queryForObject("SELECT id FROM sales_channel WHERE code = 'POS'", Long.class);

        // Danh mục & Sản phẩm
        jdbc.execute("""
            INSERT INTO category (name)
            VALUES ('Thực phẩm mẫu')
            ON CONFLICT DO NOTHING
        """);
        Long categoryId = jdbc.queryForObject("SELECT id FROM category LIMIT 1", Long.class);

        jdbc.execute(String.format("""
            INSERT INTO product (sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES ('SUA-TH-1L', 'Sữa TH 1L', 'hộp', %d, 30000, 20000, true)
            ON CONFLICT (sku) DO NOTHING
        """, categoryId));
        product101Id = jdbc.queryForObject("SELECT id FROM product WHERE sku = 'SUA-TH-1L'", Long.class);

        // Tồn kho
        jdbc.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (%d, %d, 100, 0, 1, NOW())
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 100, reserved = 0
        """, branch1Id, product101Id));
        jdbc.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (%d, %d, 100, 0, 1, NOW())
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 100, reserved = 0
        """, branch2Id, product101Id));

        // Tài khoản người dùng
        jdbc.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'cashier_cn1', 'hash', 'Thu Ngân CN1', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """, CASHIER_CN1_ID));
        jdbc.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'cashier_cn2', 'hash', 'Thu Ngân CN2', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """, CASHIER_CN2_ID));
        jdbc.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'owner_user', 'hash', 'Chủ Chuỗi', 'OWNER', true)
            ON CONFLICT (id) DO NOTHING
        """, OWNER_ID));
        jdbc.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'admin_user', 'hash', 'Quản Trị', 'ADMIN', true)
            ON CONFLICT (id) DO NOTHING
        """, ADMIN_ID));
        jdbc.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'manager_cn1', 'hash', 'Quản Lý CN1', 'BRANCH_MANAGER', true)
            ON CONFLICT (id) DO NOTHING
        """, MANAGER_CN1_ID));

        jdbc.execute(String.format("INSERT INTO user_branch (user_id, branch_id) VALUES (%d, %d) ON CONFLICT DO NOTHING", CASHIER_CN1_ID, branch1Id));
        jdbc.execute(String.format("INSERT INTO user_branch (user_id, branch_id) VALUES (%d, %d) ON CONFLICT DO NOTHING", CASHIER_CN2_ID, branch2Id));
        jdbc.execute(String.format("INSERT INTO user_branch (user_id, branch_id) VALUES (%d, %d) ON CONFLICT DO NOTHING", MANAGER_CN1_ID, branch1Id));

        // Đơn hàng mẫu 1 (Chi nhánh 1)
        order1Id = jdbc.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, status, subtotal, discount, total, created_by, created_at, completed_at)
            VALUES ('POS-GM001-TEST-0001', %d, %d, 'COMPLETED', 60000, 0, 60000, %d, NOW(), NOW())
            RETURNING id
        """, posChannelId, branch1Id, CASHIER_CN1_ID), Long.class);

        jdbc.update("INSERT INTO order_item (order_id, product_id, quantity, unit_price, discount, line_total) VALUES (?, ?, 2, 30000, 0, 60000)",
                order1Id, product101Id);
        jdbc.update("INSERT INTO payment (order_id, method, amount, status, cash_received, provider_ref, paid_at) VALUES (?, 'CASH', 60000, 'PAID', 60000, null, NOW())",
                order1Id);

        // Đơn hàng mẫu 2 (Chi nhánh 2)
        order2Id = jdbc.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, status, subtotal, discount, total, created_by, created_at, completed_at)
            VALUES ('POS-GM002-TEST-0002', %d, %d, 'COMPLETED', 30000, 0, 30000, %d, NOW(), NOW())
            RETURNING id
        """, posChannelId, branch2Id, CASHIER_CN2_ID), Long.class);

        jdbc.update("INSERT INTO order_item (order_id, product_id, quantity, unit_price, discount, line_total) VALUES (?, ?, 1, 30000, 0, 30000)",
                order2Id, product101Id);
        jdbc.update("INSERT INTO payment (order_id, method, amount, status, cash_received, provider_ref, paid_at) VALUES (?, 'CARD', 30000, 'PAID', null, 'CARD-REF-123', NOW())",
                order2Id);

        given(posIdempotencyService.execute(any(), any(), any())).willAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(2);
            if (action == null) return null;
            return new IdempotencyResult(true, (OrderResponse) action.get());
        });
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        jdbc.execute("TRUNCATE audit_log");
        jdbc.execute("DELETE FROM outbox_event");
        jdbc.execute("DELETE FROM order_item");
        jdbc.execute("DELETE FROM payment");
        jdbc.execute("DELETE FROM sales_order");
        jdbc.execute(String.format("DELETE FROM user_branch WHERE user_id IN (%d, %d, %d, %d, %d)",
                CASHIER_CN1_ID, CASHIER_CN2_ID, OWNER_ID, ADMIN_ID, MANAGER_CN1_ID));
        jdbc.execute(String.format("DELETE FROM app_user WHERE id IN (%d, %d, %d, %d, %d)",
                CASHIER_CN1_ID, CASHIER_CN2_ID, OWNER_ID, ADMIN_ID, MANAGER_CN1_ID));
    }

    @Test
    @DisplayName("AC1 Luồng thực tế (Feedback Lead): Quản lý tạo đơn POS có giảm giá tay & tiền mặt dư -> Thu ngân cùng chi nhánh GET khớp 100%")
    void testAC1_CreatePosOrder_ThenGetOrder_Matches100Percent() throws Exception {
        // Giá gốc: 2 x 30.000 = 60.000 đ. Giảm giá tay: 5.000 đ -> Tổng phải trả: 55.000 đ.
        // Khách đưa tiền mặt: 70.000 đ -> Tiền thối: 15.000 đ.
        PosOrderRequest postRequest = new PosOrderRequest(
                branch1Id,
                null,
                List.of(new OrderLineRequest(product101Id, 2)),
                new ManualDiscount(5000L, "Khách quen giảm 5k"),
                new PaymentRequest(PaymentMethod.CASH, 70000L, PaymentSimulation.SUCCESS),
                55000L
        );

        String managerToken = tokens.issue(MANAGER_CN1_ID, "BRANCH_MANAGER");
        String cashierToken = tokens.issue(CASHIER_CN1_ID, "CASHIER");

        // Quản lý chi nhánh gọi POST /orders/pos tạo đơn có giảm giá tay
        MvcResult postResult = mvc.perform(post("/api/v1/orders/pos")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        OrderResponse posRes = objectMapper.readValue(postResult.getResponse().getContentAsString(), OrderResponse.class);

        // Thu ngân cùng chi nhánh gọi GET /orders/{orderId} đọc lại đơn vừa tạo
        MvcResult getResult = mvc.perform(get("/api/v1/orders/" + posRes.id())
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andReturn();

        OrderResponse getRes = objectMapper.readValue(getResult.getResponse().getContentAsString(), OrderResponse.class);

        // Đối chiếu khớp 100% từng trường
        assertThat(getRes.id()).isEqualTo(posRes.id());
        assertThat(getRes.code()).isEqualTo(posRes.code());
        assertThat(getRes.subtotal()).isEqualTo(posRes.subtotal()).isEqualTo(60000L);
        assertThat(getRes.itemDiscount()).isEqualTo(posRes.itemDiscount()).isEqualTo(0L);
        assertThat(getRes.orderDiscount()).isEqualTo(posRes.orderDiscount()).isEqualTo(0L);
        assertThat(getRes.manualDiscount()).isEqualTo(posRes.manualDiscount()).isEqualTo(5000L);
        assertThat(getRes.manualDiscountReason()).isEqualTo(posRes.manualDiscountReason()).isEqualTo("Khách quen giảm 5k");
        assertThat(getRes.discount()).isEqualTo(posRes.discount()).isEqualTo(5000L);
        assertThat(getRes.total()).isEqualTo(posRes.total()).isEqualTo(55000L);

        // Đối chiếu chi tiết thanh toán tiền mặt (cashReceived & change)
        assertThat(getRes.payments()).hasSize(1);
        assertThat(getRes.payments().get(0).amount()).isEqualTo(55000L);
        assertThat(getRes.payments().get(0).cashReceived()).isEqualTo(70000L);
        assertThat(getRes.payments().get(0).change()).isEqualTo(15000L);
        assertThat(getRes.payments().get(0).paidAt()).isNotNull();

        // Đối chiếu chi tiết dòng hàng
        assertThat(getRes.items()).hasSize(1);
        assertThat(getRes.items().get(0).productId()).isEqualTo(product101Id);
        assertThat(getRes.items().get(0).quantity()).isEqualTo(2);
        assertThat(getRes.items().get(0).lineTotal()).isEqualTo(60000L);
    }

    @Test
    @DisplayName("AC1: Thu ngân cùng chi nhánh xem chi tiết đơn hàng -> Trả về HTTP 200 OK với đầy đủ dữ liệu")
    void testGetOrder_SameBranchCashier_Returns200() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(CASHIER_CN1_ID, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order1Id))
                .andExpect(jsonPath("$.code").value("POS-GM001-TEST-0001"))
                .andExpect(jsonPath("$.channel").value("POS"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.total").value(60000))
                .andExpect(jsonPath("$.branch.id").value(branch1Id))
                .andExpect(jsonPath("$.createdBy.id").value(CASHIER_CN1_ID))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("SUA-TH-1L"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.payments[0].method").value("CASH"));
    }

    @Test
    @DisplayName("AC2: Thu ngân Chi nhánh 2 gọi đơn của Chi nhánh 1 -> Trả về HTTP 403 BRANCH_FORBIDDEN")
    void testGetOrder_DifferentBranchCashier_Returns403BranchForbidden() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(CASHIER_CN2_ID, "CASHIER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
    }

    @Test
    @DisplayName("AC3: Chủ chuỗi OWNER xem đơn của mọi chi nhánh -> Trả về HTTP 200 OK")
    void testGetOrder_Owner_Returns200() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order1Id))
                .andExpect(jsonPath("$.branch.id").value(branch1Id));

        mvc.perform(get("/api/v1/orders/" + order2Id)
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order2Id))
                .andExpect(jsonPath("$.branch.id").value(branch2Id));
    }

    @Test
    @DisplayName("AC-Admin: Admin không thuộc phạm vi xem dữ liệu đơn -> Trả về HTTP 403 FORBIDDEN")
    void testGetOrder_Admin_Returns403Forbidden() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(ADMIN_ID, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("AC4: Đơn hàng không tồn tại -> Trả về HTTP 404 NOT_FOUND")
    void testGetOrder_NotFound_Returns404() throws Exception {
        mvc.perform(get("/api/v1/orders/999999")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("AC5: Chưa đăng nhập -> Trả về HTTP 401 UNAUTHORIZED")
    void testGetOrder_Unauthenticated_Returns401() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("ORD-06: Thu ngân không truyền branchId -> Chỉ thấy đơn thuộc chi nhánh mình, mới nhất trước")
    void testListOrders_CashierDefaultScope_ReturnsOnlyOwnBranchOrders() throws Exception {
        mvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokens.issue(CASHIER_CN1_ID, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(order1Id))
                .andExpect(jsonPath("$.content[0].branch.id").value(branch1Id))
                .andExpect(jsonPath("$.content[0].itemCount").value(2));
    }

    @Test
    @DisplayName("ORD-06: Thu ngân CN1 lọc branchId của CN2 -> Trả về HTTP 403 BRANCH_FORBIDDEN")
    void testListOrders_CashierCrossBranch_Returns403BranchForbidden() throws Exception {
        mvc.perform(get("/api/v1/orders")
                        .param("branchId", String.valueOf(branch2Id))
                        .header("Authorization", "Bearer " + tokens.issue(CASHIER_CN1_ID, "CASHIER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
    }

    @Test
    @DisplayName("ORD-06: OWNER xem toàn chuỗi và lọc theo channel, status, khoảng ngày, từ khóa q (kể cả ký tự %, _)")
    void testListOrders_OwnerWithFilters_ReturnsMatchingOrders() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString();

        // OWNER không truyền branchId -> thấy đủ 2 đơn của cả 2 chi nhánh, đơn mới tạo (order2Id) đứng trước
        mvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(order2Id))
                .andExpect(jsonPath("$.content[1].id").value(order1Id));

        // OWNER lọc theo branchId, channel, status, from/to và q
        mvc.perform(get("/api/v1/orders")
                        .param("branchId", String.valueOf(branch1Id))
                        .param("channel", "POS")
                        .param("status", "COMPLETED")
                        .param("from", today)
                        .param("to", today)
                        .param("q", "POS-GM001-TEST")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("POS-GM001-TEST-0001"));

        // Kiểm tra escape ký tự wildcard '%' và '_' trong q
        mvc.perform(get("/api/v1/orders")
                        .param("q", "%")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/v1/orders")
                        .param("q", "_")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("ORD-06: ADMIN không thuộc phạm vi xem danh sách đơn -> Trả về HTTP 403 FORBIDDEN")
    void testListOrders_Admin_Returns403Forbidden() throws Exception {
        mvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokens.issue(ADMIN_ID, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("ORD-06: Lọc với from sau to, status hoặc channel không hợp lệ -> Trả về HTTP 400 VALIDATION_ERROR")
    void testListOrders_InvalidFilters_Returns400ValidationError() throws Exception {
        // 1. from sau to -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/orders")
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-05")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 2. status không thuộc enum OrderStatus -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/orders")
                        .param("status", "INVALID_STATUS_CODE")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 3. channel không hợp lệ -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/orders")
                        .param("channel", "ABC")
                        .header("Authorization", "Bearer " + tokens.issue(OWNER_ID, "OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
