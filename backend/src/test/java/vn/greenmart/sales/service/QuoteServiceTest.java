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
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.dto.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteService - Unit Tests")
class QuoteServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private CustomerService customerService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private PromotionCalculator promotionCalculator;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private QuoteServiceImpl quoteService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        lenient().when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), any()))
                .thenReturn(List.of(1L));
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

    @Test
    @DisplayName("Khóa công thức hóa đơn ví dụ BA §3 ra đúng 200.700 đ (Issue #35)")
    void testCalculateQuote_Success_200700() {
        authenticate("CASHIER");

        // Dữ liệu 3 sản phẩm theo đúng bảng ví dụ BA §3
        // Sữa tươi 1L: 32.000 đ x 2 = 64.000 đ
        ProductSaleInfo sua = new ProductSaleInfo(205L, "SUA-TH-1L", "Sữa tươi TH 1L", "hộp", 32000L, true);
        // Rau cải 500g: 18.000 đ x 3 = 54.000 đ
        ProductSaleInfo rau = new ProductSaleInfo(206L, "RAU-CAI-500G", "Rau cải 500g", "bó", 18000L, true);
        // Gạo ST25 5kg: 120.000 đ x 1 = 120.000 đ
        ProductSaleInfo gao = new ProductSaleInfo(101L, "GAO-ST25-5KG", "Gạo ST25 5kg", "túi", 120000L, true);

        when(customerService.exists(125L)).thenReturn(true);
        when(productService.getSaleInfo(205L)).thenReturn(sua);
        when(productService.getSaleInfo(206L)).thenReturn(rau);
        when(productService.getSaleInfo(101L)).thenReturn(gao);

        when(inventoryService.getAvailable(1L, 205L)).thenReturn(12);
        when(inventoryService.getAvailable(1L, 206L)).thenReturn(20);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(40);

        // KM cấp dòng: KM-GAO giảm 15.000 đ trực tiếp cho Gạo ST25
        when(promotionCalculator.evaluateItemPromotion(eq(205L), anyString(), eq(32000L), eq(2), eq(1L)))
                .thenReturn(null);
        when(promotionCalculator.evaluateItemPromotion(eq(206L), anyString(), eq(18000L), eq(3), eq(1L)))
                .thenReturn(null);
        when(promotionCalculator.evaluateItemPromotion(eq(101L), anyString(), eq(120000L), eq(1), eq(1L)))
                .thenReturn(new PromotionRef(11L, "KM-GAO", "Giảm 15k gạo", 15000L));

        // KM cấp đơn: Tổng sau giảm dòng = 238.000 - 15.000 = 223.000 đ
        // KM-10: Giảm 10% đơn từ 200k (tối đa 30k) -> 10% x 223.000 = 22.300 đ
        when(promotionCalculator.evaluateOrderPromotion(223000L, 1L))
                .thenReturn(new PromotionRef(12L, "KM-10", "Giảm 10% đơn từ 200k", 22300L));

        QuoteRequest request = new QuoteRequest(
                1L,
                125L,
                List.of(
                        new OrderLineRequest(205L, 2),
                        new OrderLineRequest(206L, 3),
                        new OrderLineRequest(101L, 1)
                ),
                null
        );

        Quote quote = quoteService.calculateQuote(request);

        // Assert tổng thể hóa đơn
        assertThat(quote.subtotal()).isEqualTo(238000L);       // 64k + 54k + 120k
        assertThat(quote.itemDiscount()).isEqualTo(15000L);     // Giảm dòng gạo
        assertThat(quote.orderDiscount()).isEqualTo(22300L);    // 10% của 223k
        assertThat(quote.manualDiscount()).isEqualTo(0L);
        assertThat(quote.discount()).isEqualTo(37300L);         // 15k + 22.3k
        assertThat(quote.total()).isEqualTo(200700L);            // 238k - 37.3k
        assertThat(quote.canComplete()).isTrue();

        // Assert chi tiết khuyến mại đơn
        assertThat(quote.orderPromotion()).isNotNull();
        assertThat(quote.orderPromotion().code()).isEqualTo("KM-10");
        assertThat(quote.orderPromotion().discount()).isEqualTo(22300L);

        // Assert chi tiết dòng Gạo
        QuoteLine gaoLine = quote.lines().stream()
                .filter(l -> l.productId().equals(101L))
                .findFirst()
                .orElseThrow();
        assertThat(gaoLine.unitPrice()).isEqualTo(120000L);
        assertThat(gaoLine.discount()).isEqualTo(15000L);
        assertThat(gaoLine.lineTotal()).isEqualTo(105000L);
        assertThat(gaoLine.promotion()).isNotNull();
        assertThat(gaoLine.promotion().code()).isEqualTo("KM-GAO");
    }

    @Test
    @DisplayName("OWNER gọi quote -> 403 FORBIDDEN (theo x-roles openapi.yaml)")
    void testCalculateQuote_OwnerRole_ThrowsForbidden() {
        authenticate("OWNER");
        QuoteRequest request = new QuoteRequest(1L, null, List.of(new OrderLineRequest(101L, 1)), null);

        assertThatThrownBy(() -> quoteService.calculateQuote(request))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.FORBIDDEN.name()));
    }

    @Test
    @DisplayName("CASHIER gửi manualDiscount -> 422 MANUAL_DISCOUNT_FORBIDDEN (BR-19)")
    void testCalculateQuote_CashierManualDiscount_ThrowsForbidden() {
        authenticate("CASHIER");

        QuoteRequest request = new QuoteRequest(
                1L,
                null,
                List.of(new OrderLineRequest(101L, 1)),
                new ManualDiscount(10000L, "Giảm giá đặc biệt")
        );

        assertThatThrownBy(() -> quoteService.calculateQuote(request))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.MANUAL_DISCOUNT_FORBIDDEN.name()));
    }

    @Test
    @DisplayName("BRANCH_MANAGER gửi manualDiscount hợp lệ -> total giảm đúng bằng manualDiscount")
    void testCalculateQuote_ManagerManualDiscount_Success() {
        authenticate("BRANCH_MANAGER");

        ProductSaleInfo sua = new ProductSaleInfo(205L, "SUA-TH-1L", "Sữa tươi TH 1L", "hộp", 29000L, true);
        when(productService.getSaleInfo(205L)).thenReturn(sua);
        when(inventoryService.getAvailable(1L, 205L)).thenReturn(10);

        QuoteRequest request = new QuoteRequest(
                1L,
                null,
                List.of(new OrderLineRequest(205L, 2)), // 58.000 đ
                new ManualDiscount(8000L, "Khách quen chi nhánh")
        );

        Quote quote = quoteService.calculateQuote(request);

        assertThat(quote.subtotal()).isEqualTo(58000L);
        assertThat(quote.manualDiscount()).isEqualTo(8000L);
        assertThat(quote.total()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("manualDiscount lớn hơn số tiền phải trả -> 400 VALIDATION_ERROR")
    void testCalculateQuote_ManualDiscountExceedsPayable_ThrowsValidationError() {
        authenticate("BRANCH_MANAGER");

        ProductSaleInfo sua = new ProductSaleInfo(205L, "SUA-TH-1L", "Sữa tươi TH 1L", "hộp", 29000L, true);
        when(productService.getSaleInfo(205L)).thenReturn(sua);
        when(inventoryService.getAvailable(1L, 205L)).thenReturn(10);

        QuoteRequest request = new QuoteRequest(
                1L,
                null,
                List.of(new OrderLineRequest(205L, 1)), // Giá 29.000 đ
                new ManualDiscount(50000L, "Giảm quá mức")
        );

        assertThatThrownBy(() -> quoteService.calculateQuote(request))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("Thiếu tồn -> sufficient = false, canComplete = false")
    void testCalculateQuote_InsufficientStock() {
        authenticate("CASHIER");

        ProductSaleInfo sua = new ProductSaleInfo(205L, "SUA-TH-1L", "Sữa tươi TH 1L", "hộp", 29000L, true);
        when(productService.getSaleInfo(205L)).thenReturn(sua);
        when(inventoryService.getAvailable(1L, 205L)).thenReturn(1);

        QuoteRequest request = new QuoteRequest(
                1L,
                null,
                List.of(new OrderLineRequest(205L, 2)),
                null
        );

        Quote quote = quoteService.calculateQuote(request);

        assertThat(quote.canComplete()).isFalse();
        assertThat(quote.lines().get(0).sufficient()).isFalse();
    }
}
