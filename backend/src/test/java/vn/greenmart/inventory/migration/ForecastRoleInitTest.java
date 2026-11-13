package vn.greenmart.inventory.migration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ForecastRoleInitTest {
    private static final String ROLE = "forecast_ro";
    private static final String FIXTURE_PASSWORD = "forecast-init-test-fixture";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE)
            .withEnv("FORECAST_DB_USER", ROLE)
            .withEnv("FORECAST_DB_PASSWORD", FIXTURE_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../infra/postgres/init/01-forecast-role.sh").toAbsolutePath(), 0644),
                    "/docker-entrypoint-initdb.d/01-forecast-role.sh");

    @Test void newDatabaseDoesNotGrantForecastRoleAccessToSensitiveTables() throws SQLException {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of("forecast_user", ROLE)).load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), ROLE, FIXTURE_PASSWORD);
             var statement = connection.createStatement()) {
            for (String query : new String[]{"SELECT count(password_hash) FROM app_user",
                    "SELECT count(webhook_secret) FROM sales_channel", "SELECT count(*) FROM audit_log"}) {
                assertThatThrownBy(() -> statement.executeQuery(query)).isInstanceOfSatisfying(
                        SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
            }
        }
    }
}
