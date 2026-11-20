package vn.greenmart.sales.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.branch.service.BranchService;
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.identity.service.AuthService;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.*;
import vn.greenmart.sales.dto.CreateReturnRequest;
import vn.greenmart.sales.dto.ReturnItemRequest;
import vn.greenmart.sales.dto.ReturnResponse;
import vn.greenmart.sales.repository.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnServiceTest {

    @Mock private ReturnDocumentRepository returnDocumentRepository;
    @Mock private ReturnItemRepository returnItemRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private InventoryService inventoryService;
    @Mock private BranchService branchService;
    @Mock private ProductService productService;
    @Mock private AuthService authService;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private OutboxService outboxService;
    @Mock private JdbcTemplate jdbcTemplate;

    private ReturnService returnService;

    @BeforeEach
    void setUp() {
        returnService = new ReturnServiceImpl(
                returnDocumentRepository,
                returnItemRepository,
                salesOrderRepository,
                orderItemRepository,
                paymentRepository,
                inventoryService,
                branchService,
                productService,
                authService,
                auditLogRepository,
                outboxService,
                jdbcTemplate
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String role, String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("BA mục 5.2 & BR-10: Trả 1/3 bó rau cải với phân bổ KM cấp đơn 10% -> hoàn đúng 16.200đ")
    void testCreateReturn_BaExampleRefundCalculation_Returns16200() {
        authenticate("OWNER", "1");
        when(branchService.getSaleInfo(1L)).thenReturn(new BranchSaleInfo(1L, "GM-001", "GreenMart Quận 1"));

        SalesOrder order = new SalesOrder();
        order.setId(100L);
        order.setCode("POS-GM001-20261118-0001");
        order.setBranchId(1L);
        order.setStatus(OrderStatus.COMPLETED);
        order.setSubtotal(238_000L);
        order.setDiscount(37_300L);
        order.setTotal(200_700L);
        order.setCompletedAt(Instant.now().minus(5, ChronoUnit.DAYS));

        OrderItem itemMilk = new OrderItem();
        itemMilk.setId(1L);
        itemMilk.setOrderId(100L);
        itemMilk.setProductId(10L);
        itemMilk.setQuantity(2);
        itemMilk.setUnitPrice(32_000L);
        itemMilk.setDiscount(0L);
        itemMilk.setLineTotal(64_000L);

        OrderItem itemVeggie = new OrderItem();
        itemVeggie.setId(2L);
        itemVeggie.setOrderId(100L);
        itemVeggie.setProductId(20L);
        itemVeggie.setQuantity(3);
        itemVeggie.setUnitPrice(18_000L);
        itemVeggie.setDiscount(0L);
        itemVeggie.setLineTotal(54_000L);

        OrderItem itemRice = new OrderItem();
        itemRice.setId(3L);
        itemRice.setOrderId(100L);
        itemRice.setProductId(30L);
        itemRice.setQuantity(1);
        itemRice.setUnitPrice(120_000L);
        itemRice.setDiscount(15_000L);
        itemRice.setLineTotal(105_000L);

        when(jdbcTemplate.queryForList(eq("SELECT id FROM sales_order WHERE id = ? FOR UPDATE"), eq(Long.class), eq(100L)))
                .thenReturn(List.of(100L));
        when(salesOrderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(100L)).thenReturn(List.of(itemMilk, itemVeggie, itemRice));
        when(returnItemRepository.sumReturnedQuantitiesByOrder(eq(100L), anyCollection(), isNull()))
                .thenReturn(List.of());
        // [SỬA THEO REVIEW #410]: Stub theo bảng mới return_sequence
        when(jdbcTemplate.queryForObject(contains("INSERT INTO return_sequence"), eq(Long.class), eq(1L), any(LocalDate.class)))
                .thenReturn(1L);
        when(productService.getSaleInfo(20L)).thenReturn(new ProductSaleInfo(20L, "RAU-CAI", "Rau cải 500g", "bó", 18_000L, true));
        when(authService.getFullName(1L)).thenReturn("Chủ Chuỗi");
        when(returnDocumentRepository.saveAndFlush(any(ReturnDocument.class))).thenAnswer(inv -> {
            ReturnDocument d = inv.getArgument(0);
            d.setId(501L);
            return d;
        });

        CreateReturnRequest req = new CreateReturnRequest(
                100L,
                1L,
                "Rau bị dập lá",
                List.of(new ReturnItemRequest(2L, 1, ReturnItemCondition.DAMAGED))
        );

        ReturnResponse res = returnService.createReturn(req);
        assertEquals(16_200L, res.refundAmount());
        assertEquals(16_200L, res.items().get(0).refundAmount());
        assertEquals(ReturnStatus.DRAFT, res.status());
    }

    @Test
    @DisplayName("BR-10: Đơn hoàn tất cách đây 8 ngày -> trả 422 RETURN_WINDOW_EXPIRED")
    void testCreateReturn_Expired7DaysWindow_ThrowsReturnWindowExpired() {
        authenticate("OWNER", "1");
        when(branchService.getSaleInfo(1L)).thenReturn(new BranchSaleInfo(1L, "GM-001", "GreenMart Quận 1"));

        SalesOrder order = new SalesOrder();
        order.setId(101L);
        order.setBranchId(1L);
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(8, ChronoUnit.DAYS));

        when(jdbcTemplate.queryForList(eq("SELECT id FROM sales_order WHERE id = ? FOR UPDATE"), eq(Long.class), eq(101L)))
                .thenReturn(List.of(101L));
        when(salesOrderRepository.findById(101L)).thenReturn(Optional.of(order));

        CreateReturnRequest req = new CreateReturnRequest(
                101L,
                1L,
                "Đổi ý trả hàng",
                List.of(new ReturnItemRequest(1L, 1, ReturnItemCondition.RESELLABLE))
        );

        // [SỬA THEO REVIEW #410]: Khẳng định mã lỗi 422 RETURN_WINDOW_EXPIRED
        AppException ex = assertThrows(AppException.class, () -> returnService.createReturn(req));
        assertEquals(ErrorCode.RETURN_WINDOW_EXPIRED.name(), ex.getCode());
    }

    @Test
    @DisplayName("Số lượng trả vượt số còn lại (mua 3, đã trả 2, đòi trả tiếp 2) -> trả 422 RETURN_QUANTITY_EXCEEDED")
    void testCreateReturn_QuantityExceedsRemaining_ThrowsReturnQuantityExceeded() {
        authenticate("OWNER", "1");
        when(branchService.getSaleInfo(1L)).thenReturn(new BranchSaleInfo(1L, "GM-001", "GreenMart Quận 1"));

        SalesOrder order = new SalesOrder();
        order.setId(102L);
        order.setCode("POS-002");
        order.setBranchId(1L);
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        OrderItem oi = new OrderItem();
        oi.setId(10L);
        oi.setOrderId(102L);
        oi.setProductId(55L);
        oi.setQuantity(3);
        oi.setLineTotal(90_000L);

        ReturnItemRepository.ReturnedQtyProjection prev = new ReturnItemRepository.ReturnedQtyProjection() {
            @Override public Long getOrderItemId() { return 10L; }
            @Override public Long getTotalQuantity() { return 2L; }
        };

        when(jdbcTemplate.queryForList(eq("SELECT id FROM sales_order WHERE id = ? FOR UPDATE"), eq(Long.class), eq(102L)))
                .thenReturn(List.of(102L));
        when(salesOrderRepository.findById(102L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(102L)).thenReturn(List.of(oi));
        when(returnItemRepository.sumReturnedQuantitiesByOrder(eq(102L), anyCollection(), isNull()))
                .thenReturn(List.of(prev));

        CreateReturnRequest req = new CreateReturnRequest(
                102L,
                1L,
                "Trả thêm 2 sản phẩm",
                List.of(new ReturnItemRequest(10L, 2, ReturnItemCondition.RESELLABLE))
        );

        // [SỬA THEO REVIEW #410]: Khẳng định mã lỗi 422 RETURN_QUANTITY_EXCEEDED
        AppException ex = assertThrows(AppException.class, () -> returnService.createReturn(req));
        assertEquals(ErrorCode.RETURN_QUANTITY_EXCEEDED.name(), ex.getCode());
    }

    @Test
    @DisplayName("BR-13 (SoD): Người lập phiếu tự duyệt phiếu của chính mình -> 422 SELF_APPROVAL_FORBIDDEN")
    void testApproveReturn_SelfApproval_Throws422() {
        authenticate("BRANCH_MANAGER", "7");

        ReturnDocument doc = new ReturnDocument();
        doc.setId(200L);
        doc.setBranchId(1L);
        doc.setStatus(ReturnStatus.DRAFT);
        doc.setCreatedBy(7L);

        Mockito.lenient()
                .when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any()))
                .thenReturn(List.of(1L));
        Mockito.lenient()
                .when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), any()))
                .thenReturn(List.of(1L));
        when(returnDocumentRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(doc));

        AppException ex = assertThrows(AppException.class, () -> returnService.approveReturn(200L));
        assertEquals(ErrorCode.SELF_APPROVAL_FORBIDDEN.name(), ex.getCode());
    }
}
