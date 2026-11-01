package vn.greenmart.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.catalog.domain.Category;
import vn.greenmart.catalog.domain.Product;
import vn.greenmart.catalog.dto.CategoryRequest;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.catalog.repository.CategoryRepository;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.catalog.service.CategoryService;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
@Transactional
class CatalogDbIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgres:16-alpine")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    private Category catRoot;
    private Category catChild;

    @BeforeEach
    void setupData() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        auditLogRepository.deleteAll();

        catRoot = new Category();
        catRoot.setName("Đồ uống");
        catRoot = categoryRepository.save(catRoot);

        catChild = new Category();
        catChild.setName("Sữa tươi");
        catChild.setParentId(catRoot.getId());
        catChild = categoryRepository.save(catChild);
    }

    // OWNER tạo sản phẩm mới thành công -> lưu DB thật
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC1_CreateProduct_SuccessInDb() {
        ProductRequest req = new ProductRequest("SUA-TH-1L", "8935217400013", "Sữa TH True Milk 1L", "hộp", catChild.getId(), 29000L, 23000L, true);
        ProductResponse res = productService.createProduct(req);

        assertNotNull(res.id());
        Product inDb = productRepository.findById(res.id()).orElseThrow();
        assertEquals("SUA-TH-1L", inDb.getSku());
    }

    // Trùng SKU -> ném DUPLICATE_SKU
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC2_CreateProduct_DuplicateSku_ThrowsConflict() {
        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", catChild.getId(), 29000L, 23000L, true);
        productService.createProduct(req);

        ProductRequest duplicateReq = new ProductRequest("  SUA-TH-1L  ", null, "Sữa TH trùng", "hộp", catChild.getId(), 29000L, 23000L, true);
        AppException ex = assertThrows(AppException.class, () -> productService.createProduct(duplicateReq));
        assertEquals(ErrorCode.DUPLICATE_SKU.name(), ex.getCode());
    }

    // CASHIER sửa giá -> 403 FORBIDDEN & giá trong DB không đổi
    @Test
    @WithMockUser(username = "2", roles = "CASHIER")
    void testAC3_CashierUpdate_ThrowsForbidden_PriceUnchangedInDb() {
        Product p = new Product();
        p.setSku("SUA-TH-1L");
        p.setName("Sữa TH");
        p.setUnit("hộp");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(29000L);
        p.setCostPrice(23000L);
        p = productRepository.save(p);

        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", catChild.getId(), 35000L, 23000L, true);
        Long pId = p.getId();

        assertThrows(AppException.class, () -> productService.updateProduct(pId, req));

        Product unchanged = productRepository.findById(pId).orElseThrow();
        assertEquals(29000L, unchanged.getSalePrice(), "Giá trong DB không được thay đổi");
    }

    // OWNER đổi giá -> audit_log ghi bản ghi CHANGE_PRICE, oldPrice, newPrice, đúng user_id
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC4_OwnerChangePrice_RecordsAuditLog() {
        Product p = new Product();
        p.setSku("SUA-TH-1L");
        p.setName("Sữa TH");
        p.setUnit("hộp");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(29000L);
        p.setCostPrice(23000L);
        p = productRepository.save(p);

        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", catChild.getId(), 31000L, 23000L, true);
        productService.updateProduct(p.getId(), req);

        List<AuditLog> logs = auditLogRepository.findAll();
        assertEquals(1, logs.size());
        AuditLog log = logs.get(0);
        assertEquals("CHANGE_PRICE", log.getAction());
        assertEquals("product", log.getEntity());
        assertEquals(String.valueOf(p.getId()), log.getEntityId());
        assertEquals(1L, log.getUserId());
        assertNotNull(log.getDetail());
        assertEquals(29000, ((Number) log.getDetail().get("oldPrice")).longValue());
        assertEquals(31000, ((Number) log.getDetail().get("newPrice")).longValue());
    }

    // Đặt cha tạo vòng lặp -> 422 CATEGORY_CYCLE
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC5_UpdateCategory_Cycle_Throws422() {
        CategoryRequest req = new CategoryRequest("Đồ uống", catChild.getId());
        AppException ex = assertThrows(AppException.class, () -> categoryService.updateCategory(catRoot.getId(), req));
        assertEquals(ErrorCode.CATEGORY_CYCLE.name(), ex.getCode());
    }

    // Xóa danh mục còn sản phẩm hoặc con -> 409 CATEGORY_IN_USE
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC6_DeleteCategory_InUse_Throws409() {
        AppException ex = assertThrows(AppException.class, () -> categoryService.deleteCategory(catRoot.getId()));
        assertEquals(ErrorCode.CATEGORY_IN_USE.name(), ex.getCode());
    }

    // CASHIER gọi GET /products -> costPrice nhận null
    @Test
    @WithMockUser(username = "2", roles = "CASHIER")
    void testAC7_GetProducts_CashierRole_CostPriceNull() {
        Product p = new Product();
        p.setSku("SUA-TH-1L");
        p.setName("Sữa TH");
        p.setUnit("hộp");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(29000L);
        p.setCostPrice(23000L);
        productRepository.save(p);

        PageResponse<ProductResponse> page = productService.listProducts(null, null, null, 0, 20, null);
        assertEquals(1, page.content().size());
        assertNull(page.content().get(0).costPrice());
    }

    // Kiểm tra JPQL tìm theo danh mục
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testSearchProducts_FilterRootCategory_IncludesChildren() {
        Product p = new Product();
        p.setSku("SUA-TH-1L");
        p.setName("Sữa TH");
        p.setUnit("hộp");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(29000L);
        p.setCostPrice(23000L);
        productRepository.save(p);

        PageResponse<ProductResponse> page = productService.listProducts(null, catRoot.getId(), null, 0, 20, null);
        assertEquals(1, page.content().size());
        assertEquals("SUA-TH-1L", page.content().get(0).sku());
    }
}
