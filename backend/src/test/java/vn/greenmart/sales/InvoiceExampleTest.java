package vn.greenmart.sales;

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
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PromotionCalculator;
import vn.greenmart.sales.service.QuoteServiceImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Issue #35: Test hóa đơn ví dụ ra đúng 200.700 đ (BA §3, BR-07, UC-01)")
class InvoiceExampleTest {

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

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Khóa công thức: Giỏ 3 món 238k - KM dòng 15k - KM đơn 10% (22.3k) = 200.700 đ")
    void testInvoiceExample_CalculatesCorrectly() {
        // Given: 2 Sữa tươi 1L (32k), 3 Rau cải (18k), 1 Gạo ST25 (120k)
        ProductSaleInfo sua = new ProductSaleInfo(205L, "SUA-TH-1L", "Sữa tươi TH 1L", "hộp", 32000L, true);
        ProductSaleInfo rau = new ProductSaleInfo(206L, "RAU-CAI-500G", "Rau cải 500g", "bó", 18000L, true);
        ProductSaleInfo gao = new ProductSaleInfo(101L, "GAO-ST25-5KG", "Gạo ST25 5kg", "túi", 120000L, true);

        when(customerService.exists(125L)).thenReturn(true);
        when(productService.getSaleInfo(205L)).thenReturn(sua);
        when(productService.getSaleInfo(206L)).thenReturn(rau);
        when(productService.getSaleInfo(101L)).thenReturn(gao);

        when(inventoryService.getAvailable(1L, 205L)).thenReturn(12);
        when(inventoryService.getAvailable(1L, 206L)).thenReturn(20);
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(40);

        // Mock KM dòng cho từng sản phẩm
        when(promotionCalculator.evaluateItemPromotion(eq(205L), anyString(), eq(32000L), eq(2), eq(1L)))
                .thenReturn(null);
        when(promotionCalculator.evaluateItemPromotion(eq(206L), anyString(), eq(18000L), eq(3), eq(1L)))
                .thenReturn(null);
        when(promotionCalculator.evaluateItemPromotion(eq(101L), anyString(), eq(120000L), eq(1), eq(1L)))
                .thenReturn(new PromotionRef(11L, "KM-GAO", "Giảm 15.000 đ cho Gạo ST25", 15000L));

        // Mock KM cấp đơn: 10% trên tổng 223.000 đ
        when(promotionCalculator.evaluateOrderPromotion(223000L, 1L))
                .thenReturn(new PromotionRef(12L, "KM-10", "Giảm 10% đơn từ 200.000 đ", 22300L));

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

        // When
        Quote quote = quoteService.calculateQuote(request);

        // Then - Đối soát toàn bộ số liệu theo BA §3
        assertThat(quote.subtotal()).isEqualTo(238000L);
        assertThat(quote.itemDiscount()).isEqualTo(15000L);
        assertThat(quote.orderDiscount()).isEqualTo(22300L);
        assertThat(quote.manualDiscount()).isEqualTo(0L);
        assertThat(quote.discount()).isEqualTo(37300L);
        assertThat(quote.total()).isEqualTo(200700L);
        assertThat(quote.canComplete()).isTrue();

        // Then - Khuyến mại đơn
        assertThat(quote.orderPromotion()).isNotNull();
        assertThat(quote.orderPromotion().code()).isEqualTo("KM-10");
        assertThat(quote.orderPromotion().discount()).isEqualTo(22300L);

        // Then - Dòng hàng được giảm giá
        QuoteLine gaoLine = quote.lines().stream()
                .filter(l -> l.productId().equals(101L))
                .findFirst()
                .orElseThrow();
        assertThat(gaoLine.lineTotal()).isEqualTo(105000L);
        assertThat(gaoLine.discount()).isEqualTo(15000L);
    }
}
