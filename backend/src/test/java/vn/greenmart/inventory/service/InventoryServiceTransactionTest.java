package vn.greenmart.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.greenmart.inventory.exception.InsufficientStockException;

/**
 * Kiểm tra ranh giới transaction của InventoryService (P2 — review issue #49).
 * Dùng @SpringBootTest để load Spring proxy. Mockito @InjectMocks không qua proxy;
 * @DataJpaTest có thể import service để kiểm tra proxy trong context nhỏ hơn.
 *
 * Xác minh:
 * 1. Gọi service không có transaction bị từ chối (MANDATORY → IllegalTransactionStateException).
 * 2. Thao tác thất bại (InsufficientStock) → exception, inventory không đổi.
 */
@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        // Tắt health group dùng redis/readiness vì Redis bị excluded trong test này
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "management.endpoint.health.group.liveness.include=livenessState"
    }
)
@Testcontainers
class InventoryServiceTransactionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(vn.greenmart.inventory.PostgresTestImage.IMAGE)
            .withDatabaseName("greenmart_tx_test")
            .withUsername("greenmart")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",                    postgres::getJdbcUrl);
        registry.add("spring.datasource.username",               postgres::getUsername);
        registry.add("spring.datasource.password",               postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto",            () -> "validate");
        registry.add("spring.flyway.enabled",                    () -> "true");
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private static final Long BRANCH_ID  = 10L;
    private static final Long PRODUCT_ID = 50L;
    // Product thứ hai để test rollback multi-row
    private static final Long PRODUCT_ID_2 = 51L;

    @ParameterizedTest
    @CsvSource({
            "reserve,RESERVE,3,0,3,10,7",
            "release,RELEASE,3,0,-3,10,1",
            "commitSale,SALE,3,-3,-3,7,1",
            "sellDirect,SALE,2,-2,0,8,4"
    })
    @Transactional
    void writesExactlyOneLedgerEntryWithPostUpdateBalances(String operation, String type,
            int qty, int deltaOnHand, int deltaReserved, int onHandAfter, int reservedAfter) {
        insertFixtures(PRODUCT_ID, 10, 4);
        switch (operation) {
            case "reserve" -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, qty, 5001L);
            case "release" -> inventoryService.release(BRANCH_ID, PRODUCT_ID, qty, 5001L);
            case "commitSale" -> inventoryService.commitSale(BRANCH_ID, PRODUCT_ID, qty, 5001L);
            case "sellDirect" -> inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, qty, 5001L);
            default -> throw new IllegalArgumentException(operation);
        }
        var entries = jdbc.queryForList("SELECT * FROM inventory_transaction WHERE ref_id=5001");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst()).containsEntry("type", type)
                .containsEntry("branch_id", BRANCH_ID).containsEntry("product_id", PRODUCT_ID)
                .containsEntry("ref_type", "ORDER").containsEntry("ref_id", 5001L)
                .containsEntry("delta_on_hand", deltaOnHand).containsEntry("delta_reserved", deltaReserved)
                .containsEntry("on_hand_after", onHandAfter).containsEntry("reserved_after", reservedAfter);
        assertThat(entries.getFirst().get("created_at")).isNotNull();
    }

    @Test
    void ledgerInsertFailureRollsBackInventoryAndEarlierLedgerEntries() {
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> insertFixtures(PRODUCT_ID, 5, 0));
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 1, 5002L);
                // NOT NULL ref_id fails after the second inventory UPDATE succeeds.
                inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 2, null);
            })).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForMap("SELECT on_hand,reserved,version FROM inventory WHERE branch_id=? AND product_id=?",
                    BRANCH_ID, PRODUCT_ID)).containsEntry("on_hand", 5)
                    .containsEntry("reserved", 0).containsEntry("version", 0L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
        } finally {
            cleanupCommittedFixture();
        }
    }

    @Test
    void ledgerRejectsSqlUpdateAndDeleteAfterCommit() {
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            insertFixtures(PRODUCT_ID, 5, 0);
            inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 2, 5003L);
        });
        try {
            for (String sql : java.util.List.of(
                    "UPDATE inventory_transaction SET delta_on_hand=0 WHERE ref_id=5003",
                    "DELETE FROM inventory_transaction WHERE ref_id=5003")) {
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update(sql)))
                        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            }
            assertThat(jdbc.queryForObject("SELECT delta_on_hand FROM inventory_transaction WHERE ref_id=5003",
                    Integer.class)).isEqualTo(-2);
        } finally {
            cleanupCommittedFixture();
        }
    }

    private void cleanupCommittedFixture() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // Test-only cleanup on an isolated container; no business delete API.
            jdbc.execute("TRUNCATE inventory_transaction");
            jdbc.update("DELETE FROM inventory WHERE branch_id=? AND product_id=?", BRANCH_ID, PRODUCT_ID);
            jdbc.update("DELETE FROM product WHERE id=?", PRODUCT_ID);
            jdbc.update("DELETE FROM branch WHERE id=?", BRANCH_ID);
            jdbc.update("DELETE FROM category WHERE id=98");
        });
    }

    // -------------------------------------------------------------------------
    // Test 1: MANDATORY — gọi không có transaction phải bị từ chối bởi Spring proxy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reserve không có @Transactional ở caller → IllegalTransactionStateException")
    void reserve_withoutTransaction_throwsIllegalTransactionState() {
        assertThatThrownBy(() -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 1, 999L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("sellDirect không có @Transactional → IllegalTransactionStateException")
    void sellDirect_withoutTransaction_throwsIllegalTransactionState() {
        assertThatThrownBy(() -> inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 1, 999L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // -------------------------------------------------------------------------
    // Test 2: Thao tác thất bại → exception, inventory không đổi
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reserve thất bại (InsufficientStock) → exception, reserved không đổi")
    @Transactional
    void reserve_fail_inventoryUnchanged() {
        insertFixtures(PRODUCT_ID, 2, 0);  // on_hand=2, reserved=0, available=2

        assertThatThrownBy(() -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 5, 1002L))
                .isInstanceOf(InsufficientStockException.class);

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE branch_id=? AND product_id=?",
                Integer.class, BRANCH_ID, PRODUCT_ID);
        assertThat(reserved).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
    }

    @Test
    @DisplayName("reserve thành công trong @Transactional → reserved tăng đúng")
    @Transactional
    void reserve_withinTransaction_reservedIncreases() {
        insertFixtures(PRODUCT_ID, 5, 0);  // on_hand=5, reserved=0, available=5

        inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 3, 1003L);

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE branch_id=? AND product_id=?",
                Integer.class, BRANCH_ID, PRODUCT_ID);
        assertThat(reserved).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Test 3: Rollback thực sự — thao tác đầu thành công, thao tác sau thất bại
    // → toàn bộ transaction rollback, cả hai dòng về trạng thái ban đầu
    //
    // Không dùng @Transactional ở cấp test để tránh nhầm lẫn:
    // - @Transactional ở test method tự rollback sau test (Spring Test behaviour)
    // - Ở đây cần kiểm chứng rollback do exception, đọc kết quả bằng TX độc lập sau đó
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reserve p1 thành công, reserve p2 thất bại → cả p1 lẫn p2 rollback về 0")
    void reserve_partialFailure_fullRollback() {
        // Setup: commit fixture trước (ngoài transaction sẽ test)
        TransactionTemplate setup = new TransactionTemplate(txManager);
        setup.execute(status -> {
            insertFixtures(PRODUCT_ID,   5, 0);  // on_hand=5, available=5
            insertFixtures(PRODUCT_ID_2, 2, 0);  // on_hand=2, available=2
            return null;
        });

        // Mở transaction qua TransactionTemplate, không dùng @Transactional ở test method
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                // Thao tác 1: reserve p1 qty=3 — thành công (available=5>=3)
                inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 3, 2001L);

                // Thao tác 2: reserve p2 qty=5 — thất bại (available=2<5)
                // InsufficientStockException là RuntimeException → sẽ rollback TX
                inventoryService.reserve(BRANCH_ID, PRODUCT_ID_2, 5, 2002L);
                return null;
            });
        } catch (InsufficientStockException expected) {
            // Exception thoát khỏi TransactionTemplate → TX đã rollback
        }

        // Đọc bằng transaction mới để xác nhận rollback thực sự
        TransactionTemplate verify = new TransactionTemplate(txManager);
        verify.execute(status -> {
            // p1: thao tác thứ nhất phải được rollback — reserved về 0
            Integer reserved1 = jdbc.queryForObject(
                    "SELECT reserved FROM inventory WHERE branch_id=? AND product_id=?",
                    Integer.class, BRANCH_ID, PRODUCT_ID);
            assertThat(reserved1)
                    .as("reserved của p1 phải được rollback về 0 sau khi p2 thất bại")
                    .isEqualTo(0);

            // p2: không thay đổi
            Integer reserved2 = jdbc.queryForObject(
                    "SELECT reserved FROM inventory WHERE branch_id=? AND product_id=?",
                    Integer.class, BRANCH_ID, PRODUCT_ID_2);
            assertThat(reserved2)
                    .as("reserved của p2 phải giữ nguyên 0")
                    .isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
            return null;
        });

        // Dọn fixture vì đã commit (không có @Transactional tự rollback)
        TransactionTemplate cleanup = new TransactionTemplate(txManager);
        cleanup.execute(status -> {
            jdbc.execute("TRUNCATE inventory_transaction");
            jdbc.update("DELETE FROM inventory WHERE branch_id=? AND product_id=?", BRANCH_ID, PRODUCT_ID);
            jdbc.update("DELETE FROM inventory WHERE branch_id=? AND product_id=?", BRANCH_ID, PRODUCT_ID_2);
            jdbc.update("DELETE FROM product WHERE id=? OR id=?", PRODUCT_ID, PRODUCT_ID_2);
            jdbc.update("DELETE FROM branch  WHERE id=?", BRANCH_ID);
            jdbc.update("DELETE FROM category WHERE id=?", 98);
            return null;
        });
    }



    @Autowired
    private vn.greenmart.inventory.repository.InventoryRepository repository;

    @Test
    @Transactional
    void bulkUpdate_refreshesPreviouslyLoadedInventory() {
        insertFixtures(PRODUCT_ID, 5, 0);
        var id = new vn.greenmart.inventory.domain.Inventory.InventoryId(BRANCH_ID, PRODUCT_ID);
        assertThat(repository.findById(id).orElseThrow().getOnHand()).isEqualTo(5);
        inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 2, 3001L);
        assertThat(repository.findById(id).orElseThrow().getOnHand()).isEqualTo(3);
    }

    @Test
    @Transactional
    void insufficientStock_containsApiDetailsAndActualAvailable() {
        insertFixtures(PRODUCT_ID, 5, 3);
        assertThatThrownBy(() -> inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 3, 3002L))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(ex.getAvailable()).isEqualTo(2);
                    assertThat(ex.getDetails()).isEqualTo(java.util.Map.of("lines", java.util.List.of(
                            java.util.Map.of("productId", PRODUCT_ID, "sku", "TX-SKU-50",
                                    "requested", 3, "available", 2))));
                });
    }

    @Test
    void concurrentDirectSales() throws Exception { concurrentStockChanges(0); }

    @Test
    @Transactional
    void missingInventory_reportsZeroAvailable() {
        insertFixtures(PRODUCT_ID, 5, 0);
        jdbc.update("DELETE FROM inventory WHERE branch_id=? AND product_id=?", BRANCH_ID, PRODUCT_ID);
        assertThatThrownBy(() -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 1, 3003L))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getAvailable()).isZero();
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                });
    }

    @Test
    void concurrentReservations() throws Exception { concurrentStockChanges(1); }

    @Test
    void concurrentMixedSalesAndReservations() throws Exception { concurrentStockChanges(2); }

    private void concurrentStockChanges(int mode) throws Exception {
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> insertFixtures(PRODUCT_ID, 5, 0));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(10);
        var ready = new java.util.concurrent.CountDownLatch(10);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for (int i = 0; i < 10; i++) {
                final boolean reserve = mode == 1 || (mode == 2 && i % 2 == 0);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start barrier timed out");
                    }
                    try {
                        new TransactionTemplate(txManager).executeWithoutResult(status -> {
                            if (reserve) inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 1, 4001L);
                            else inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 1, 4001L);
                        });
                        return true;
                    } catch (InsufficientStockException expected) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (var future : futures) {
                if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            }
            assertThat(successes).isEqualTo(5);
            tx.executeWithoutResult(status -> {
                var row = jdbc.queryForMap("SELECT on_hand,reserved,version FROM inventory WHERE branch_id=? AND product_id=?",
                        BRANCH_ID, PRODUCT_ID);
                int onHand = ((Number) row.get("on_hand")).intValue();
                int reserved = ((Number) row.get("reserved")).intValue();
                assertThat(onHand - reserved).isZero();
                assertThat(reserved).isBetween(0, onHand);
                assertThat(((Number) row.get("version")).longValue()).isEqualTo(5);
                if (mode == 0) assertThat(onHand).isZero();
                if (mode == 1) assertThat(reserved).isEqualTo(5);
                var ledger = jdbc.queryForList("SELECT * FROM inventory_transaction ORDER BY id");
                assertThat(ledger).hasSize(5);
                int runningOnHand = 5;
                int runningReserved = 0;
                for (var entry : ledger) {
                    runningOnHand += ((Number) entry.get("delta_on_hand")).intValue();
                    runningReserved += ((Number) entry.get("delta_reserved")).intValue();
                    assertThat(entry).containsEntry("on_hand_after", runningOnHand)
                            .containsEntry("reserved_after", runningReserved);
                }
                assertThat(runningOnHand).isEqualTo(onHand);
                assertThat(runningReserved).isEqualTo(reserved);
            });
        } finally {
            start.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not terminate");
            }
            tx.executeWithoutResult(status -> {
                jdbc.execute("TRUNCATE inventory_transaction");
                jdbc.update("DELETE FROM inventory WHERE branch_id=? AND product_id=?", BRANCH_ID, PRODUCT_ID);
                jdbc.update("DELETE FROM product WHERE id=?", PRODUCT_ID);
                jdbc.update("DELETE FROM branch WHERE id=?", BRANCH_ID);
                jdbc.update("DELETE FROM category WHERE id=?", 98);
            });
        }
    }
    private void insertFixtures(long productId, int onHand, int reserved) {
        // Xóa theo thứ tự FK: inventory trước, rồi product
        // Branch và category chỉ xóa nếu không còn inventory nào tham chiếu
        jdbc.update("DELETE FROM inventory WHERE branch_id=? AND product_id=?", BRANCH_ID, productId);
        jdbc.update("DELETE FROM product  WHERE id=?", productId);

        // Tạo branch/category nếu chưa có (ON CONFLICT DO NOTHING)
        jdbc.update("INSERT INTO category (id, name) VALUES (?,?) ON CONFLICT DO NOTHING", 98, "TxTest");
        jdbc.update("INSERT INTO branch (id,code,name,is_active) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                BRANCH_ID, "TX-CN", "TX Branch", true);
        String sku = "TX-SKU-" + productId;
        jdbc.update("INSERT INTO product (id,sku,name,unit,category_id,sale_price,cost_price,is_active) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                productId, sku, "TX Product", "cai", 98, 5000, 4000, true);
        jdbc.update("INSERT INTO inventory (branch_id,product_id,on_hand,reserved,version,updated_at) VALUES (?,?,?,?,0,NOW())",
                BRANCH_ID, productId, onHand, reserved);
    }
}
