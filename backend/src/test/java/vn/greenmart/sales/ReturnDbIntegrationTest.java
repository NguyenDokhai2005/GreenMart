package vn.greenmart.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.domain.*;
import vn.greenmart.sales.dto.CreateReturnRequest;
import vn.greenmart.sales.dto.ReturnItemRequest;
import vn.greenmart.sales.dto.ReturnResponse;
import vn.greenmart.sales.repository.*;
import vn.greenmart.sales.service.ReturnService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ReturnDbIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ReturnService returnService;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long branch1Id;
    private Long branch2Id;
    private Long posChannelId;
    private Long productResellId;
    private Long productDamagedId;

    private static final long CASHIER_ID = 9101L;
    private static final long MANAGER_ID = 9102L;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-RET-1', 'GreenMart Trả Hàng CN1', '12 Nguyễn Huệ', true),
                   ('GM-RET-2', 'GreenMart Trả Hàng CN2', '34 Lê Duẩn', true)
            ON CONFLICT (code) DO NOTHING
        """);
        branch1Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-RET-1'", Long.class);
        branch2Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-RET-2'", Long.class);
        posChannelId = jdbcTemplate.queryForObject("SELECT id FROM sales_channel WHERE code = 'POS'", Long.class);

        jdbcTemplate.execute("""
            INSERT INTO category (id, name) VALUES (9100, 'Danh mục Test Trả Hàng')
            ON CONFLICT (id) DO NOTHING
        """);
        jdbcTemplate.execute("""
            INSERT INTO product (sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES ('RET-SKU-01', 'Sữa hộp trả lại', 'hộp', 9100, 30000, 22000, true),
                   ('RET-SKU-02', 'Rau cải hư hỏng', 'bó', 9100, 18000, 10000, true)
            ON CONFLICT (sku) DO NOTHING
        """);
        productResellId = jdbcTemplate.queryForObject("SELECT id FROM product WHERE sku = 'RET-SKU-01'", Long.class);
        productDamagedId = jdbcTemplate.queryForObject("SELECT id FROM product WHERE sku = 'RET-SKU-02'", Long.class);

        jdbcTemplate.update("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version)
            VALUES (?, ?, 10, 0, 1), (?, ?, 10, 0, 1)
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 10, reserved = 0
        """, branch2Id, productResellId, branch2Id, productDamagedId);

        jdbcTemplate.update("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (?, 'cashier_ret_91', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân Trả Hàng', 'CASHIER', true),
                   (?, 'manager_ret_92', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Quản Lý Trả Hàng', 'BRANCH_MANAGER', true)
            ON CONFLICT (id) DO NOTHING
        """, CASHIER_ID, MANAGER_ID);

        jdbcTemplate.update("INSERT INTO user_branch (user_id, branch_id) VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING",
                CASHIER_ID, branch2Id, MANAGER_ID, branch2Id);
    }

    @Test
    @WithMockUser(username = "9101", roles = "CASHIER")
    @DisplayName("ORD-04 Integration: Trả 1 phần (RESELLABLE + DAMAGED) -> cộng kho RESELLABLE, xuất hủy DAMAGED, giữ COMPLETED; trả nốt phần còn lại -> RETURNED")
    void testPartialThenFullReturn_InventoryLedgerAndOrderStatusAndV4ApprovedAt() {
        SalesOrder order = new SalesOrder();
        order.setCode("POS-RET-TEST-01");
        order.setChannelId(posChannelId);
        order.setBranchId(branch1Id);
        order.setStatus(OrderStatus.COMPLETED);
        order.setSubtotal(78_000L);
        order.setDiscount(0L);
        order.setTotal(78_000L);
        order.setCreatedBy(CASHIER_ID);
        order.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        order.setCompletedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        order = salesOrderRepository.saveAndFlush(order);

        OrderItem oi1 = new OrderItem();
        oi1.setOrderId(order.getId());
        oi1.setProductId(productResellId);
        oi1.setQuantity(2);
        oi1.setUnitPrice(30_000L);
        oi1.setDiscount(0L);
        oi1.setLineTotal(60_000L);
        oi1 = orderItemRepository.saveAndFlush(oi1);

        OrderItem oi2 = new OrderItem();
        oi2.setOrderId(order.getId());
        oi2.setProductId(productDamagedId);
        oi2.setQuantity(1);
        oi2.setUnitPrice(18_000L);
        oi2.setDiscount(0L);
        oi2.setLineTotal(18_000L);
        oi2 = orderItemRepository.saveAndFlush(oi2);

        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setMethod(PaymentMethod.CASH);
        payment.setAmount(78_000L);
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(order.getCompletedAt());
        paymentRepository.saveAndFlush(payment);

        // 1. Thu ngân lập phiếu trả 1 phần tại CN2: 1 sữa RESELLABLE + 1 rau DAMAGED
        ReturnResponse draft1 = returnService.createReturn(new CreateReturnRequest(
                order.getId(),
                branch2Id,
                "Khách trả 1 hộp sữa nguyên và 1 bó rau hỏng",
                List.of(
                        new ReturnItemRequest(oi1.getId(), 1, ReturnItemCondition.RESELLABLE),
                        new ReturnItemRequest(oi2.getId(), 1, ReturnItemCondition.DAMAGED)
                )
        ));
        assertEquals(ReturnStatus.DRAFT, draft1.status());
        assertEquals(48_000L, draft1.refundAmount());
        assertNull(draft1.approvedAt());

        // Chuyển sang quyền Quản lý chi nhánh để duyệt phiếu 1
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        String.valueOf(MANAGER_ID),
                        null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BRANCH_MANAGER"))
                )
        );

        ReturnResponse approved1 = returnService.approveReturn(draft1.id());
        assertEquals(ReturnStatus.APPROVED, approved1.status());
        assertNotNull(approved1.approvedAt(), "Migration V4: approved_at bắt buộc không null khi APPROVED");
        assertFalse(approved1.approvedAt().isBefore(approved1.createdAt()));

        Integer resellOnHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE branch_id = ? AND product_id = ?",
                Integer.class, branch2Id, productResellId
        );
        Integer damagedOnHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE branch_id = ? AND product_id = ?",
                Integer.class, branch2Id, productDamagedId
        );
        assertEquals(11, resellOnHand);
        assertEquals(10, damagedOnHand);

        var resellTx = jdbcTemplate.queryForList(
                "SELECT type, delta_on_hand FROM inventory_transaction WHERE ref_type = 'RETURN_DOCUMENT' AND ref_id = ? AND product_id = ?",
                draft1.id(), productResellId
        );
        assertEquals(1, resellTx.size());
        assertEquals("RETURN", resellTx.get(0).get("type"));
        assertEquals(1, resellTx.get(0).get("delta_on_hand"));

        var damagedTx = jdbcTemplate.queryForList(
                "SELECT type, delta_on_hand FROM inventory_transaction WHERE ref_type = 'RETURN_DOCUMENT' AND ref_id = ? AND product_id = ? ORDER BY id ASC",
                draft1.id(), productDamagedId
        );
        assertEquals(2, damagedTx.size());
        assertEquals("RETURN", damagedTx.get(0).get("type"));
        assertEquals("EXPORT", damagedTx.get(1).get("type"));

        SalesOrder afterPartial = salesOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, afterPartial.getStatus());

        // 2. Lập và duyệt phiếu trả nốt 1 hộp sữa cuối cùng (Thu ngân lập -> Quản lý duyệt)
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        String.valueOf(CASHIER_ID),
                        null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CASHIER"))
                )
        );
        ReturnResponse draft2 = returnService.createReturn(new CreateReturnRequest(
                order.getId(),
                branch2Id,
                "Khách trả nốt hộp sữa còn lại",
                List.of(new ReturnItemRequest(oi1.getId(), 1, ReturnItemCondition.RESELLABLE))
        ));

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        String.valueOf(MANAGER_ID),
                        null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BRANCH_MANAGER"))
                )
        );
        returnService.approveReturn(draft2.id());

        SalesOrder afterFull = salesOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.RETURNED, afterFull.getStatus());

        List<Payment> paymentsAfter = paymentRepository.findByOrderId(order.getId());
        assertEquals(PaymentStatus.REFUNDED, paymentsAfter.get(0).getStatus());
    }

    // Test HTTP qua SecurityConfig cho 401 chưa đăng nhập
    @Test
    @DisplayName("HTTP Security: Chưa đăng nhập gọi API /returns -> 401 UNAUTHORIZED")
    void testHttp_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/returns").param("orderId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // Test HTTP RBAC cho STOREKEEPER (403 cả 5 endpoint)
    @Test
    @WithMockUser(username = "9103", roles = "STOREKEEPER")
    @DisplayName("HTTP RBAC: STOREKEEPER gọi cả 5 endpoint /returns -> đều 403 FORBIDDEN")
    void testHttp_Storekeeper_ForbiddenAll5Endpoints() throws Exception {
        String body = """
            {
              "orderId": 1,
              "branchId": %d,
              "reason": "Trả hàng test quyền",
              "items": [{ "orderItemId": 1, "quantity": 1, "condition": "RESELLABLE" }]
            }
        """.formatted(branch2Id);

        mockMvc.perform(post("/api/v1/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/returns").param("orderId", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/returns/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/returns/1/approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/returns/1/cancel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // [THÊM THEO REVIEW #410]: Test HTTP RBAC cho ADMIN (403 cả 5 endpoint)
    @Test
    @WithMockUser(username = "9104", roles = "ADMIN")
    @DisplayName("HTTP RBAC: ADMIN gọi cả 5 endpoint /returns -> đều 403 FORBIDDEN")
    void testHttp_Admin_ForbiddenAll5Endpoints() throws Exception {
        String body = """
            {
              "orderId": 1,
              "branchId": %d,
              "reason": "Trả hàng test quyền admin",
              "items": [{ "orderItemId": 1, "quantity": 1, "condition": "RESELLABLE" }]
            }
        """.formatted(branch2Id);

        mockMvc.perform(post("/api/v1/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/returns").param("orderId", "1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/returns/1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/returns/1/approve"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/returns/1/cancel"))
                .andExpect(status().isForbidden());
    }

    // Test HTTP đầy đủ luồng Controller/SecurityConfig
    @Test
    @WithMockUser(username = "9101", roles = "CASHIER")
    @DisplayName("HTTP RBAC & Lifecycle: CASHIER tạo 201 và xem 200 nhưng duyệt/hủy 403; QLCN khác CN 403 BRANCH_FORBIDDEN; duyệt lại phiếu đã APPROVED 409 INVALID_STATUS; quá hạn/vượt SL 422")
    void testHttp_CashierCreate_ForbiddenApproveCancel_BranchForbidden_DoubleApprove409_Br10Returns422() throws Exception {
        SalesOrder order = new SalesOrder();
        order.setCode("POS-HTTP-RET-01");
        order.setChannelId(posChannelId);
        order.setBranchId(branch1Id);
        order.setStatus(OrderStatus.COMPLETED);
        order.setSubtotal(60_000L);
        order.setDiscount(0L);
        order.setTotal(60_000L);
        order.setCreatedBy(CASHIER_ID);
        order.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        order.setCompletedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        order = salesOrderRepository.saveAndFlush(order);

        OrderItem oi = new OrderItem();
        oi.setOrderId(order.getId());
        oi.setProductId(productResellId);
        oi.setQuantity(2);
        oi.setUnitPrice(30_000L);
        oi.setDiscount(0L);
        oi.setLineTotal(60_000L);
        oi = orderItemRepository.saveAndFlush(oi);

        // 1. Vượt số lượng mua (mua 2, đòi trả 3) -> HTTP 422 RETURN_QUANTITY_EXCEEDED
        String exceedQtyBody = """
            {
              "orderId": %d,
              "branchId": %d,
              "reason": "Trả vượt số lượng",
              "items": [{ "orderItemId": %d, "quantity": 3, "condition": "RESELLABLE" }]
            }
        """.formatted(order.getId(), branch2Id, oi.getId());

        mockMvc.perform(post("/api/v1/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exceedQtyBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.details[0].field").value("items[0].quantity"));

        // 2. CASHIER lập phiếu hợp lệ tại CN2 -> 201 CREATED
        String validBody = """
            {
              "orderId": %d,
              "branchId": %d,
              "reason": "Khách đổi ý trả 1 hộp",
              "items": [{ "orderItemId": %d, "quantity": 1, "condition": "RESELLABLE" }]
            }
        """.formatted(order.getId(), branch2Id, oi.getId());

        String createdJson = mockMvc.perform(post("/api/v1/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        long returnId = objectMapper.readTree(createdJson).get("id").asLong();

        // CASHIER xem được chi tiết & danh sách -> 200 OK
        mockMvc.perform(get("/api/v1/returns/{id}", returnId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/returns").param("orderId", order.getId().toString()))
                .andExpect(status().isOk());

        // CASHIER cố duyệt hoặc hủy -> 403 FORBIDDEN
        mockMvc.perform(post("/api/v1/returns/{id}/approve", returnId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/returns/{id}/cancel", returnId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 3. QLCN của CN1 (không được gán CN2) cố duyệt phiếu của CN2 -> 403 BRANCH_FORBIDDEN
        jdbcTemplate.update("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (9105, 'manager_cn1_9105', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'QLCN 1', 'BRANCH_MANAGER', true)
            ON CONFLICT (id) DO NOTHING
        """);
        jdbcTemplate.update("INSERT INTO user_branch (user_id, branch_id) VALUES (9105, ?) ON CONFLICT DO NOTHING", branch1Id);

        mockMvc.perform(post("/api/v1/returns/{id}/approve", returnId)
                        .with(user("9105").roles("BRANCH_MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));

        // 4. QLCN của CN2 (9102) duyệt lần 1 -> 200 APPROVED; duyệt lại lần 2 -> 409 INVALID_STATUS
        mockMvc.perform(post("/api/v1/returns/{id}/approve", returnId)
                        .with(user(String.valueOf(MANAGER_ID)).roles("BRANCH_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/v1/returns/{id}/approve", returnId)
                        .with(user(String.valueOf(MANAGER_ID)).roles("BRANCH_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        // 5. Đơn quá hạn 7 ngày -> HTTP 422 RETURN_WINDOW_EXPIRED
        order.setCompletedAt(Instant.now().minus(8, ChronoUnit.DAYS));
        salesOrderRepository.saveAndFlush(order);

        mockMvc.perform(post("/api/v1/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_WINDOW_EXPIRED"));
    }
}
