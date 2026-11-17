package vn.greenmart.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import vn.greenmart.inventory.PostgresTestImage;

import java.util.stream.StreamSupport;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@AutoConfigureMockMvc
@Testcontainers
class ReportApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setupFixtures() {
        jdbc.execute("TRUNCATE branch, category, app_user CASCADE");

        // Branches
        jdbc.update("INSERT INTO branch(id, code, name) VALUES (1, 'B01', 'Chi nhánh 1'), (2, 'B02', 'Chi nhánh 2')");

        // Category & Products
        jdbc.update("INSERT INTO category(id, name) VALUES (1, 'Thực phẩm')");
        jdbc.update("INSERT INTO product(id, sku, name, unit, category_id, sale_price, cost_price, is_active) VALUES "
                + "(1, 'SKU-01', 'Sản phẩm 1', 'cái', 1, 100000, 50000, true),"
                + "(2, 'SKU-02', 'Sản phẩm 2', 'cái', 1, 50000, 25000, true),"
                + "(3, 'SKU-03', 'Sản phẩm 3', 'cái', 1, 20000, 10000, true),"
                + "(4, 'SKU-04', 'Sản phẩm 4', 'cái', 1, 10000, 5000, true)");

        // Users
        jdbc.update("INSERT INTO app_user(id, username, password_hash, full_name, role) VALUES "
                + "(10, 'owner', 'hash', 'Chủ Chuỗi', 'OWNER'),"
                + "(11, 'manager1', 'hash', 'Quản Lý CN1', 'BRANCH_MANAGER'),"
                + "(12, 'manager2', 'hash', 'Quản Lý CN2', 'BRANCH_MANAGER'),"
                + "(13, 'cashier', 'hash', 'Thu Ngân', 'CASHIER')");

        // Branch mapping for manager 1 (branch 1) & manager 2 (branch 2)
        jdbc.update("INSERT INTO user_branch(user_id, branch_id) VALUES (11, 1), (12, 2)");

        // Sales Channel
        jdbc.update("INSERT INTO sales_channel(id, code, name) VALUES (1, 'POS', 'Bán tại quầy'), (2, 'WEB', 'Website')");
    }

    @Test
    @DisplayName("AC-1: Báo cáo doanh thu tính theo ngày hoàn tất, trừ trả hàng theo ngày trả (BR-18)")
    void shouldCalculateRevenueAccordingToBR18() throws Exception {
        // Đơn 1: CN 1, COMPLETED ngày 2026-11-01, total 500.000
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(101, 'ORD-101', 1, 1, 'COMPLETED', 500000, 0, 500000, '2026-11-01T08:00:00+07:00', '2026-11-01T09:00:00+07:00')");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) VALUES "
                + "(1001, 101, 1, 5, 100000, 0, 500000)");

        // Đơn 2: CN 1, COMPLETED ngày 2026-11-01, total 300.000
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(102, 'ORD-102', 1, 1, 'COMPLETED', 300000, 0, 300000, '2026-11-01T10:00:00+07:00', '2026-11-01T11:00:00+07:00')");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) VALUES "
                + "(1002, 102, 2, 6, 50000, 0, 300000)");

        // Đơn 3: CANCELLED ngày 2026-11-02 -> BR-18: Không được tính!
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(103, 'ORD-103', 1, 1, 'CANCELLED', 400000, 0, 400000, '2026-11-02T08:00:00+07:00', '2026-11-02T09:00:00+07:00')");

        // Đơn 4: CN 1, COMPLETED ngày 2026-11-03, total 200.000
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(104, 'ORD-104', 1, 1, 'COMPLETED', 200000, 0, 200000, '2026-11-03T08:00:00+07:00', '2026-11-03T09:00:00+07:00')");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) VALUES "
                + "(1004, 104, 2, 4, 50000, 0, 200000)");

        // Trả hàng 1: trả đơn 101, duyệt ngày 2026-11-03, refund 100.000 -> trừ vào ngày trả (BR-18)
        jdbc.update("INSERT INTO return_document(id, code, order_id, branch_id, reason, refund_amount, status, created_by, approved_by, created_at, approved_at) VALUES "
                + "(201, 'RET-201', 101, 1, 'Hàng lỗi', 100000, 'APPROVED', 11, 10, '2026-11-02T10:00:00+07:00', '2026-11-03T14:00:00+07:00')");
        jdbc.update("INSERT INTO return_item(id, return_id, order_item_id, quantity, condition) VALUES "
                + "(2001, 201, 1001, 1, 'DAMAGED')");

        // Trả hàng 2: DRAFT (chưa duyệt) -> không được tính
        jdbc.update("INSERT INTO return_document(id, code, order_id, branch_id, reason, refund_amount, status, created_by, created_at) VALUES "
                + "(202, 'RET-202', 102, 1, 'Khách đổi ý', 50000, 'DRAFT', 11, '2026-11-03T15:00:00+07:00')");

        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("10").roles("OWNER"))
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-03")
                        .param("groupBy", "day")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Summary
                .andExpect(jsonPath("$.summary.totalGrossRevenue").value(1000000))
                .andExpect(jsonPath("$.summary.totalReturnAmount").value(100000))
                .andExpect(jsonPath("$.summary.totalNetRevenue").value(900000))
                .andExpect(jsonPath("$.summary.totalOrders").value(3))
                .andExpect(jsonPath("$.summary.totalReturns").value(1))
                // Point 1: 2026-11-01
                .andExpect(jsonPath("$.points[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.points[0].grossRevenue").value(800000))
                .andExpect(jsonPath("$.points[0].returnAmount").value(0))
                .andExpect(jsonPath("$.points[0].revenue").value(800000))
                .andExpect(jsonPath("$.points[0].orderCount").value(2))
                .andExpect(jsonPath("$.points[0].returnCount").value(0))
                // Point 2: 2026-11-02 (zero-fill)
                .andExpect(jsonPath("$.points[1].date").value("2026-11-02"))
                .andExpect(jsonPath("$.points[1].grossRevenue").value(0))
                .andExpect(jsonPath("$.points[1].returnAmount").value(0))
                .andExpect(jsonPath("$.points[1].revenue").value(0))
                // Point 3: 2026-11-03
                .andExpect(jsonPath("$.points[2].date").value("2026-11-03"))
                .andExpect(jsonPath("$.points[2].grossRevenue").value(200000))
                .andExpect(jsonPath("$.points[2].returnAmount").value(100000))
                .andExpect(jsonPath("$.points[2].revenue").value(100000))
                .andExpect(jsonPath("$.points[2].orderCount").value(1))
                .andExpect(jsonPath("$.points[2].returnCount").value(1));
    }

    @Test
    @DisplayName("AC-2: Top sản phẩm bán chạy và bán chậm theo số lượng ròng (DASH-02)")
    void shouldReturnTopSellingAndSlowSellingProductsByNetQuantity() throws Exception {
        // Đơn hoàn tất trong tháng 11/2026
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(110, 'ORD-110', 1, 1, 'COMPLETED', 1000000, 0, 1000000, '2026-11-05T08:00:00+07:00', '2026-11-05T09:00:00+07:00')");

        // P1: bán 10
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) VALUES "
                + "(1101, 110, 1, 10, 100000, 0, 1000000)");

        // P2: bán 25
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(111, 'ORD-111', 1, 1, 'COMPLETED', 1250000, 0, 1250000, '2026-11-06T08:00:00+07:00', '2026-11-06T09:00:00+07:00')");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) VALUES "
                + "(1102, 111, 2, 25, 50000, 0, 1250000)");

        // P3: bán 5
        jdbc.update("INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total, created_at, completed_at) VALUES "
                + "(112, 'ORD-112', 1, 1, 'COMPLETED', 100000, 0, 100000, '2026-11-07T08:00:00+07:00', '2026-11-07T09:00:00+07:00')");
        jdbc.update("INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total) VALUES "
                + "(1103, 112, 3, 5, 20000, 0, 100000)");

        // P4: Không bán được cái nào -> net_quantity = 0

        // Trả hàng: Trả 2 cái P1 (duyệt ngày 10/11/2026) -> netQuantity P1 = 10 - 2 = 8
        jdbc.update("INSERT INTO return_document(id, code, order_id, branch_id, reason, refund_amount, status, created_by, approved_by, created_at, approved_at) VALUES "
                + "(210, 'RET-210', 110, 1, 'Lỗi', 200000, 'APPROVED', 11, 10, '2026-11-09T10:00:00+07:00', '2026-11-10T11:00:00+07:00')");
        jdbc.update("INSERT INTO return_item(id, return_id, order_item_id, quantity, condition) VALUES "
                + "(2101, 210, 1101, 2, 'DAMAGED')");

        mvc.perform(get("/api/v1/reports/top-products")
                        .with(user("10").roles("OWNER"))
                        .param("month", "2026-11")
                        .param("limit", "2")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2026-11-01"))
                .andExpect(jsonPath("$.period.to").value("2026-11-30"))
                // Top Selling (limit 2): P2 (net 25), P1 (net 8)
                .andExpect(jsonPath("$.topSelling", hasSize(2)))
                .andExpect(jsonPath("$.topSelling[0].productId").value(2))
                .andExpect(jsonPath("$.topSelling[0].netQuantity").value(25))
                .andExpect(jsonPath("$.topSelling[1].productId").value(1))
                .andExpect(jsonPath("$.topSelling[1].netQuantity").value(8))
                .andExpect(jsonPath("$.topSelling[1].soldQuantity").value(10))
                .andExpect(jsonPath("$.topSelling[1].returnedQuantity").value(2))
                // Slow Selling (limit 2): P4 (net 0), P3 (net 5)
                .andExpect(jsonPath("$.slowSelling", hasSize(2)))
                .andExpect(jsonPath("$.slowSelling[0].productId").value(4))
                .andExpect(jsonPath("$.slowSelling[0].netQuantity").value(0))
                .andExpect(jsonPath("$.slowSelling[1].productId").value(3))
                .andExpect(jsonPath("$.slowSelling[1].netQuantity").value(5));
    }

    @ParameterizedTest
    @CsvSource({
            "10000, 10000, 0, 0, 2000, 2000, 9000, 9000",
            "20000, 10000, 5000, 5000, 3999, 999, 12001, 4000",
            "10000, 10000, 0, 0, 1, 0, 10000, 9999",
            "10000, 10000, 10000, 10000, 0, 0, 0, 0",
            "10000, 10000, 0, 10000, 10000, 10000, 0, 0"
    })
    void allocatesPaidOrderTotalAcrossProducts(
            int firstPrice, int secondPrice, int firstLineDiscount, int secondLineDiscount,
            int orderDiscount, int manualDiscount, int firstRevenue, int secondRevenue
    ) throws Exception {
        int subtotal = firstPrice + secondPrice;
        int discount = firstLineDiscount + secondLineDiscount + orderDiscount;
        int total = subtotal - discount;
        jdbc.update("""
                INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total,
                                        manual_discount, manual_discount_reason, created_at, completed_at)
                VALUES (130, 'ORD-130', 1, 1, 'COMPLETED', ?, ?, ?, ?,
                        CASE WHEN ? > 0 THEN 'QA manual discount' ELSE NULL END,
                        '2026-11-17T08:00:00+07:00', '2026-11-17T09:00:00+07:00')
                """, subtotal, discount, total, manualDiscount, manualDiscount);
        jdbc.update("""
                INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total)
                VALUES (1301, 130, 1, 1, ?, ?, ?),
                       (1302, 130, 2, 1, ?, ?, ?)
                """, firstPrice, firstLineDiscount, firstPrice - firstLineDiscount,
                secondPrice, secondLineDiscount, secondPrice - secondLineDiscount);

        var topProducts = mvc.perform(get("/api/v1/reports/top-products").with(user("11").roles("BRANCH_MANAGER"))
                        .param("from", "2026-11-17").param("to", "2026-11-17")
                        .param("branchId", "1").param("channelId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topSelling[?(@.productId == 1)].revenue", contains(firstRevenue)))
                .andExpect(jsonPath("$.topSelling[?(@.productId == 2)].revenue", contains(secondRevenue)))
                .andExpect(jsonPath("$.slowSelling[?(@.productId == 1)].revenue", contains(firstRevenue)))
                .andExpect(jsonPath("$.slowSelling[?(@.productId == 2)].revenue", contains(secondRevenue)))
                .andReturn();
        var revenue = mvc.perform(get("/api/v1/reports/revenue").with(user("11").roles("BRANCH_MANAGER"))
                        .param("from", "2026-11-17").param("to", "2026-11-17")
                        .param("branchId", "1").param("channelId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalNetRevenue").value(total))
                .andReturn();

        JsonNode productRows = mapper.readTree(topProducts.getResponse().getContentAsByteArray()).path("topSelling");
        long productRevenue = StreamSupport.stream(productRows.spliterator(), false)
                .mapToLong(row -> row.path("revenue").asLong()).sum();
        assertEquals(mapper.readTree(revenue.getResponse().getContentAsByteArray())
                .path("summary").path("totalNetRevenue").asLong(), productRevenue);
    }

    @Test
    void shouldAllocateActualRefundAndReconcileReportsForSamePeriod() throws Exception {
        jdbc.update("""
                INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total,
                                        created_at, completed_at)
                VALUES (120, 'ORD-120', 1, 1, 'COMPLETED', 170000, 16997, 153003,
                        '2026-11-05T08:00:00+07:00', '2026-11-05T09:00:00+07:00')
                """);
        jdbc.update("""
                INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total)
                VALUES (1201, 120, 1, 2, 100000, 60000, 140000),
                       (1202, 120, 2, 1, 50000, 20000, 30000)
                """);
        jdbc.update("""
                INSERT INTO return_document(id, code, order_id, branch_id, reason, refund_amount, status,
                                            created_by, approved_by, created_at, approved_at)
                VALUES (220, 'RET-220', 120, 2, 'Lỗi', 90001, 'APPROVED', 12, 10,
                        '2026-11-09T18:00:00+07:00', '2026-11-10T11:00:00+07:00')
                """);
        jdbc.update("""
                INSERT INTO return_item(id, return_id, order_item_id, quantity, condition)
                VALUES (2201, 220, 1201, 1, 'RESELLABLE'),
                       (2202, 220, 1202, 1, 'DAMAGED')
                """);

        // Use the approval day, so sales revenue does not contribute to either total.
        var revenue = mvc.perform(get("/api/v1/reports/revenue").with(user("12").roles("BRANCH_MANAGER"))
                        .param("from", "2026-11-10").param("to", "2026-11-10")
                        .param("branchId", "2").param("channelId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalGrossRevenue").value(0))
                .andExpect(jsonPath("$.summary.totalReturnAmount").value(90001))
                .andReturn();

        var topProducts = mvc.perform(get("/api/v1/reports/top-products").with(user("12").roles("BRANCH_MANAGER"))
                        .param("from", "2026-11-10").param("to", "2026-11-10")
                        .param("branchId", "2").param("channelId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topSelling", hasSize(4)))
                .andExpect(jsonPath("$.topSelling[?(@.productId == 1)].revenue", contains(-63001)))
                .andExpect(jsonPath("$.topSelling[?(@.productId == 2)].revenue", contains(-27000)))
                .andExpect(jsonPath("$.slowSelling[?(@.productId == 1)].revenue", contains(-63001)))
                .andExpect(jsonPath("$.slowSelling[?(@.productId == 2)].revenue", contains(-27000)))
                .andReturn();

        long returnAmount = mapper.readTree(revenue.getResponse().getContentAsByteArray())
                .path("summary").path("totalReturnAmount").asLong();
        JsonNode productRows = mapper.readTree(topProducts.getResponse().getContentAsByteArray()).path("topSelling");
        long productRevenue = StreamSupport.stream(productRows.spliterator(), false)
                .mapToLong(row -> row.path("revenue").asLong())
                .sum();
        assertEquals(returnAmount, -productRevenue);
    }

    @Test
    void shouldKeepReturnedQuantityWhenFullyDiscountedItemsHaveNoRefund() throws Exception {
        jdbc.update("""
                INSERT INTO sales_order(id, code, channel_id, branch_id, status, subtotal, discount, total,
                                        created_at, completed_at)
                VALUES (121, 'ORD-121', 1, 1, 'COMPLETED', 0, 0, 0,
                        '2026-11-05T08:00:00+07:00', '2026-11-05T09:00:00+07:00')
                """);
        jdbc.update("""
                INSERT INTO order_item(id, order_id, product_id, quantity, unit_price, discount, line_total)
                VALUES (1211, 121, 1, 1, 100000, 100000, 0)
                """);
        jdbc.update("""
                INSERT INTO return_document(id, code, order_id, branch_id, reason, refund_amount, status,
                                            created_by, approved_by, created_at, approved_at)
                VALUES (221, 'RET-221', 121, 1, 'Lỗi', 0, 'APPROVED', 11, 10,
                        '2026-11-09T18:00:00+07:00', '2026-11-10T11:00:00+07:00')
                """);
        jdbc.update("""
                INSERT INTO return_item(id, return_id, order_item_id, quantity, condition)
                VALUES (2211, 221, 1211, 1, 'RESELLABLE')
                """);

        mvc.perform(get("/api/v1/reports/top-products").with(user("10").roles("OWNER"))
                        .param("from", "2026-11-10").param("to", "2026-11-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topSelling[?(@.productId == 1)].returnedQuantity", contains(1)))
                .andExpect(jsonPath("$.topSelling[?(@.productId == 1)].revenue", contains(0)));
    }

    @Test
    @DisplayName("AC-3: Phân quyền RBAC và cách ly chi nhánh (AUTH-01, AUTH-02)")
    void shouldEnforceRbacAndBranchIsolation() throws Exception {
        // 1. OWNER xem chi nhánh 1 -> 200 OK
        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("10").roles("OWNER"))
                        .param("branchId", "1"))
                .andExpect(status().isOk());

        // 2. BRANCH_MANAGER xem chi nhánh 1 của mình -> 200 OK
        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("11").roles("BRANCH_MANAGER"))
                        .param("branchId", "1"))
                .andExpect(status().isOk());

        // 3. BRANCH_MANAGER xem chi nhánh 2 (không được gán) -> 403 BRANCH_FORBIDDEN
        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("11").roles("BRANCH_MANAGER"))
                        .param("branchId", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));

        // 4. CASHIER truy cập -> 403 FORBIDDEN
        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("13").roles("CASHIER")))
                .andExpect(status().isForbidden());

        // 5. Chưa đăng nhập -> 401 UNAUTHORIZED
        mvc.perform(get("/api/v1/reports/revenue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Validation: Báo lỗi khi tham số không hợp lệ")
    void shouldReturnValidationErrorOnInvalidParams() throws Exception {
        // from sau to
        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("10").roles("OWNER"))
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // groupBy không hợp lệ
        mvc.perform(get("/api/v1/reports/revenue")
                        .with(user("10").roles("OWNER"))
                        .param("groupBy", "year"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // month sai format
        mvc.perform(get("/api/v1/reports/top-products")
                        .with(user("10").roles("OWNER"))
                        .param("month", "2026/11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // limit ngoài khoảng 1..100
        mvc.perform(get("/api/v1/reports/top-products")
                        .with(user("10").roles("OWNER"))
                        .param("limit", "150"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @CsvSource({
            "day, 2023-03-01, 2024-02-29, 2024-03-01, 366",
            "week, 2020-02-29, 2025-02-27, 2025-02-28, 262",
            "month, 2021-01-01, 2025-12-31, 2026-01-01, 60"
    })
    void revenueAcceptsInclusiveLimitAndRejectsOneDayOver(
            String groupBy, String from, String lastAllowedDate, String firstRejectedDate, int points
    ) throws Exception {
        mvc.perform(get("/api/v1/reports/revenue").with(user("10").roles("OWNER"))
                        .param("groupBy", groupBy).param("from", from).param("to", lastAllowedDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(points)));

        mvc.perform(get("/api/v1/reports/revenue").with(user("10").roles("OWNER"))
                        .param("groupBy", groupBy).param("from", from).param("to", firstRejectedDate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revenue", "top-products"})
    void rejectsQaDateRangeBeforeBuildingReport(String report) throws Exception {
        mvc.perform(get("/api/v1/reports/" + report).with(user("11").roles("BRANCH_MANAGER"))
                        .param("from", "0001-01-01").param("to", "2026-10-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void dailyRevenueRejectsDatesOutsideSupportedYears() throws Exception {
        mvc.perform(get("/api/v1/reports/revenue").with(user("10").roles("OWNER"))
                        .param("to", "+999999999-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void topProductsAcceptsInclusiveLimitAndRejectsOneDayOver() throws Exception {
        mvc.perform(get("/api/v1/reports/top-products").with(user("10").roles("OWNER"))
                        .param("from", "2023-03-01").param("to", "2024-02-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2023-03-01"))
                .andExpect(jsonPath("$.period.to").value("2024-02-29"));

        mvc.perform(get("/api/v1/reports/top-products").with(user("10").roles("OWNER"))
                        .param("from", "2023-03-01").param("to", "2024-03-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void topProductsValidatesEffectiveMonthInsteadOfIgnoredDateRange() throws Exception {
        mvc.perform(get("/api/v1/reports/top-products").with(user("10").roles("OWNER"))
                        .param("month", "2024-02")
                        .param("from", "0001-01-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2024-02-01"))
                .andExpect(jsonPath("$.period.to").value("2024-02-29"));
    }
}
