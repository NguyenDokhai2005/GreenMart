package vn.greenmart.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
import vn.greenmart.inventory.domain.Inventory;
import vn.greenmart.inventory.repository.InventoryRepository;

import java.lang.reflect.Field;
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
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "OWNER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void testCreateProduct_DuplicateSku_Throws409() {
        ProductRequest req = new ProductRequest("SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, 29000L, 23000L, true);
        when(productRepository.existsBySku("SUA-TH-1L")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> productService.createProduct(req));
        assertEquals(ErrorCode.DUPLICATE_SKU.name(), ex.getCode());
    }

    @Test
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
    void testGetProduct_CashierRole_CostPriceIsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "CASHIER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

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
    void testLookupProduct_Success() throws Exception {
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

        Inventory inv = new Inventory();
        setPrivateField(inv, "onHand", 15);
        setPrivateField(inv, "reserved", 3);

        when(inventoryRepository.findById(any(Inventory.InventoryId.class))).thenReturn(Optional.of(inv));

        ProductLookupResponse res = productService.lookupProduct("8935217400013", 1L);

        assertNotNull(res);
        assertEquals(12, res.available()); 
        assertEquals("SUA-TH-1L", res.product().sku());
    }

    private void setPrivateField(Object obj, String fieldName, Object val) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, val);
    }
}
