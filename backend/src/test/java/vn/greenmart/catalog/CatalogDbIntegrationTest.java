package vn.greenmart.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import vn.greenmart.catalog.dto.ProductLookupResponse;
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
            vn.greenmart.inventory.PostgresTestImage.IMAGE
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Category catRoot;
    private Category catChild;

    @BeforeEach
    void setupData() {
        auditLogRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();

        jdbcTemplate.execute("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (1, 'owner_demo', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Chủ Cửa Hàng', 'OWNER', true)
            ON CONFLICT (id) DO NOTHING
        """);

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

    // Chưa đăng nhập gọi sửa sản phẩm -> 401 UNAUTHORIZED
    @Test
    void testUpdateProduct_Unauthenticated_ThrowsUnauthorized() {
        Product p = new Product();
        p.setSku("SUA-TH-1L");
        p.setName("Sữa TH");
        p.setUnit("hộp");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(29000L);
        p.setCostPrice(23000L);
        p = productRepository.save(p);

        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", catChild.getId(), 31000L, 23000L, true);
        Long pId = p.getId();

        AppException ex = assertThrows(AppException.class, () -> productService.updateProduct(pId, req));
        assertEquals(ErrorCode.UNAUTHORIZED.name(), ex.getCode());
    }

    @Test
    @WithMockUser(username = "2", roles = "CASHIER")
    void testAC8_LookupProduct_BranchMatrix_AndRoles() {
        Product p = new Product();
        p.setSku("LOOKUP-MATRIX");
        p.setName("Sản phẩm Test Matrix");
        p.setUnit("cái");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(15000L);
        p.setCostPrice(10000L);
        productRepository.save(p);

        jdbcTemplate.execute("INSERT INTO branch (id, code, name, is_active) VALUES (1, 'GM-001', 'CN 1', true), (2, 'GM-002', 'CN 2', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO app_user (id, username, password_hash, full_name, role, is_active) VALUES (2, 'cashier_1', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu ngan 1', 'CASHIER', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO user_branch (user_id, branch_id) VALUES (2, 1) ON CONFLICT DO NOTHING");

        // Gọi đúng chi nhánh được gán (CN1) -> 200 OK
        ProductLookupResponse okRes = productService.lookupProduct("LOOKUP-MATRIX", 1L);
        assertEquals(1L, okRes.branchId());

        // Gọi chéo chi nhánh (CN2) -> 403 BRANCH_FORBIDDEN
        AppException ex = assertThrows(AppException.class, () -> productService.lookupProduct("LOOKUP-MATRIX", 2L));
        assertEquals(ErrorCode.BRANCH_FORBIDDEN.name(), ex.getCode());
    }

    @Test
    @WithMockUser(username = "3", roles = "BRANCH_MANAGER")
    void testAC9_LookupProduct_BranchManagerCrossBranch_ThrowsForbidden() {
        Product p = new Product();
        p.setSku("LOOKUP-BM");
        p.setName("Sản phẩm BM");
        p.setUnit("cái");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(15000L);
        p.setCostPrice(10000L);
        productRepository.save(p);

        AppException ex = assertThrows(AppException.class, () -> productService.lookupProduct("LOOKUP-BM", 2L));
        assertEquals(ErrorCode.BRANCH_FORBIDDEN.name(), ex.getCode());
    }

    @Test
    @WithMockUser(username = "4", roles = "STOREKEEPER")
    void testAC10_LookupProduct_StorekeeperCrossBranch_ThrowsForbidden() {
        Product p = new Product();
        p.setSku("LOOKUP-SK");
        p.setName("Sản phẩm SK");
        p.setUnit("cái");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(15000L);
        p.setCostPrice(10000L);
        productRepository.save(p);

        AppException ex = assertThrows(AppException.class, () -> productService.lookupProduct("LOOKUP-SK", 2L));
        assertEquals(ErrorCode.BRANCH_FORBIDDEN.name(), ex.getCode());
    }

    @Test
    @WithMockUser(username = "5", roles = "ADMIN")
    void testAC11_LookupProduct_AdminAllBranches_Success() {
        Product p = new Product();
        p.setSku("LOOKUP-ADMIN");
        p.setName("Sản phẩm Admin");
        p.setUnit("cái");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(20000L);
        p.setCostPrice(15000L);
        productRepository.save(p);

        ProductLookupResponse res = productService.lookupProduct("LOOKUP-ADMIN", 2L);
        assertEquals(2L, res.branchId());
    }

    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC12_LookupProduct_OwnerAllBranches_Success() {
        Product p = new Product();
        p.setSku("LOOKUP-OWNER");
        p.setName("Sản phẩm Owner");
        p.setUnit("cái");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(20000L);
        p.setCostPrice(15000L);
        productRepository.save(p);

        ProductLookupResponse res = productService.lookupProduct("LOOKUP-OWNER", 2L);
        assertEquals(2L, res.branchId());
    }

    @Test
    @WithMockUser(username = "2", roles = "CASHIER")
    void testAC13_LookupProduct_CrossBranch_CarriesDeniedBranchIdForAudit() {
        Product p = new Product();
        p.setSku("COM-SUON-BI-CHA-0001");
        p.setBarcode("8930000009999");
        p.setName("Cơm sườn bì chả");
        p.setUnit("phần");
        p.setCategoryId(catChild.getId());
        p.setSalePrice(35000L);
        p.setCostPrice(25000L);
        productRepository.save(p);

        jdbcTemplate.execute("INSERT INTO branch (id, code, name, is_active) VALUES (1, 'GM-001', 'CN 1', true), (2, 'GM-002', 'CN 2', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO app_user (id, username, password_hash, full_name, role, is_active) VALUES (2, 'thungan.gm001', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu ngan GM001', 'CASHIER', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO user_branch (user_id, branch_id) VALUES (2, 1) ON CONFLICT DO NOTHING");

        // Tra cứu theo SKU chéo chi nhánh -> ném BRANCH_FORBIDDEN kèm deniedBranchId = 2L
        AppException skuEx = assertThrows(AppException.class,
                () -> productService.lookupProduct("COM-SUON-BI-CHA-0001", 2L));
        assertEquals(ErrorCode.BRANCH_FORBIDDEN.name(), skuEx.getCode());
        assertEquals(2L, skuEx.getDeniedBranchId());

        // Tra cứu theo barcode chéo chi nhánh -> ném BRANCH_FORBIDDEN kèm deniedBranchId = 2L
        AppException barcodeEx = assertThrows(AppException.class,
                () -> productService.lookupProduct("8930000009999", 2L));
        assertEquals(ErrorCode.BRANCH_FORBIDDEN.name(), barcodeEx.getCode());
        assertEquals(2L, barcodeEx.getDeniedBranchId());
    }
}
