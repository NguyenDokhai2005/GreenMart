package vn.greenmart.catalog.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import vn.greenmart.catalog.domain.Category;
import vn.greenmart.catalog.domain.Product;
import vn.greenmart.catalog.dto.ProductLookupResponse;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.catalog.repository.CategoryRepository;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.inventory.service.InventoryService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        SecurityContextHolder.clearContext();
        testInfo.getTestMethod().ifPresent(method -> {
            WithMockUser withMockUser = method.getAnnotation(WithMockUser.class);
            if (withMockUser != null) {
                String role = withMockUser.roles().length > 0 ? withMockUser.roles()[0] : "USER";
                String username = withMockUser.username().isEmpty() ? "1" : withMockUser.username();
                Authentication auth = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testCreateProduct_DuplicateSku_Throws409() {
        ProductRequest req = new ProductRequest("SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, 29000L, 23000L, true);
        when(productRepository.existsBySku("SUA-TH-1L")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> productService.createProduct(req));
        assertEquals(ErrorCode.DUPLICATE_SKU.name(), ex.getCode());
    }

    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testUpdateProduct_PriceChanged_SavesAuditLog() {
        Product existing = new Product();
        existing.setId(205L);
        existing.setSku("SUA-TH-1L");
        existing.setSalePrice(29000L);
        existing.setCostPrice(23000L);
        existing.setCategoryId(1L);

        Category cat = new Category();
        cat.setId(1L);
        cat.setName("Sữa");

        when(productRepository.findById(205L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", 1L, 31000L, 23000L, true);

        ProductResponse res = productService.updateProduct(205L, req);

        assertEquals(31000L, res.salePrice());
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testGetProduct_CashierRole_CostPriceIsNull() {
        Product p = new Product();
        p.setId(101L);
        p.setSku("GAO-ST25");
        p.setName("Gạo ST25");
        p.setUnit("túi");
        p.setCategoryId(1L);
        p.setSalePrice(180000L);
        p.setCostPrice(150000L);

        Category cat = new Category();
        cat.setId(1L);
        cat.setName("Lương thực");

        when(productRepository.findById(101L)).thenReturn(Optional.of(p));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));

        ProductResponse res = productService.getProduct(101L);

        assertEquals(180000L, res.salePrice());
        assertNull(res.costPrice(), "CASHIER không được thấy costPrice");
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testLookupProduct_Success() {
        Product p = new Product();
        p.setId(205L);
        p.setSku("SUA-TH-1L");
        p.setBarcode("8935217400013");
        p.setName("Sữa TH");
        p.setUnit("hộp");
        p.setCategoryId(1L);
        p.setSalePrice(29000L);
        p.setCostPrice(23000L);

        when(productRepository.findByBarcode("8935217400013")).thenReturn(Optional.of(p));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category()));
        when(inventoryService.getAvailable(1L, 205L)).thenReturn(12);

        ProductLookupResponse res = productService.lookupProduct("8935217400013", 1L);

        assertNotNull(res);
        assertEquals(12, res.available());
        assertEquals("SUA-TH-1L", res.product().sku());
    }
}
