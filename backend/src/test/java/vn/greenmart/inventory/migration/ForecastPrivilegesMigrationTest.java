package vn.greenmart.inventory.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ForecastPrivilegesMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void forecastCanReadOnlyRequiredTablesAfterMigration(boolean legacyDefaultGrant) throws Exception {
        var owner = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(owner);
        String schema = legacyDefaultGrant ? "legacy_forecast" : "fresh_forecast";
        String role = schema + "_reader";
        String password = "test-fixture";
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        if (legacyDefaultGrant) {
            jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT SELECT ON TABLES TO " + role);
            migrate(owner, schema, role, "13");
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(?, ?, 'SELECT')", Boolean.class,
                    role, schema + ".app_user")).isTrue();
        }

        migrate(owner, schema, role, "14");
        jdbc.execute("CREATE TABLE " + schema + ".future_table(id INT)");
        var forecast = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), role, password);
        try (var connection = forecast.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (var result = statement.executeQuery("SELECT current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(role);
            }
            for (String table : List.of("branch", "product", "sales_order", "order_item",
                    "return_document", "return_item", "promotion", "promotion_branch", "promotion_product",
                    "inventory_transaction", "daily_sales", "forecast_run", "forecast_result")) {
                statement.executeQuery("SELECT * FROM " + table + " LIMIT 0").close();
            }
            assertDenied(statement, "SELECT password_hash FROM app_user");
            assertDenied(statement, "SELECT webhook_secret FROM sales_channel");
            assertDenied(statement, "SELECT * FROM audit_log");
            assertDenied(statement, "SELECT * FROM customer");
            assertDenied(statement, "SELECT * FROM future_table");
            assertDenied(statement, "DELETE FROM sales_order WHERE false");

            try (var result = statement.executeQuery("""
                    INSERT INTO forecast_run(status, model_version) VALUES ('RUNNING', 'test') RETURNING id
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isPositive();
            }
            assertThat(statement.executeUpdate("UPDATE forecast_run SET status = 'DONE'")).isEqualTo(1);
            assertThat(statement.executeUpdate("DELETE FROM forecast_run")).isEqualTo(1);
        }
    }

    private static void assertDenied(Statement statement, String sql) {
        assertThatThrownBy(() -> statement.execute(sql)).isInstanceOfSatisfying(PSQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    }

    private static void migrate(DriverManagerDataSource owner, String schema, String role, String target) {
        Flyway.configure().dataSource(owner).schemas(schema).defaultSchema(schema).target(target)
                .placeholders(Map.of("forecast_user", role)).load().migrate();
    }
}
