package vn.greenmart.sales.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.greenmart.catalog.domain.Product;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.customer.repository.CustomerRepository;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.dto.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentMatchers;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteService - Unit Tests")
class QuoteServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Spy
    private PromotionCalculatorService promotionCalculatorService = new PromotionCalculatorService();

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
    @DisplayName("Tính tiền hóa đơn ví dụ ra đúng 200.700 đ (AC1 Issue #31)")
    void testCalculateQuote_Success_200700() {
        authenticate("CASHIER");

        Product gao = new Product();
        gao.setId(101L);
        gao.setSku("GAO-ST25-5KG");
        gao.setName("Gạo ST25 5kg");
        gao.setUnit("túi");
        gao.setSalePrice(180000L);
        gao.setActive(true);

        Product sua = new Product();
        sua.setId(205L);
        sua.setSku("SUA-TH-1L");
        sua.setName("Sữa tươi TH 1L");
        sua.setUnit("hộp");
        sua.setSalePrice(29000L);
        sua.setActive(true);

        when(customerRepository.existsById(125L)).thenReturn(true);
        when(productRepository.findById(101L)).thenReturn(Optional.of(gao));
        when(productRepository.findById(205L)).thenReturn(Optional.of(sua));
        when(inventoryService.getAvailable(1L, 101L)).thenReturn(40);
        when(inventoryService.getAvailable(1L, 205L)).thenReturn(12);

        QuoteRequest request = new QuoteRequest(
                1L,
                125L,
                List.of(new OrderLineRequest(101L, 1), new OrderLineRequest(205L, 2)),
                null
        );

        Quote quote = quoteService.calculateQuote(request);

        assertThat(quote.subtotal()).isEqualTo(238000L);
        assertThat(quote.itemDiscount()).isEqualTo(15000L);
        assertThat(quote.orderDiscount()).isEqualTo(22300L);
        assertThat(quote.discount()).isEqualTo(37300L);
        assertThat(quote.total()).isEqualTo(200700L);
        assertThat(quote.canComplete()).isTrue();
        assertThat(quote.lines().get(0).sufficient()).isTrue();
        assertThat(quote.lines().get(1).sufficient()).isTrue();
    }

    @Test
    @DisplayName("Truy cập chi nhánh không được gán -> 403 BRANCH_FORBIDDEN (BR-11)")
    void testCalculateQuote_WrongBranch_ThrowsForbidden() {
        authenticate("CASHIER");
        QuoteRequest request = new QuoteRequest(2L, null, List.of(new OrderLineRequest(101L, 1)), null);

        assertThatThrownBy(() -> quoteService.calculateQuote(request))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.BRANCH_FORBIDDEN.name()));
    }

    @Test
    @DisplayName("Thiếu tồn -> sufficient = false, canComplete = false (AC2 Issue #31)")
    void testCalculateQuote_InsufficientStock() {
        authenticate("CASHIER");

        Product sua = new Product();
        sua.setId(205L);
        sua.setSku("SUA-TH-1L");
        sua.setName("Sữa tươi TH 1L");
        sua.setUnit("hộp");
        sua.setSalePrice(29000L);
        sua.setActive(true);

        when(productRepository.findById(205L)).thenReturn(Optional.of(sua));
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
        assertThat(quote.lines().get(0).available()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sản phẩm ngưng bán (active = false) -> canComplete = false")
    void testCalculateQuote_InactiveProduct() {
        authenticate("CASHIER");

        Product p = new Product();
        p.setId(301L);
        p.setSku("SP-NGUNG");
        p.setName("Sản phẩm ngưng");
        p.setUnit("cái");
        p.setSalePrice(50000L);
        p.setActive(false);

        when(productRepository.findById(301L)).thenReturn(Optional.of(p));
        when(inventoryService.getAvailable(1L, 301L)).thenReturn(10);

        QuoteRequest request = new QuoteRequest(
                1L,
                null,
                List.of(new OrderLineRequest(301L, 1)),
                null
        );

        Quote quote = quoteService.calculateQuote(request);

        assertThat(quote.canComplete()).isFalse();
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
    @DisplayName("BRANCH_MANAGER gửi manualDiscount hợp lệ -> total giảm thêm đúng bằng manualDiscount")
    void testCalculateQuote_ManagerManualDiscount_Success() {
        authenticate("BRANCH_MANAGER");

        Product sua = new Product();
        sua.setId(205L);
        sua.setSku("SUA-TH-1L");
        sua.setName("Sữa tươi TH 1L");
        sua.setUnit("hộp");
        sua.setSalePrice(29000L);
        sua.setActive(true);

        when(productRepository.findById(205L)).thenReturn(Optional.of(sua));
        when(inventoryService.getAvailable(1L, 205L)).thenReturn(10);

        QuoteRequest request = new QuoteRequest(
                1L,
                null,
                List.of(new OrderLineRequest(205L, 2)),
                new ManualDiscount(8000L, "Khách quen chi nhánh")
        );

        Quote quote = quoteService.calculateQuote(request);

        assertThat(quote.subtotal()).isEqualTo(58000L);
        assertThat(quote.manualDiscount()).isEqualTo(8000L);
        assertThat(quote.total()).isEqualTo(50000L);
    }
}
