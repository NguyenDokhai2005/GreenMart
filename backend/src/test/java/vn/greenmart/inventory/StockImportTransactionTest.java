package vn.greenmart.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.inventory.dto.StockDocumentRequests;
import vn.greenmart.inventory.domain.StockDocumentType;
import vn.greenmart.inventory.service.StockDocumentService;
import vn.greenmart.inventory.service.InventoryService;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@Testcontainers
class StockImportTransactionTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StockDocumentService documents;
    @Autowired InventoryService inventory;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(81,'B81','Branch 81'),(82,'B82','Branch 82')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) "
                + "VALUES(81,'MILK','Milk','hop',81,29000,10000),(82,'OTHER','Other','hop',81,10000,5000)");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES "
                + "(810,'maker','hash','Maker','STOREKEEPER'),(811,'manager','hash','Manager','BRANCH_MANAGER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(810,81),(810,82),(811,81),(811,82)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(81,81,10,3),(82,81,10,2)");
    }

    private <T> T authenticated(long actor, String role, java.util.concurrent.Callable<T> action) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(actor), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(context);
        try { return action.call(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private long draft(long branch, List<StockDocumentRequests.Item> items) throws Exception {
        return authenticated(810, "STOREKEEPER", () -> documents.create(new StockDocumentRequests.Create(
                StockDocumentType.IMPORT, branch, null, "Supplier", null, null, items)).id());
    }

    private String approve(long id, CountDownLatch start) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
        return authenticated(811, "BRANCH_MANAGER", () -> {
            try { return documents.approve(id).status().name(); }
            catch (AppException ex) { return ex.getCode(); }
        });
    }

    @Test
    void twoBranchesImportSameProductWithoutLostStockOrCostUpdates() throws Exception {
        var lines = List.of(new StockDocumentRequests.Item(81L, 10, 20000L, null));
        long first = draft(81, lines), second = draft(82, lines);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> approve(first, start));
            var b = pool.submit(() -> approve(second, start));
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo("APPROVED");
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo("APPROVED");
        }
        assertThat(jdbc.queryForList("SELECT on_hand FROM inventory ORDER BY branch_id", Integer.class)).containsExactly(20, 20);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isEqualTo(15000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction WHERE type='IMPORT'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document WHERE status='APPROVED' AND approved_at IS NOT NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void simultaneousApprovalOfOneDocumentHasExactlyOneStockEffect() throws Exception {
        long id = draft(81, List.of(new StockDocumentRequests.Item(81L, 10, 20000L, null)));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> approve(id, start));
            var b = pool.submit(() -> approve(id, start));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("APPROVED", "INVALID_STATUS");
        }
        assertThat(jdbc.queryForObject("SELECT SUM(on_hand) FROM inventory", Long.class)).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isEqualTo(1);
    }

    @Test
    void ledgerFailureOnSecondLineRollsBackFirstLineCostStockAndApproval() throws Exception {
        long id = draft(81, List.of(new StockDocumentRequests.Item(81L, 10, 20000L, null),
                new StockDocumentRequests.Item(82L, 1, 9000L, null)));
        jdbc.execute("ALTER TABLE inventory_transaction ADD CONSTRAINT test_reject_import CHECK(product_id<>82)");
        try {
            assertThatThrownBy(() -> authenticated(811, "BRANCH_MANAGER", () -> documents.approve(id)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForList("SELECT cost_price FROM product ORDER BY id", Long.class)).containsExactly(10000L, 5000L);
            assertThat(jdbc.queryForObject("SELECT SUM(on_hand) FROM inventory", Long.class)).isEqualTo(20L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='APPROVE_STOCK_DOCUMENT'", Integer.class)).isZero();
            assertThat(jdbc.queryForMap("SELECT status,approved_at,approved_by FROM stock_document WHERE id=?", id))
                    .containsEntry("status", "DRAFT").containsEntry("approved_at", null).containsEntry("approved_by", null);
        } finally { jdbc.execute("ALTER TABLE inventory_transaction DROP CONSTRAINT test_reject_import"); }
    }

    @Test
    void overflowingValuationCannotPartiallyApprove() throws Exception {
        long id = draft(81, List.of(new StockDocumentRequests.Item(81L, 1, 1L, null)));
        jdbc.update("UPDATE product SET cost_price=? WHERE id=81", Long.MAX_VALUE);
        assertThat(approve(id, new CountDownLatch(0))).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT SUM(on_hand) FROM inventory", Long.class)).isEqualTo(20L);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
    }

    @Test
    void importAndSaleCanOverlapWithoutChangingCostOnSale() throws Exception {
        long id = draft(81, List.of(new StockDocumentRequests.Item(81L, 10, 20000L, null)));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> approve(id, start));
            var b = pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                new TransactionTemplate(manager).executeWithoutResult(tx -> inventory.sellDirect(81L, 81L, 1, 900L));
                return "SOLD";
            });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo("APPROVED");
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo("SOLD");
        }
        assertThat(jdbc.queryForObject("SELECT SUM(on_hand) FROM inventory", Long.class)).isEqualTo(29L);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isIn(13333L, 13448L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isEqualTo(2);
    }
}
