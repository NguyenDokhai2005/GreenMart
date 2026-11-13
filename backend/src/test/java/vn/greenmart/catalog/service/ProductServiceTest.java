package vn.greenmart.catalog.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.repository.CategoryRepository;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.inventory.service.InventoryService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        SecurityContextHolder.clearContext();
        // Mặc định giả lập user thuộc chi nhánh 1L cho các test cũ
        lenient().when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), any()))
                .thenReturn(List.of(1L));

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
    void testCreateProduct_Success() {
        ProductRequest req = new ProductRequest("SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, 29000L, 23000L, true);

        when(productRepository.existsBySku("SUA-TH-1L")).thenReturn(false);
        when(productRepository.existsByBarcode("8935217400013")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category()));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });

        ProductResponse res = productService.createProduct(req);
        assertNotNull(res);
        assertEquals(10L, res.id());
        assertEquals("SUA-TH-1L", res.sku());
        assertEquals(23000L, res.costPrice()); // OWNER thấy costPrice
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
    @WithMockUser(roles = "OWNER")
    void testCreateProduct_DuplicateBarcode_Throws409() {
        ProductRequest req = new ProductRequest("SUA-NEW", "8935217400013", "Sữa Mới", "hộp", 1L, 29000L, 23000L, true);
        when(productRepository.existsBySku("SUA-NEW")).thenReturn(false);
        when(productRepository.existsByBarcode("8935217400013")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> productService.createProduct(req));
        assertEquals(ErrorCode.DUPLICATE_BARCODE.name(), ex.getCode());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testCreateProduct_NonOwner_ThrowsForbidden() {
        ProductRequest req = new ProductRequest("SUA-TEST", null, "Sữa", "hộp", 1L, 20000L, 15000L, true);

        AppException ex = assertThrows(AppException.class, () -> productService.createProduct(req));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getCode());
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

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog savedLog = captor.getValue();
        assertEquals("CHANGE_PRICE", savedLog.getAction());
        assertEquals("product", savedLog.getEntity());
        assertEquals("205", savedLog.getEntityId());
        assertEquals(1L, savedLog.getUserId());
        assertEquals(29000L, savedLog.getDetail().get("oldPrice"));
        assertEquals(31000L, savedLog.getDetail().get("newPrice"));
    }

    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testUpdateProduct_NoPriceChange_NeverSavesAuditLog() {
        Product existing = new Product();
        existing.setId(205L);
        existing.setSku("SUA-TH-1L");
        existing.setSalePrice(29000L);
        existing.setCategoryId(1L);

        Category cat = new Category();
        cat.setId(1L);

        when(productRepository.findById(205L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Giữ nguyên giá 29000L
        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH Đổi Tên", "hộp", 1L, 29000L, 23000L, true);
        productService.updateProduct(205L, req);

        verify(auditLogRepository, never()).save(any());
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
    @WithMockUser(roles = "BRANCH_MANAGER")
    void testGetProduct_ManagerRole_CostPriceIsVisible() {
        Product p = new Product();
        p.setId(101L);
        p.setCategoryId(1L);
        p.setCostPrice(150000L);

        when(productRepository.findById(101L)).thenReturn(Optional.of(p));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category()));

        ProductResponse res = productService.getProduct(101L);

        assertEquals(150000L, res.costPrice());
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

    @Test
    @WithMockUser(roles = "CASHIER")
    void testLookupProduct_CrossBranch_ThrowsBranchForbidden() {
        // CASHIER mặc định chỉ có chi nhánh 1L -> gọi chi nhánh 2L phải bị chặn 403 BRANCH_FORBIDDEN
        AppException ex = assertThrows(AppException.class, () -> productService.lookupProduct("8935217400013", 2L));
        assertEquals(ErrorCode.BRANCH_FORBIDDEN.name(), ex.getCode());
        verifyNoInteractions(inventoryService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void testLookupProduct_AdminCrossBranch_Success() {
        Product p = new Product();
        p.setId(205L);
        p.setSku("SUA-TH-1L");
        p.setCategoryId(1L);

        when(productRepository.findByBarcode("SUA-TH-1L")).thenReturn(Optional.of(p));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category()));
        when(inventoryService.getAvailable(2L, 205L)).thenReturn(99);

        ProductLookupResponse res = productService.lookupProduct("SUA-TH-1L", 2L);
        assertEquals(2L, res.branchId());
        assertEquals(99, res.available());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testLookupProduct_FallbackToSku() {
        Product p = new Product();
        p.setId(206L);
        p.setSku("BANH-GAO");
        p.setCategoryId(1L);

        when(productRepository.findByBarcode("BANH-GAO")).thenReturn(Optional.empty());
        when(productRepository.findBySku("BANH-GAO")).thenReturn(Optional.of(p));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category()));
        when(inventoryService.getAvailable(1L, 206L)).thenReturn(5);

        ProductLookupResponse res = productService.lookupProduct("BANH-GAO", 1L);

        assertNotNull(res);
        assertEquals("BANH-GAO", res.product().sku());
        assertEquals(5, res.available());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testLookupProduct_InactiveProduct_ReturnsActiveFalse() {
        Product p = new Product();
        p.setId(207L);
        p.setSku("SP-NGUNG");
        p.setCategoryId(1L);
        p.setActive(false);

        when(productRepository.findByBarcode("SP-NGUNG")).thenReturn(Optional.of(p));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category()));
        when(inventoryService.getAvailable(1L, 207L)).thenReturn(0);

        ProductLookupResponse res = productService.lookupProduct("SP-NGUNG", 1L);

        assertNotNull(res);
        assertFalse(res.product().active());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testLookupProduct_NotFound_Throws404() {
        when(productRepository.findByBarcode("KHONG-CO")).thenReturn(Optional.empty());
        when(productRepository.findBySku("KHONG-CO")).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> productService.lookupProduct("KHONG-CO", 1L));
        assertEquals(ErrorCode.NOT_FOUND.name(), ex.getCode());
    }

    @Test
    void testGetSaleInfo_Success() {
        Product p = new Product();
        p.setId(101L);
        p.setSku("GAO-ST25");
        p.setName("Gạo ST25");
        p.setUnit("túi");
        p.setSalePrice(180000L);
        p.setActive(true);

        when(productRepository.findById(101L)).thenReturn(Optional.of(p));

        ProductSaleInfo info = productService.getSaleInfo(101L);

        assertNotNull(info);
        assertEquals(101L, info.id());
        assertEquals("GAO-ST25", info.sku());
        assertEquals("Gạo ST25", info.name());
        assertEquals("túi", info.unit());
        assertEquals(180000L, info.salePrice());
        assertTrue(info.active());
    }

    @Test
    void testGetSaleInfo_NotFound_Throws404() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> productService.getSaleInfo(999L));
        assertEquals(ErrorCode.NOT_FOUND.name(), ex.getCode());
    }
}
