package vn.greenmart.inventory.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaConstraintsMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateExistingDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migrate(dataSource, "12");
        jdbc.update("INSERT INTO branch(id, code, name) VALUES (41, 'B41', 'Branch')");
        jdbc.update("INSERT INTO category(id, name) VALUES (41, 'Drinks')");
        jdbc.update("""
                INSERT INTO product(id, sku, name, unit, category_id, sale_price, cost_price)
                VALUES (41, 'TEA41', 'Tea', 'box', 41, 10000, 5000)
                """);
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, full_name, role)
                VALUES (41, 'tester41', 'fixture', 'Tester', 'OWNER')
                """);
        jdbc.update("""
                INSERT INTO audit_log(user_id, action, entity) VALUES (41, 'ORIGINAL', 'inventory')
                """);
        migrate(dataSource, "13");
    }

    private static void migrate(DriverManagerDataSource dataSource, String target) {
        Flyway.configure().dataSource(dataSource).target(target)
                .placeholders(Map.of("forecast_user", "forecast_ro")).load().migrate();
    }

    @ParameterizedTest
    @CsvSource({
            "sales_order, branch_id created_at",
            "sales_order, status reserved_until",
            "inventory_transaction, branch_id product_id created_at",
            "daily_sales, product_id branch_id sale_date",
            "forecast_result, run_id branch_id product_id"
    })
    void requiredIndexHasLeadingColumnsInOrder(String table, String columns) {
        var indexColumns = jdbc.queryForList("""
                SELECT array_to_string(ARRAY(
                    SELECT a.attname
                    FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, position)
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                    WHERE k.position <= i.indnkeyatts
                    ORDER BY k.position
                ), ' ')
                FROM pg_index i
                JOIN pg_class idx ON idx.oid = i.indexrelid
                JOIN pg_am am ON am.oid = idx.relam
                WHERE i.indrelid = ?::regclass AND i.indisvalid AND i.indpred IS NULL AND am.amname = 'btree'
                """, String.class, table);
        assertThat(indexColumns).anyMatch(keys -> keys.equals(columns) || keys.startsWith(columns + " "));
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 0, chk_on_hand_non_negative",
            "0, -1, chk_reserved_non_negative",
            "1, 2, chk_reserved_le_on_hand"
    })
    void rejectsInvalidStockOnDirectInsert(int onHand, int reserved, String constraint) {
        assertSqlRejected("""
                INSERT INTO inventory(branch_id, product_id, on_hand, reserved) VALUES (41, 41, ?, ?)
                """, "23514", constraint, onHand, reserved);
    }

    @Test
    void rejectsMissingForeignKeyAndDuplicateChannelSku() {
        assertSqlRejected("INSERT INTO inventory(branch_id, product_id) VALUES (41, -1)",
                "23503", "inventory_product_id_fkey");
        jdbc.update("""
                INSERT INTO channel_product(channel_id, product_id, external_sku)
                SELECT id, 41, 'TEA-CHANNEL' FROM sales_channel WHERE code = 'WEB'
                """);
        jdbc.update("""
                INSERT INTO product(id, sku, name, unit, category_id, sale_price, cost_price)
                VALUES (42, 'TEA42', 'Tea', 'box', 41, 10000, 5000)
                """);
        assertSqlRejected("""
                INSERT INTO channel_product(channel_id, product_id, external_sku)
                SELECT id, 42, 'TEA-CHANNEL' FROM sales_channel WHERE code = 'WEB'
                """, "23505", "channel_product_channel_id_external_sku_key");
    }

    @Test
    void auditHistorySurvivesUpgradeAndCanOnlyBeAppended() {
        assertSqlRejected("UPDATE audit_log SET action = 'CHANGED' WHERE action = 'ORIGINAL'", "55000", null);
        assertSqlRejected("DELETE FROM audit_log WHERE action = 'ORIGINAL'", "55000", null);
        assertSqlRejected("DELETE FROM app_user WHERE id = 41", "55000", null);
        jdbc.update("INSERT INTO audit_log(user_id, action, entity) VALUES (41, 'NEW', 'inventory')");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE user_id = 41 ORDER BY id", String.class))
                .containsExactly("ORIGINAL", "NEW");
    }

    private static void assertSqlRejected(String sql, String state, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().satisfies(cause -> {
                    var error = (PSQLException) cause;
                    assertThat(error.getSQLState()).isEqualTo(state);
                    if (constraint != null) {
                        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                    }
                });
    }
}
