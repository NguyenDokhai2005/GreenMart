package vn.greenmart.inventory.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Each test upgrades an isolated schema, without modifying deployed V1-V3. */
@Testcontainers
class DocumentTimestampsMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(vn.greenmart.inventory.PostgresTestImage.IMAGE);
    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        schema = "timestamps_" + UUID.randomUUID().toString().replace("-", "");
        migrate("3");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(1,'B1','Branch')");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(1,'maker','hash','Maker','STOREKEEPER')");
        jdbc.update("INSERT INTO sales_order(id,code,channel_id,branch_id,status,subtotal,total) "
                + "SELECT 1,'ORDER1',id,1,'COMPLETED',0,0 FROM sales_channel WHERE code='POS'");
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target(target)
                .placeholders(Map.of("forecast_user", "forecast_ro"))
                .load().migrate();
    }

    private void returnDocument(long id, String status) {
        jdbc.update("INSERT INTO return_document(id,code,order_id,branch_id,reason,refund_amount,status,created_by) "
                + "VALUES(?,?,1,1,'Quality',0,?,1)", id, "RETURN" + id, status);
    }

    private void stockDocument(long id, String status) {
        jdbc.update("INSERT INTO stock_document(id,code,type,branch_id,status,created_by,created_at) "
                + "VALUES(?,?,'IMPORT',1,?,1,'2026-10-01T09:00:00+07:00')", id, "STOCK" + id, status);
    }

    @Test
    void migratesEmptyDocumentTablesAndIsRepeatable() {
        migrate("4");
        migrate("4");
        returnDocument(1, "DRAFT");
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL AND approved_at IS NULL FROM return_document WHERE id=1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
                + "AND ((table_name='return_document' AND column_name IN ('created_at','approved_at')) "
                + "OR (table_name='stock_document' AND column_name IN ('approved_at','received_at')))", Integer.class, schema)).isEqualTo(4);
    }

    @Test
    void backfillsExistingApprovedAndReceivedDocuments() {
        returnDocument(1, "APPROVED");
        returnDocument(2, "DRAFT");
        returnDocument(3, "CANCELLED");
        // Same number of approved return documents as the legacy seed (#209).
        for (long id = 4; id <= 152; id++) {
            returnDocument(id, "APPROVED");
        }
        stockDocument(1, "APPROVED");
        stockDocument(2, "IN_TRANSIT");
        stockDocument(3, "RECEIVED");
        stockDocument(4, "DRAFT");
        stockDocument(5, "CANCELLED");
        migrate("4");
        assertThat(jdbc.queryForObject("SELECT approved_at=created_at FROM return_document WHERE id=1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_document WHERE status='APPROVED' AND approved_at=created_at", Integer.class)).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_document WHERE id IN (2,3) AND approved_at IS NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document WHERE id IN (1,2,3) AND approved_at=created_at", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT received_at=approved_at FROM stock_document WHERE id=3", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document WHERE id IN (4,5) AND approved_at IS NULL AND received_at IS NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsApprovedDocumentsWithoutApprovalTime() {
        migrate("4");
        assertThatThrownBy(() -> returnDocument(1, "APPROVED")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_return_approved_at");
        assertThatThrownBy(() -> stockDocument(1, "APPROVED")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_stock_approved_at");
    }

    @Test
    void rejectsReceivingBeforeApprovalAndApprovalBeforeCreation() {
        migrate("4");
        stockDocument(1, "DRAFT");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO stock_document(code,type,branch_id,status,created_by,created_at,approved_at,received_at) "
                + "VALUES('BAD-RECEIPT','IMPORT',1,'RECEIVED',1,'2026-10-01T09:00:00+07:00',"
                + "'2026-10-02T10:00:00+07:00','2026-10-02T09:00:00+07:00')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_stock_time_order");
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_document SET status='RECEIVED', "
                + "approved_at='2026-10-02T10:00:00+07:00', received_at='2026-10-02T09:00:00+07:00' WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_stock_time_order");
        returnDocument(1, "DRAFT");
        assertThatThrownBy(() -> jdbc.update("UPDATE return_document SET status='APPROVED', approved_at=created_at-interval '1 second' WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_return_time_order");
    }

    @Test
    void permitsValidApprovalAndReceiptAndRejectsMissingReceiptTime() {
        migrate("4");
        stockDocument(1, "DRAFT");
        jdbc.update("UPDATE stock_document SET status='IN_TRANSIT',approved_at=created_at+interval '1 hour' WHERE id=1");
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_document SET status='RECEIVED' WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_stock_received_at");
        jdbc.update("UPDATE stock_document SET status='RECEIVED',received_at=approved_at+interval '1 hour' WHERE id=1");
        assertThat(jdbc.queryForObject("SELECT received_at>approved_at FROM stock_document WHERE id=1", Boolean.class)).isTrue();
    }

    @Test
    void migratesFreshDatabaseSchemaDirectlyToV4() {
        schema = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        migrate("4");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema)
                .placeholders(Map.of("forecast_user", "forecast_ro"))
                .load().validate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
                + "AND table_name='return_document' AND column_name='approved_at'", Integer.class, schema)).isEqualTo(1);
    }
}
