package vn.greenmart.inventory.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.domain.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test kiểm tra SQL conditional UPDATE trong InventoryRepository.
 * <p>
 * Chạy trên schema Flyway thật (V1 migration, ddl-auto=validate) với PostgreSQL 17 Testcontainers
 * — đảm bảo mapping và query hoạt động đúng với schema triển khai, không phải schema
 * do Hibernate tự tạo.
 * <p>
 * Bulk UPDATE flush và clear persistence context; đọc lại để kiểm tra dữ liệu DB.
 * <p>
 * Không test đồng thời (thuộc issue #51).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class InventoryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(vn.greenmart.inventory.PostgresTestImage.IMAGE)
            .withDatabaseName("greenmart_test")
            .withUsername("greenmart")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Dùng Flyway migration thật để test trên schema triển khai
        registry.add("spring.jpa.hibernate.ddl-auto",                   () -> "validate");
        registry.add("spring.flyway.enabled",                            () -> "true");
        registry.add("spring.flyway.placeholders.forecast_user",         () -> "forecast_ro");
    }

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    /** Trạng thái ban đầu: on_hand=10, reserved=3, available=7 */
    private static final Long BRANCH_ID  = 1L;
    private static final Long PRODUCT_ID = 5L;

    @BeforeEach
    void setUp() {
        // Xóa dữ liệu cũ theo thứ tự FK
        jdbc.execute("DELETE FROM inventory");
        jdbc.execute("DELETE FROM inventory_transaction");
        jdbc.execute("DELETE FROM return_item");
        jdbc.execute("DELETE FROM order_item");
        jdbc.execute("DELETE FROM payment");
        jdbc.execute("DELETE FROM return_document");
        jdbc.execute("DELETE FROM sales_order");
        jdbc.execute("DELETE FROM product WHERE id = " + PRODUCT_ID);
        jdbc.execute("DELETE FROM branch  WHERE id = " + BRANCH_ID);
        jdbc.execute("DELETE FROM category WHERE id = 99");

        // Tạo fixtures thỏa FK constraint
        jdbc.execute("INSERT INTO category (id, name) VALUES (99, 'Test') ON CONFLICT DO NOTHING");
        jdbc.execute("""
                INSERT INTO branch (id, code, name, is_active)
                VALUES (%d, 'T-CN', 'Test Branch', true)
                ON CONFLICT DO NOTHING
                """.formatted(BRANCH_ID));
        jdbc.execute("""
                INSERT INTO product (id, sku, name, unit, category_id, sale_price, cost_price, is_active)
                VALUES (%d, 'T-SKU', 'Test Product', 'cai', 99, 10000, 8000, true)
                ON CONFLICT DO NOTHING
                """.formatted(PRODUCT_ID));

        // on_hand=10, reserved=3, available=7
        jdbc.execute("""
                INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
                VALUES (%d, %d, 10, 3, 0, NOW())
                """.formatted(BRANCH_ID, PRODUCT_ID));

        em.flush();
        em.clear();
    }

    /**
     * Reload entity từ DB sau UPDATE, bỏ qua Hibernate L1 cache.
     * em.clear() cũng detach toàn bộ entity đang được quản lý trong test.
     */
    private Inventory reload() {
        em.flush();
        em.clear();
        return inventoryRepository.findById(
                new Inventory.InventoryId(BRANCH_ID, PRODUCT_ID)).orElseThrow();
    }

    // =========================================================================
    // reserve
    // =========================================================================

    @Nested
    @DisplayName("reserve")
    class ReserveTests {

        @Test
        @DisplayName("available=7 >= qty=3 → reserved tăng, on_hand không đổi")
        void reserve_success_reservedIncrease() {
            int rows = inventoryRepository.reserve(BRANCH_ID, PRODUCT_ID, 3);

            assertThat(rows).isEqualTo(1);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(10);  // on_hand không đổi
            assertThat(inv.getReserved()).isEqualTo(6); // 3 + 3
        }

        @Test
        @DisplayName("qty=8 > available=7 → 0 dòng, dữ liệu không đổi")
        void reserve_fail_insufficientAvailable() {
            int rows = inventoryRepository.reserve(BRANCH_ID, PRODUCT_ID, 8);

            assertThat(rows).isEqualTo(0);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(10);
            assertThat(inv.getReserved()).isEqualTo(3);
        }

        @Test
        @DisplayName("qty=7 = available → biên hợp lệ, reserved = on_hand = 10")
        void reserve_success_exactAvailable() {
            int rows = inventoryRepository.reserve(BRANCH_ID, PRODUCT_ID, 7);

            assertThat(rows).isEqualTo(1);
            assertThat(reload().getReserved()).isEqualTo(10); // 3 + 7 = 10 = on_hand
        }

        @Test
        @DisplayName("version tăng 1 sau reserve thành công")
        void reserve_versionIncrement() {
            long before = reload().getVersion();
            inventoryRepository.reserve(BRANCH_ID, PRODUCT_ID, 3);
            assertThat(reload().getVersion()).isEqualTo(before + 1);
        }
    }

    // =========================================================================
    // release
    // =========================================================================

    @Nested
    @DisplayName("release")
    class ReleaseTests {

        @Test
        @DisplayName("reserved=3 >= qty=3 → reserved về 0, on_hand không đổi")
        void release_success() {
            int rows = inventoryRepository.release(BRANCH_ID, PRODUCT_ID, 3);

            assertThat(rows).isEqualTo(1);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(10);
            assertThat(inv.getReserved()).isEqualTo(0);
        }

        @Test
        @DisplayName("qty=5 > reserved=3 → 0 dòng")
        void release_fail_reservedInsufficient() {
            int rows = inventoryRepository.release(BRANCH_ID, PRODUCT_ID, 5);

            assertThat(rows).isEqualTo(0);
            assertThat(reload().getReserved()).isEqualTo(3);
        }
    }

    // =========================================================================
    // commitSale
    // =========================================================================

    @Nested
    @DisplayName("commitSale")
    class CommitSaleTests {

        @Test
        @DisplayName("giảm đồng thời on_hand và reserved đúng qty=3")
        void commitSale_success_bothDecrement() {
            int rows = inventoryRepository.commitSale(BRANCH_ID, PRODUCT_ID, 3);

            assertThat(rows).isEqualTo(1);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(7);   // 10 - 3
            assertThat(inv.getReserved()).isEqualTo(0); // 3 - 3
        }

        @Test
        @DisplayName("qty=5 > reserved=3 → 0 dòng, không thay đổi")
        void commitSale_fail_reservedInsufficient() {
            int rows = inventoryRepository.commitSale(BRANCH_ID, PRODUCT_ID, 5);

            assertThat(rows).isEqualTo(0);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(10);
            assertThat(inv.getReserved()).isEqualTo(3);
        }
    }

    // =========================================================================
    // sellDirect
    // =========================================================================

    @Nested
    @DisplayName("sellDirect")
    class SellDirectTests {

        @Test
        @DisplayName("available=7 >= qty=4 → on_hand giảm, reserved không đổi")
        void sellDirect_success_onHandDecrement_reservedUnchanged() {
            int rows = inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 4);

            assertThat(rows).isEqualTo(1);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(6);   // 10 - 4
            assertThat(inv.getReserved()).isEqualTo(3); // không đổi
        }

        @Test
        @DisplayName("qty=8 > available=7 → 0 dòng, on_hand không đổi")
        void sellDirect_fail_insufficientAvailable() {
            int rows = inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 8);

            assertThat(rows).isEqualTo(0);
            assertThat(reload().getOnHand()).isEqualTo(10);
        }

        @Test
        @DisplayName("sellDirect(qty=7) = available → on_hand=3=reserved, available=0")
        void sellDirect_doesNotConsumeReserved_exactAvailable() {
            int rows = inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 7);

            assertThat(rows).isEqualTo(1);
            Inventory inv = reload();
            assertThat(inv.getOnHand()).isEqualTo(3);   // 10 - 7
            assertThat(inv.getReserved()).isEqualTo(3); // reserved không đổi

            // available = 0 → sellDirect tiếp theo phải thất bại
            assertThat(inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 1))
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("on_hand=10 đủ nhưng available=7 < 8 → thất bại (WHERE dùng on_hand-reserved)")
        void sellDirect_fail_onHandSufficientButAvailableInsufficient() {
            int rows = inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 8);

            assertThat(rows).isEqualTo(0);
        }
    }
}
