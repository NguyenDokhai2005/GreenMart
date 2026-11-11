package vn.greenmart.sales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.identity.security.JwtTokens;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.service.PosIdempotencyService;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@DisplayName("Issue #259: [ORD-07] GET /orders/{orderId} chi tiết đơn và hóa đơn")
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
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokens tokens;

    @MockitoBean
    private PosIdempotencyService posIdempotencyService;

    private Long branch1Id;
    private Long branch2Id;
    private Long order1Id;
    private Long order2Id;

    @BeforeEach
    void setupFixtures() {
        jdbc.execute("TRUNCATE branch, category, app_user, sales_channel, sales_order, order_item, payment CASCADE");

        // Chi nhánh
        jdbc.update("INSERT INTO branch(id, code, name, is_active) VALUES (1, 'GM-001', 'GreenMart Quận 1', true), (2, 'GM-002', 'GreenMart Quận 2', true)");
        branch1Id = 1L;
        branch2Id = 2L;

        // Kênh bán
        jdbc.update("INSERT INTO sales_channel(id, code, name) VALUES (1, 'POS', 'POS Channel')");

        // Danh mục & Sản phẩm
        jdbc.update("INSERT INTO category(id, name) VALUES (1, 'Thực phẩm')");
        jdbc.update("INSERT INTO product(id, sku, name, unit, category_id, sale_price, cost_price, is_active) VALUES (101, 'SUA-TH-1L', 'Sữa TH 1L', 'hộp', 1, 30000, 20000, true)");

        // Tài khoản người dùng
        jdbc.update("INSERT INTO app_user(id, username, password_hash, full_name, role, is_active) VALUES "
                + "(701, 'cashier_cn1', 'hash', 'Thu Ngân CN1', 'CASHIER', true),"
                + "(702, 'cashier_cn2', 'hash', 'Thu Ngân CN2', 'CASHIER', true),"
                + "(703, 'owner_user', 'hash', 'Chủ Chuỗi', 'OWNER', true),"
                + "(704, 'admin_user', 'hash', 'Quản Trị', 'ADMIN', true)");

        jdbc.update("INSERT INTO user_branch(user_id, branch_id) VALUES (701, 1), (702, 2)");

        // Đơn hàng mẫu
        // Đơn 1 thuộc Chi nhánh 1
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_by, created_at, completed_at) "
                + "VALUES (1001, 'POS-GM001-20261105-0001', 1, 1, 'COMPLETED', 60000, 0, 60000, 701, NOW(), NOW())");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) "
                + "VALUES (1, 1001, 101, 2, 30000, 0, 60000)");
        jdbc.update("INSERT INTO payment(id, order_id, method, amount, status, provider_ref, paid_at) "
                + "VALUES (1, 1001, 'CASH', 60000, 'PAID', null, NOW())");

        // Đơn 2 thuộc Chi nhánh 2
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_by, created_at, completed_at) "
                + "VALUES (1002, 'POS-GM002-20261105-0002', 1, 2, 'COMPLETED', 30000, 0, 30000, 702, NOW(), NOW())");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) "
                + "VALUES (2, 1002, 101, 1, 30000, 0, 30000)");
        jdbc.update("INSERT INTO payment(id, order_id, method, amount, status, provider_ref, paid_at) "
                + "VALUES (2, 1002, 'CARD', 30000, 'PAID', 'CARD-REF-123', NOW())");

        order1Id = 1001L;
        order2Id = 1002L;
    }

    @Test
    @DisplayName("AC1: Thu ngân cùng chi nhánh xem chi tiết đơn hàng -> Trả về HTTP 200 OK với đầy đủ dữ liệu")
    void testGetOrder_SameBranchCashier_Returns200() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(701, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.code").value("POS-GM001-20261105-0001"))
                .andExpect(jsonPath("$.channel").value("POS"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.total").value(60000))
                .andExpect(jsonPath("$.branch.id").value(branch1Id))
                .andExpect(jsonPath("$.createdBy.id").value(701))
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
                        .header("Authorization", "Bearer " + tokens.issue(702, "CASHIER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
    }

    @Test
    @DisplayName("AC3: Chủ chuỗi OWNER xem đơn của mọi chi nhánh -> Trả về HTTP 200 OK")
    void testGetOrder_Owner_Returns200() throws Exception {
        // OWNER xem đơn của CN 1
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(703, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.branch.id").value(branch1Id));

        // OWNER xem đơn của CN 2
        mvc.perform(get("/api/v1/orders/" + order2Id)
                        .header("Authorization", "Bearer " + tokens.issue(703, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1002))
                .andExpect(jsonPath("$.branch.id").value(branch2Id));
    }

    @Test
    @DisplayName("AC-Admin: Admin không thuộc phạm vi xem dữ liệu đơn -> Trả về HTTP 403 FORBIDDEN")
    void testGetOrder_Admin_Returns403Forbidden() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order1Id)
                        .header("Authorization", "Bearer " + tokens.issue(704, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("AC4: Đơn hàng không tồn tại -> Trả về HTTP 404 NOT_FOUND")
    void testGetOrder_NotFound_Returns404() throws Exception {
        mvc.perform(get("/api/v1/orders/999999")
                        .header("Authorization", "Bearer " + tokens.issue(703, "OWNER")))
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
}
