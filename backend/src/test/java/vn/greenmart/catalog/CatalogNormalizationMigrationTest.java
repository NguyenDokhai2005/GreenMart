package vn.greenmart.catalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CatalogNormalizationMigrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;

    @BeforeEach void legacySchema() {
        schema = "normalize_" + UUID.randomUUID().toString().replace("-", "");
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        dataSource = new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migrate("18");
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(target)
                .placeholders(Map.of("forecast_user", "forecast_ro")).load().migrate();
    }

    @Test void upgradePreservesLegacyNamesIdsAndMappings() {
        jdbc.update("INSERT INTO category(id,name) VALUES(1,'  Sữa   Tươi  '),(2,'Sua Tuoi')");
        jdbc.update("INSERT INTO category(id,name,parent_id) VALUES(3,'Sữa Tươi',1),(4,'Sữa Tươi',2)");
        productAndMapping("Ab-1._:z");
        var categories = jdbc.queryForList("SELECT * FROM category ORDER BY id");
        var mapping = jdbc.queryForList("SELECT * FROM channel_product");
        migrate("20");
        assertThat(jdbc.queryForList("SELECT * FROM category ORDER BY id")).isEqualTo(categories);
        assertThat(jdbc.queryForList("SELECT * FROM channel_product")).isEqualTo(mapping);
        assertThat(jdbc.queryForObject("SELECT greenmart_category_key(?)=greenmart_category_key(?)", Boolean.class,
                " SỮA TƯƠI ", "Su\u031b\u0303a Tươi")).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO category(id,name) VALUES(10,'SỮA TƯƠI')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"root", "child", "blank", "sku-space", "sku-markup"})
    void invalidLegacyDataStopsUpgradeWithoutChangingRecords(String kind) {
        jdbc.update("INSERT INTO category(id,name) VALUES(1,'Legacy'),(2,'Parent')");
        switch (kind) {
            case "root" -> jdbc.update("INSERT INTO category(id,name) VALUES(3,' LEGACY ')");
            case "child" -> jdbc.update("INSERT INTO category(id,name,parent_id) VALUES(3,'Sữa Tươi',2),(4,'SỮA TƯƠI',2)");
            case "blank" -> jdbc.update("INSERT INTO category(id,name) VALUES(3,'   ')");
            case "sku-space" -> productAndMapping(" W-OLD ");
            case "sku-markup" -> productAndMapping("<old>");
            default -> throw new IllegalArgumentException(kind);
        }
        var categories = jdbc.queryForList("SELECT * FROM category ORDER BY id");
        var mapping = jdbc.queryForList("SELECT * FROM channel_product");
        assertThatThrownBy(() -> migrate("20")).hasStackTraceContaining("Issue 430:");
        assertThat(jdbc.queryForList("SELECT * FROM category ORDER BY id")).isEqualTo(categories);
        assertThat(jdbc.queryForList("SELECT * FROM channel_product")).isEqualTo(mapping);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='20'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname='uq_category_parent_name_key'",
                Integer.class, schema)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void databaseConstraintArbitratesConcurrentWritersWithoutApplicationLock(boolean root) throws Exception {
        jdbc.update("INSERT INTO category(id,name) VALUES(1,'Parent')");
        migrate("20");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = List.of(10, 11).stream().map(id -> workers.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    jdbc.update("INSERT INTO category(id,name,parent_id) VALUES(?,?,?)", id,
                            id == 10 ? "Sữa Tươi" : " SỮA TƯƠI ", root ? null : 1L);
                    return "CREATED";
                } catch (DataIntegrityViolationException ex) {
                    assertThat(ex).hasStackTraceContaining("uq_category_parent_name_key");
                    return "CONFLICT";
                }
            })).toList();
            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(2);
    }

    private void productAndMapping(String sku) {
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES(1,'GM1','Product','box',1,100,50)");
        jdbc.update("INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,1,? FROM sales_channel WHERE code='WEB'", sku);
    }
}
