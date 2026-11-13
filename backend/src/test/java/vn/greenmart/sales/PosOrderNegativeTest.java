package vn.greenmart.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.service.QuoteService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
@ActiveProfiles("local-dev")
class PosOrderNegativeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

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
    private QuoteService quoteService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private PosIdempotencyService posIdempotencyService;

    private static final long CASHIER_USER_ID = 8888L;
    private static final long STOREKEEPER_USER_ID = 8889L;

    private Long branch1Id;
    private Long branch2Id;
    private Long productAId;
    private Long productBId;

    private final List<Long> insertedBranchIds = new ArrayList<>();
    private final List<Long> insertedProductIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanTestData();

        // Kênh bán POS bắt buộc khi tạo đơn
        jdbcTemplate.execute("""
            INSERT INTO sales_channel (code, name, safety_stock)
            VALUES ('POS', 'Bán tại quầy', 0)
            ON CONFLICT (code) DO NOTHING
        """);

        // Chi nhánh test
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-POS-CN1', 'GreenMart Test CN1', '123 Lê Lợi', true)
            ON CONFLICT (code) DO NOTHING
        """);
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-POS-CN2', 'GreenMart Test CN2', '456 Hai Bà Trưng', true)
            ON CONFLICT (code) DO NOTHING
        """);

        branch1Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-POS-CN1'", Long.class);
        branch2Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-POS-CN2'", Long.class);
        insertedBranchIds.add(branch1Id);
        insertedBranchIds.add(branch2Id);

        // Danh mục & Sản phẩm
        jdbcTemplate.execute("""
            INSERT INTO category (name)
            VALUES ('Tiêu dùng nhanh')
            ON CONFLICT DO NOTHING
        """);
        Long categoryId = jdbcTemplate.queryForObject("SELECT id FROM category LIMIT 1", Long.class);

        jdbcTemplate.execute(String.format("""
            INSERT INTO product (sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES ('SP-GAO-ST25', 'Gạo ST25', 'túi', %d, 100000, 80000, true)
            ON CONFLICT (sku) DO NOTHING
        """, categoryId));
        jdbcTemplate.execute(String.format("""
            INSERT INTO product (sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES ('SP-SUA-TH-1L', 'Sữa TH 1L', 'hộp', %d, 30000, 20000, true)
            ON CONFLICT (sku) DO NOTHING
        """, categoryId));

        productAId = jdbcTemplate.queryForObject("SELECT id FROM product WHERE sku = 'SP-GAO-ST25'", Long.class);
        productBId = jdbcTemplate.queryForObject("SELECT id FROM product WHERE sku = 'SP-SUA-TH-1L'", Long.class);
        insertedProductIds.add(productAId);
        insertedProductIds.add(productBId);

        // Tài khoản test: CASHIER gán chi nhánh 1, STOREKEEPER gán chi nhánh 1
        jdbcTemplate.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'cashier_user', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân Test', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """, CASHIER_USER_ID));
        jdbcTemplate.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'storekeeper_user', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thủ Kho Test', 'STOREKEEPER', true)
            ON CONFLICT (id) DO NOTHING
        """, STOREKEEPER_USER_ID));

        jdbcTemplate.execute(String.format("INSERT INTO user_branch (user_id, branch_id) VALUES (%d, %d) ON CONFLICT DO NOTHING", CASHIER_USER_ID, branch1Id));
        jdbcTemplate.execute(String.format("INSERT INTO user_branch (user_id, branch_id) VALUES (%d, %d) ON CONFLICT DO NOTHING", STOREKEEPER_USER_ID, branch1Id));

        // Thiết lập tồn kho ban đầu
        jdbcTemplate.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (%d, %d, 10, 0, 1, NOW())
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 10, reserved = 0
        """, branch1Id, productAId));
        jdbcTemplate.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (%d, %d, 5, 0, 1, NOW())
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 5, reserved = 0
        """, branch1Id, productBId));

        // Mock mặc định cho ProductService & InventoryService
        given(productService.getSaleInfo(productAId)).willReturn(new ProductSaleInfo(productAId, "SP-GAO-ST25", "Gạo ST25", "túi", 100000L, true));
        given(productService.getSaleInfo(productBId)).willReturn(new ProductSaleInfo(productBId, "SP-SUA-TH-1L", "Sữa TH 1L", "hộp", 30000L, true));
        given(inventoryService.getAvailable(eq(branch1Id), eq(productAId))).willReturn(10);
        given(inventoryService.getAvailable(eq(branch1Id), eq(productBId))).willReturn(5);

        // Bỏ qua lớp kiểm tra Idempotency để gọi trực tiếp tới PosOrderService
        given(posIdempotencyService.execute(any(), any(), any())).willAnswer(invocation -> {
        java.util.function.Supplier<?> action = invocation.getArgument(2);
        if (action == null) {
            return null;
        }
        return new IdempotencyResult(true, (OrderResponse) action.get());
    });
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        jdbcTemplate.execute("DELETE FROM outbox_event");
        jdbcTemplate.execute("DELETE FROM order_item");
        jdbcTemplate.execute("DELETE FROM payment");
        jdbcTemplate.execute("DELETE FROM sales_order");
        jdbcTemplate.execute("DELETE FROM inventory_transaction");
        jdbcTemplate.execute(String.format("DELETE FROM user_branch WHERE user_id IN (%d, %d)", CASHIER_USER_ID, STOREKEEPER_USER_ID));
        jdbcTemplate.execute(String.format("DELETE FROM app_user WHERE id IN (%d, %d)", CASHIER_USER_ID, STOREKEEPER_USER_ID));
    }

    private PosOrderRequest createStandardRequest(Long branchId, Long productId, int quantity, Long cashReceived, Long expectedTotal) {
        return new PosOrderRequest(
                branchId,
                null,
                List.of(new OrderLineRequest(productId, quantity)),
                null,
                new PaymentRequest(PaymentMethod.CASH, cashReceived, PaymentSimulation.SUCCESS),
                expectedTotal
        );
    }

    private void mockStandardQuote(Long branchId, Long productId, int quantity, long total) {
        QuoteLine line = new QuoteLine(
                productId, "SP-GAO-ST25", "Gạo ST25", "túi", quantity,
                total, 0L, total, null, 10, true
        );
        Quote quote = new Quote(
                branchId, null, List.of(line), null,
                total, 0L, 0L, 0L, 0L, total, true
        );
        given(quoteService.calculateQuote(any(QuoteRequest.class))).willReturn(quote);
    }

    private void assertStandardErrorResponse(MvcResult result, String expectedCode) throws Exception {
        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.hasNonNull("code")).isTrue();
        assertThat(root.get("code").asText()).isEqualTo(expectedCode);

        assertThat(root.hasNonNull("message")).isTrue();
        assertThat(root.get("message").asText()).isNotBlank();

        assertThat(root.hasNonNull("traceId")).isTrue();
        assertThat(root.get("traceId").asText()).isNotBlank();

        // NFR-06: Tuyệt đối không để lộ stack trace Java
        assertThat(json).doesNotContain("at vn.greenmart");
        assertThat(json).doesNotContain(".java:");
    }

    private void assertDatabaseStateUnchanged() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();

        // Tồn kho không bị trừ
        Integer onHandA = jdbcTemplate.queryForObject("SELECT on_hand FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, branch1Id, productAId);
        Integer onHandB = jdbcTemplate.queryForObject("SELECT on_hand FROM inventory WHERE branch_id = ? AND product_id = ?", Integer.class, branch1Id, productBId);
        assertThat(onHandA).isEqualTo(10);
        assertThat(onHandB).isEqualTo(5);
    }

    // NHÓM 1: Lỗi Dữ liệu & Tham số đầu vào (HTTP 400 VALIDATION_ERROR)
    @Nested
    @DisplayName("Nhóm 1: Lỗi Tham số & Dữ liệu đầu vào (HTTP 400)")
    class ValidationErrors {

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-400-01: Thiếu header Idempotency-Key bắt buộc")
        void testMissingIdempotencyKeyHeader_ShouldReturn400() throws Exception {
            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 1, 100000L, 100000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andReturn();

            assertStandardErrorResponse(result, "VALIDATION_ERROR");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-400-02: Giỏ hàng rỗng (items: [])")
        void testEmptyItemsList_ShouldReturn400() throws Exception {
            PosOrderRequest request = new PosOrderRequest(
                    branch1Id, null, Collections.emptyList(), null,
                    new PaymentRequest(PaymentMethod.CASH, 100000L, PaymentSimulation.SUCCESS),
                    100000L
            );

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details").isArray())
                    .andReturn();

            assertStandardErrorResponse(result, "VALIDATION_ERROR");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-400-03: Số lượng mua <= 0 (quantity = 0 hoặc âm)")
        void testInvalidQuantity_ShouldReturn400() throws Exception {
            PosOrderRequest request = new PosOrderRequest(
                    branch1Id, null,
                    List.of(new OrderLineRequest(productAId, 0)),
                    null,
                    new PaymentRequest(PaymentMethod.CASH, 100000L, PaymentSimulation.SUCCESS),
                    100000L
            );

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andReturn();

            assertStandardErrorResponse(result, "VALIDATION_ERROR");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-400-04: Thiếu trường bắt buộc (branchId = null)")
        void testMissingRequiredFields_ShouldReturn400() throws Exception {
            PosOrderRequest request = new PosOrderRequest(
                    null, null,
                    List.of(new OrderLineRequest(productAId, 1)),
                    null,
                    new PaymentRequest(PaymentMethod.CASH, 100000L, PaymentSimulation.SUCCESS),
                    null
            );

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andReturn();

            assertStandardErrorResponse(result, "VALIDATION_ERROR");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-400-05: JSON body sai cú pháp")
        void testMalformedJsonBody_ShouldReturn400() throws Exception {
            String malformedJson = "{ \"branchId\": 1, \"items\": [ ";

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andReturn();

            assertStandardErrorResponse(result, "VALIDATION_ERROR");
            assertDatabaseStateUnchanged();
        }
    }

    // NHÓM 2: Lỗi Phân quyền & Giới hạn Chi nhánh (HTTP 401 & 403)
    @Nested
    @DisplayName("Nhóm 2: Lỗi Phân quyền & Tài nguyên (HTTP 403)")
    class AuthAndSecurityErrors {

        @Test
        @WithMockUser(username = "8889", roles = "STOREKEEPER")
        @DisplayName("TC-403-01: Sai vai trò (STOREKEEPER cố tình gọi POS)")
        void testRoleStorekeeper_ShouldReturn403Forbidden() throws Exception {
            mockStandardQuote(branch1Id, productAId, 1, 100000L);
            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 1, 100000L, 100000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andReturn();

            assertStandardErrorResponse(result, "FORBIDDEN");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-403-02: Sai chi nhánh (Thu ngân CN1 tạo đơn cho CN2 - BR-11, EX-11)")
        void testBranchMismatch_ShouldReturn403BranchForbidden() throws Exception {
            mockStandardQuote(branch2Id, productAId, 1, 100000L);
            // Request tạo đơn cho chi nhánh 2 trong khi thu ngân 8888 chỉ được gán chi nhánh 1
            PosOrderRequest request = createStandardRequest(branch2Id, productAId, 1, 100000L, 100000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"))
                    .andReturn();

            assertStandardErrorResponse(result, "BRANCH_FORBIDDEN");
            assertDatabaseStateUnchanged();
        }
    }

    // NHÓM 3: Lỗi Xung đột & Thiếu hàng (HTTP 409 Conflict)
    @Nested
    @DisplayName("Nhóm 3: Lỗi Xung đột & Thiếu hàng (HTTP 409)")
    class ConflictErrors {

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-409-01: Thiếu tồn kho khả dụng (INSUFFICIENT_STOCK kèm details.lines)")
        void testInsufficientStock_ShouldReturn409WithDetailsLines() throws Exception {
            mockStandardQuote(branch1Id, productAId, 2, 200000L);

            // Giả lập tồn khả dụng của sản phẩm A chỉ còn 1 nhưng đơn mua 2
            given(inventoryService.getAvailable(branch1Id, productAId)).willReturn(1);

            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 2, 200000L, 200000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.details.lines").isArray())
                    .andExpect(jsonPath("$.details.lines[0].productId").value(productAId))
                    .andExpect(jsonPath("$.details.lines[0].sku").value("SP-GAO-ST25"))
                    .andExpect(jsonPath("$.details.lines[0].requested").value(2))
                    .andExpect(jsonPath("$.details.lines[0].available").value(1))
                    .andReturn();

            assertStandardErrorResponse(result, "INSUFFICIENT_STOCK");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-409-02: Xung đột Idempotency (IDEMPOTENCY_CONFLICT khi dùng lại key nhưng đổi body)")
        void testIdempotencyConflict_ShouldReturn409() throws Exception {
            String key = UUID.randomUUID().toString();
            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 1, 100000L, 100000L);

           org.mockito.Mockito.doThrow(new AppException(ErrorCode.IDEMPOTENCY_CONFLICT, "Yêu cầu đang được xử lý hoặc trùng lặp với nội dung khác"))
            .when(posIdempotencyService).execute(eq(key), any(), any());

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
            .andReturn();

            assertStandardErrorResponse(result, "IDEMPOTENCY_CONFLICT");
            assertDatabaseStateUnchanged();
        }
    }

    // NHÓM 4: Vi phạm Quy tắc Nghiệp vụ (HTTP 422 Unprocessable Entity)
    @Nested
    @DisplayName("Nhóm 4: Vi phạm Quy tắc Nghiệp vụ (HTTP 422)")
    class BusinessRuleErrors {

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-422-01: Tổng tiền bị đổi (TOTAL_CHANGED kèm details.quote)")
        void testTotalChanged_ShouldReturn422WithQuote() throws Exception {
            // Giá tính lại thực tế là 100.000 đ
            mockStandardQuote(branch1Id, productAId, 1, 100000L);

            // Khách gửi expectedTotal = 90.000 đ
            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 1, 100000L, 90000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("TOTAL_CHANGED"))
                    .andExpect(jsonPath("$.details.quote").exists())
                    .andExpect(jsonPath("$.details.quote.total").value(100000))
                    .andReturn();

            assertStandardErrorResponse(result, "TOTAL_CHANGED");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-422-02: Tiền mặt khách đưa không đủ (CASH_INSUFFICIENT)")
        void testCashInsufficient_ShouldReturn422() throws Exception {
            mockStandardQuote(branch1Id, productAId, 1, 100000L);

            // Đơn 100.000 đ nhưng khách chỉ đưa 80.000 đ
            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 1, 80000L, 100000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                    .andExpect(jsonPath("$.details.total").value(100000))
                    .andExpect(jsonPath("$.details.cashReceived").value(80000))
                    .andReturn();

            assertStandardErrorResponse(result, "CASH_INSUFFICIENT");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-422-03: Giả lập thanh toán thẻ thất bại (PAYMENT_FAILED)")
        void testPaymentFailed_ShouldReturn422() throws Exception {
            mockStandardQuote(branch1Id, productAId, 1, 100000L);

            PosOrderRequest request = new PosOrderRequest(
                    branch1Id, null,
                    List.of(new OrderLineRequest(productAId, 1)),
                    null,
                    new PaymentRequest(PaymentMethod.CARD, null, PaymentSimulation.FAILED),
                    100000L
            );

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"))
                    .andReturn();

            assertStandardErrorResponse(result, "PAYMENT_FAILED");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-422-04: Giả lập thanh toán QR quá thời gian chờ (PAYMENT_TIMEOUT)")
        void testPaymentTimeout_ShouldReturn422() throws Exception {
            mockStandardQuote(branch1Id, productAId, 1, 100000L);

            PosOrderRequest request = new PosOrderRequest(
                    branch1Id, null,
                    List.of(new OrderLineRequest(productAId, 1)),
                    null,
                    new PaymentRequest(PaymentMethod.QR, null, PaymentSimulation.TIMEOUT),
                    100000L
            );

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PAYMENT_TIMEOUT"))
                    .andReturn();

            assertStandardErrorResponse(result, "PAYMENT_TIMEOUT");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-422-05: Sản phẩm đã ngưng kinh doanh (PRODUCT_INACTIVE)")
        void testProductInactive_ShouldReturn422() throws Exception {
            mockStandardQuote(branch1Id, productAId, 1, 100000L);

            // Giả lập sản phẩm A ngưng kinh doanh (active = false)
            given(productService.getSaleInfo(productAId))
                    .willReturn(new ProductSaleInfo(productAId, "SP-GAO-ST25", "Gạo ST25", "túi", 100000L, false));

            PosOrderRequest request = createStandardRequest(branch1Id, productAId, 1, 100000L, 100000L);

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"))
                    .andReturn();

            assertStandardErrorResponse(result, "PRODUCT_INACTIVE");
            assertDatabaseStateUnchanged();
        }

        @Test
        @WithMockUser(username = "8888", roles = "CASHIER")
        @DisplayName("TC-422-06: Giảm giá tay vi phạm độ dài lý do (< 5 ký tự)")
        void testManualDiscountInvalid_ShouldReturn400() throws Exception {
            PosOrderRequest request = new PosOrderRequest(
                    branch1Id, null,
                    List.of(new OrderLineRequest(productAId, 1)),
                    new ManualDiscount(5000L, "ngan"), // 4 ký tự -> vi phạm validation
                    new PaymentRequest(PaymentMethod.CASH, 100000L, PaymentSimulation.SUCCESS),
                    100000L
            );

            MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value("manualDiscount.reason"))
                    .andReturn();

            assertStandardErrorResponse(result, "VALIDATION_ERROR");
            assertDatabaseStateUnchanged();
        }
    }
}
