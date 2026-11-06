package vn.greenmart.sales.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.identity.service.AuthService;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentStatus;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.repository.OrderItemRepository;
import vn.greenmart.sales.repository.PaymentRepository;
import vn.greenmart.sales.repository.SalesOrderRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PosOrderService - Unit Tests (Bao gồm PAY-01)")
class PosOrderServiceTest {

    @Mock private QuoteService quoteService;
    @Mock private ProductService productService;
    @Mock private CustomerService customerService;
    @Mock private InventoryService inventoryService;
    @Mock private BranchService branchService;
    @Mock private AuthService authService;
    @Mock private PromotionService promotionService;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private OutboxService outboxService;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PosOrderServiceImpl posOrderService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        lenient().when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), any()))
                .thenReturn(List.of(1L, 2L));
        lenient().when(jdbcTemplate.queryForObject(contains("sales_channel"), eq(Long.class)))
                .thenReturn(1L);
        lenient().when(jdbcTemplate.queryForObject(contains("order_sequence"), eq(Long.class), any(), any()))
                .thenReturn(1L);
        lenient().when(branchService.getSaleInfo(anyLong()))
                .thenReturn(new BranchSaleInfo(1L, "GM-001", "Chi nhánh 1"));
        lenient().when(authService.getFullName(anyLong()))
                .thenReturn("Thu Ngân");
        lenient().when(promotionService.recordUsage(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // -------------------------------------------------------------------------
    // TEST CASES ISSUE #33: THANH TOÁN GIẢ LẬP [PAY-01]
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TC-POS-01: Thanh toán CASH đủ tiền -> Trả 201, payment PAID, tính đúng tiền thối")
    void testCreatePosOrder_Cash_Success() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(10);

        Quote mockQuote = new Quote(
                1L, null,
                List.of(new QuoteLine(101L, "GAO-ST25", "Gạo ST25", "túi", 2, 100000L, 0L, 200000L, null, 10, true)),
                null, 200000L, 0L, 0L, 0L, 0L, 200000L, true
        );
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder so = inv.getArgument(0);
            so.setId(500L);
            return so;
        });
        when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(600L);
            return p;
        });

        // Tổng 200.000, khách đưa 250.000 -> thối 50.000
        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 2)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 250000L, "SUCCESS"),
                200000L
        );

        OrderResponse res = posOrderService.createPosOrder(request, UUID.randomUUID().toString());

        assertThat(res.id()).isEqualTo(500L);
        assertThat(res.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(res.total()).isEqualTo(200000L);
        assertThat(res.payments()).hasSize(1);
        assertThat(res.payments().get(0).status()).isEqualTo(PaymentStatus.PAID);
        assertThat(res.payments().get(0).cashReceived()).isEqualTo(250000L);
        assertThat(res.payments().get(0).change()).isEqualTo(50000L);

        verify(paymentRepository).save(any(Payment.class));
        verify(inventoryService).sellDirect(1L, 101L, 2, 500L);
        verify(outboxService).enqueue(eq("order"), eq("500"), eq("order.completed"), any());
        verify(outboxService).enqueue(eq("stock"), eq("1"), eq("stock.changed"), any());
    }

    @Test
    @DisplayName("TC-POS-08: Thanh toán CASH thiếu tiền -> 422 CASH_INSUFFICIENT kèm details total & cashReceived")
    void testCreatePosOrder_CashInsufficient_Throws422() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(10);

        Quote mockQuote = new Quote(1L, null, Collections.emptyList(), null, 200000L, 0L, 0L, 0L, 0L, 200000L, true);
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        // Tổng 200.000 nhưng khách chỉ đưa 150.000
        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 2)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 150000L, "SUCCESS"),
                200000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.CASH_INSUFFICIENT.name());

                    assertThat(appEx.getDetails()).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) appEx.getDetails();
                    assertThat(details.get("total")).isEqualTo(200000L);
                    assertThat(details.get("cashReceived")).isEqualTo(150000L);
                });

        verify(salesOrderRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verify(inventoryService, never()).sellDirect(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("TC-POS-06: Thẻ CARD simulate = FAILED -> 422 PAYMENT_FAILED, không lưu đơn, không trừ tồn")
    void testCreatePosOrder_CardFailed_Throws422() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(10);

        Quote mockQuote = new Quote(1L, null, Collections.emptyList(), null, 100000L, 0L, 0L, 0L, 0L, 100000L, true);
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.CARD, null, "FAILED"),
                100000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.PAYMENT_FAILED.name()));

        verify(salesOrderRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verify(inventoryService, never()).sellDirect(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("TC-POS-07: Mã QR simulate = TIMEOUT -> 422 PAYMENT_TIMEOUT, không lưu đơn, không trừ tồn")
    void testCreatePosOrder_QrTimeout_Throws422() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(10);

        Quote mockQuote = new Quote(1L, null, Collections.emptyList(), null, 100000L, 0L, 0L, 0L, 0L, 100000L, true);
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.QR, null, "TIMEOUT"),
                100000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.PAYMENT_TIMEOUT.name()));

        verify(salesOrderRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verify(inventoryService, never()).sellDirect(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Thanh toán CARD simulate = SUCCESS -> Tạo bản ghi PAID và sinh providerRef đúng định dạng")
    void testCreatePosOrder_Card_Success() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(10);

        Quote mockQuote = new Quote(1L, null,
                List.of(new QuoteLine(101L, "GAO-ST25", "Gạo ST25", "túi", 1, 100000L, 0L, 100000L, null, 10, true)),
                null, 100000L, 0L, 0L, 0L, 0L, 100000L, true
        );
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder so = inv.getArgument(0);
            so.setId(500L);
            return so;
        });
        when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(601L);
            return p;
        });

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.CARD, null, "SUCCESS"),
                100000L
        );

        OrderResponse res = posOrderService.createPosOrder(request, UUID.randomUUID().toString());

        assertThat(res.payments().get(0).status()).isEqualTo(PaymentStatus.PAID);
        assertThat(res.payments().get(0).cashReceived()).isNull();
        assertThat(res.payments().get(0).change()).isNull();
        assertThat(res.payments().get(0).providerRef()).contains("SIM-");
    }

    // -------------------------------------------------------------------------
    // CÁC TEST CASES KHÁC (NGHIỆP VỤ POS ĐƠN HÀNG)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Hai chi nhánh trong cùng ngày đều bắt đầu mã đơn từ 0001 (Yêu cầu Lead #32)")
    void testCreatePosOrder_SequenceStartsAt0001PerBranch() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(anyLong(), eq(101L))).thenReturn(10);

        Quote mockQuote = new Quote(
                1L, null,
                List.of(new QuoteLine(101L, "GAO-ST25", "Gạo ST25", "túi", 1, 100000L, 0L, 100000L, null, 10, true)),
                null, 100000L, 0L, 0L, 0L, 0L, 100000L, true
        );
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder so = inv.getArgument(0);
            so.setId(501L);
            return so;
        });
        when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(601L);
            return p;
        });

        when(branchService.getSaleInfo(1L)).thenReturn(new BranchSaleInfo(1L, "GM001", "CN 1"));
        when(branchService.getSaleInfo(2L)).thenReturn(new BranchSaleInfo(2L, "GM002", "CN 2"));

        PosOrderRequest req1 = new PosOrderRequest(1L, null, List.of(new OrderLineRequest(101L, 1)), null,
                new PaymentRequest(PaymentMethod.CASH, 100000L, "SUCCESS"), 100000L);
        PosOrderRequest req2 = new PosOrderRequest(2L, null, List.of(new OrderLineRequest(101L, 1)), null,
                new PaymentRequest(PaymentMethod.CASH, 100000L, "SUCCESS"), 100000L);

        OrderResponse res1 = posOrderService.createPosOrder(req1, UUID.randomUUID().toString());
        OrderResponse res2 = posOrderService.createPosOrder(req2, UUID.randomUUID().toString());

        assertThat(res1.code()).endsWith("-0001");
        assertThat(res2.code()).endsWith("-0001");
    }

    @Test
    @DisplayName("Thiếu tồn kho khả dụng -> 409 INSUFFICIENT_STOCK kèm details.lines (AC3 Issue #32)")
    void testCreatePosOrder_InsufficientStock_Throws409_WithDetails() {
        authenticate("CASHIER");

        ProductSaleInfo product = new ProductSaleInfo(101L, "GAO-ST25", "Gạo ST25", "túi", 100000L, true);
        when(productService.getSaleInfo(101L)).thenReturn(product);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(1);

        Quote mockQuote = new Quote(1L, null, Collections.emptyList(), null, 200000L, 0L, 0L, 0L, 0L, 200000L, true);
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 2)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, "SUCCESS"),
                200000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK.name());

                    assertThat(appEx.getDetails()).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detailsMap = (Map<String, Object>) appEx.getDetails();

                    @SuppressWarnings("unchecked")
                    List<InsufficientStockLine> lines = (List<InsufficientStockLine>) detailsMap.get("lines");
                    assertThat(lines).isNotEmpty();
                    assertThat(lines.get(0).productId()).isEqualTo(101L);
                    assertThat(lines.get(0).requested()).isEqualTo(2);
                    assertThat(lines.get(0).available()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("expectedTotal khác tổng tính lại -> 422 TOTAL_CHANGED (AC2 Issue #32)")
    void testCreatePosOrder_TotalChanged_Throws422() {
        authenticate("CASHIER");

        Quote mockQuote = new Quote(1L, null, Collections.emptyList(), null, 200000L, 0L, 0L, 0L, 0L, 200000L, true);
        when(quoteService.calculateQuote(any())).thenReturn(mockQuote);

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 2)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 250000L, "SUCCESS"),
                180000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.TOTAL_CHANGED.name()));

        verify(salesOrderRepository, never()).save(any());
        verify(inventoryService, never()).sellDirect(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Chi nhánh ngoài phạm vi gán -> 403 BRANCH_FORBIDDEN (AC4 Issue #32)")
    void testCreatePosOrder_BranchForbidden_Throws403() {
        authenticate("CASHIER");

        PosOrderRequest request = new PosOrderRequest(
                99L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 100000L, "SUCCESS"),
                100000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.BRANCH_FORBIDDEN.name()));
    }

    @Test
    @DisplayName("Khuyến mại hết lượt lúc hoàn tất đơn -> trả 422 TOTAL_CHANGED kèm details.quote và không lưu đơn")
    void testCreatePosOrder_PromotionExhausted_Throws422_WithDetailsQuote() {
        authenticate("CASHIER");

        PromotionRef promo = new PromotionRef(88L, "KM-CHOT", "Giảm 20k", 20000L);
        Quote initialQuote = new Quote(
                1L, null, Collections.emptyList(), promo,
                200000L, 0L, 20000L, 0L, 20000L, 180000L, true
        );
        Quote quoteWithoutPromo = new Quote(
                1L, null, Collections.emptyList(), null,
                200000L, 0L, 0L, 0L, 0L, 200000L, true
        );

        when(quoteService.calculateQuote(any()))
                .thenReturn(initialQuote)
                .thenReturn(quoteWithoutPromo);

        when(promotionService.recordUsage(88L)).thenReturn(false);

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, "SUCCESS"),
                180000L
        );

        assertThatThrownBy(() -> posOrderService.createPosOrder(request, UUID.randomUUID().toString()))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.TOTAL_CHANGED.name());

                    assertThat(appEx.getDetails()).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) appEx.getDetails();
                    assertThat(details).containsKey("quote");

                    Quote quoteInDetails = (Quote) details.get("quote");
                    assertThat(quoteInDetails.total()).isEqualTo(200000L);
                });

        verify(salesOrderRepository, never()).save(any());
        verify(inventoryService, never()).sellDirect(any(), any(), anyInt(), any());
    }
}
