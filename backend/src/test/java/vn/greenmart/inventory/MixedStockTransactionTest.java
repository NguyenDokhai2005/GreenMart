package vn.greenmart.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.inventory.domain.StockDocumentType;
import vn.greenmart.inventory.dto.StockDocumentRequests;
import vn.greenmart.inventory.service.StockDocumentService;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.dto.OrderLineRequest;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PosOrderRequest;
import vn.greenmart.sales.service.PosOrderService;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "greenmart.outbox.enabled=false"
})
@Testcontainers
class MixedStockTransactionTest {
    private static final long BRANCH = 81L;
    private static final long PRODUCT = 81L;
    private static final long MAKER = 810L;
    private static final long MANAGER = 811L;
    private static final long CASHIER = 812L;
    private static final int INITIAL_ON_HAND = 5;
    private static final int RESERVED = 2;
    private static final int OUTGOING_QUANTITY = 3;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StockDocumentService documents;
    @Autowired PosOrderService orders;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.execute("TRUNCATE branch, category, app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES (?, 'B81', 'Branch 81')", BRANCH);
        jdbc.update("INSERT INTO sales_channel(code,name) VALUES ('POS','POS')");
        jdbc.update("INSERT INTO category(id,name) VALUES (81,'Milk')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                VALUES (?, 'MILK', 'Milk', 'hop', 81, 10000, 5000)
                """, PRODUCT);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES
                (?, 'maker', 'hash', 'Maker', 'STOREKEEPER'),
                (?, 'manager', 'hash', 'Manager', 'BRANCH_MANAGER'),
                (?, 'cashier', 'hash', 'Cashier', 'CASHIER')
                """, MAKER, MANAGER, CASHIER);
        for (long user : List.of(MAKER, MANAGER, CASHIER)) {
            jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES (?,?)", user, BRANCH);
        }
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES (?,?,?,?)",
                BRANCH, PRODUCT, INITIAL_ON_HAND, RESERVED);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 2})
    void concurrentImportExportAndPosSaleReconcileStockAndLedger(int imported) throws Exception {
        long importId = draft(StockDocumentType.IMPORT, imported);
        long exportId = draft(StockDocumentType.EXPORT, OUTGOING_QUANTITY);
        var importReady = new CountDownLatch(1);
        var allowImportCommit = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        Result exported;
        Result sold;
        try {
            var importing = workers.submit(() -> run("issue56-import", MANAGER, "BRANCH_MANAGER", () -> {
                var document = documents.approve(importId);
                assertThat(document.status().name()).isEqualTo("APPROVED");
                importReady.countDown();
                await(allowImportCommit);
                return document.id();
            }));
            await(importReady);

            var exporting = workers.submit(() -> run("issue56-export", MANAGER, "BRANCH_MANAGER", () -> {
                var document = documents.approve(exportId);
                assertThat(document.status().name()).isEqualTo("APPROVED");
                return document.id();
            }));
            var selling = workers.submit(() -> run("issue56-pos", CASHIER, "CASHIER", () -> {
                long total = 10000L * OUTGOING_QUANTITY;
                var request = new PosOrderRequest(BRANCH, null, List.of(new OrderLineRequest(PRODUCT, OUTGOING_QUANTITY)),
                        null, new PaymentRequest(PaymentMethod.CASH, total, null), total);
                var order = orders.createPosOrder(request, "issue56-pos");
                assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
                return order.id();
            }));

            awaitBothStockRemovalsBlocked();
            allowImportCommit.countDown();
            assertThat(importing.get(20, TimeUnit.SECONDS)).isEqualTo(new Result(importId, null));
            exported = exporting.get(20, TimeUnit.SECONDS);
            sold = selling.get(20, TimeUnit.SECONDS);
        } finally {
            allowImportCommit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        int expectedRemovals = imported == 3 ? 2 : 1;
        assertThat(List.of(exported, sold).stream().filter(Result::succeeded).count()).isEqualTo(expectedRemovals);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?", String.class, exportId))
                .isEqualTo(exported.succeeded() ? "APPROVED" : "DRAFT");
        var orderIds = jdbc.queryForList("SELECT id FROM sales_order", Long.class);
        assertThat(orderIds).containsExactlyElementsOf(sold.succeeded() ? List.of(sold.id()) : List.of());

        var expected = new HashSet<Movement>();
        expected.add(new Movement("IMPORT", "STOCK_DOCUMENT", importId, imported));
        if (exported.succeeded()) expected.add(new Movement("EXPORT", "STOCK_DOCUMENT", exportId, -OUTGOING_QUANTITY));
        if (sold.succeeded()) expected.add(new Movement("SALE", "ORDER", sold.id(), -OUTGOING_QUANTITY));

        int onHand = INITIAL_ON_HAND;
        var ledger = jdbc.queryForList("SELECT * FROM inventory_transaction ORDER BY id");
        assertThat(ledger).hasSize(1 + expectedRemovals);
        var versions = jdbc.queryForList("""
                SELECT (payload->>'version')::bigint FROM outbox_event
                WHERE event_type='stock.changed' ORDER BY id
                """, Long.class);
        assertThat(versions).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 1L + expectedRemovals).boxed().toList());
        for (var row : ledger) {
            int delta = (int) row.get("delta_on_hand");
            var movement = new Movement((String) row.get("type"), (String) row.get("ref_type"),
                    (long) row.get("ref_id"), delta);
            assertThat(expected.remove(movement)).as("giao dịch đúng chứng từ và xuất hiện đúng một lần: %s", movement)
                    .isTrue();
            onHand += delta;
            assertThat(onHand).isGreaterThanOrEqualTo(RESERVED);
            assertThat(row).containsEntry("branch_id", BRANCH).containsEntry("product_id", PRODUCT)
                    .containsEntry("on_hand_after", onHand).containsEntry("delta_reserved", 0)
                    .containsEntry("reserved_after", RESERVED);
        }
        assertThat(expected).isEmpty();
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved FROM inventory WHERE branch_id=? AND product_id=?",
                BRANCH, PRODUCT)).containsEntry("on_hand", onHand).containsEntry("reserved", RESERVED);
    }

    @Test
    void databaseRejectsInvalidStockEvenWhenServiceIsBypassed() {
        assertRejected("UPDATE inventory SET on_hand=-1, reserved=0", "chk_on_hand_non_negative");
        assertRejected("UPDATE inventory SET reserved=-1", "chk_reserved_non_negative");
        assertRejected("UPDATE inventory SET reserved=on_hand+1", "chk_reserved_le_on_hand");
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved FROM inventory"))
                .containsEntry("on_hand", INITIAL_ON_HAND).containsEntry("reserved", RESERVED);
    }

    private void assertRejected(String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().satisfies(cause -> {
                    var error = (PSQLException) cause;
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }

    private long draft(StockDocumentType type, int quantity) {
        boolean importing = type == StockDocumentType.IMPORT;
        return authenticated(MAKER, "STOREKEEPER", () -> documents.create(new StockDocumentRequests.Create(
                type, BRANCH, null, importing ? "Supplier" : null, importing ? null : "DAMAGED", null,
                List.of(new StockDocumentRequests.Item(PRODUCT, quantity, importing ? 5000L : null, null)))).id());
    }

    private Result run(String name, long actor, String role, Supplier<Long> work) {
        try {
            Long id = authenticated(actor, role, () -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, name);
                jdbc.execute("SET LOCAL statement_timeout = '20s'");
                return work.get();
            }));
            return new Result(id, null);
        } catch (AppException error) {
            assertThat(error.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK.name());
            return new Result(null, error.getCode());
        }
    }

    private void awaitBothStockRemovalsBlocked() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE application_name IN ('issue56-export', 'issue56-pos')
                      AND cardinality(pg_blocking_pids(pid)) > 0
                    """, Integer.class);
            if (waiting == 2) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Xuất hủy và POS phải cùng chờ khóa trong khi giao dịch nhập còn mở");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("đồng bộ giao dịch trong thời hạn").isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Giao dịch bị ngắt khi chờ đồng bộ", error);
        }
    }

    private static <T> T authenticated(long actor, String role, Supplier<T> action) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(actor, null, "ROLE_" + role));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private record Result(Long id, String error) {
        boolean succeeded() {
            return error == null;
        }
    }

    private record Movement(String type, String refType, long refId, int delta) {}
}
