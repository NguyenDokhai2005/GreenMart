package vn.greenmart.inventory.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class StockExportMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    private String schema;
    private JdbcTemplate jdbc;
    private void migrate(String version) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target(version)
                .placeholders(Map.of("forecast_user","forecast_ro")).load().migrate();
    }
    @BeforeEach void setup() {
        schema="export_"+UUID.randomUUID().toString().replace("-","");
        migrate("6");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                +(POSTGRES.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema,
                POSTGRES.getUsername(),POSTGRES.getPassword()));
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(1,'B1','Branch')");
        jdbc.update("INSERT INTO category(id,name) VALUES(1,'Milk')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES(1,'MILK','Milk','hop',1,20000,10000)");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(1,'maker','hash','Maker','STOREKEEPER'),(2,'manager','hash','Manager','BRANCH_MANAGER')");
    }
    private void document(long id,String type,String reason) {
        jdbc.update("INSERT INTO stock_document(id,code,type,branch_id,status,created_by,reason) VALUES(?,?,?,1,'DRAFT',1,?)",
                id,"STOCK"+id,type,reason);
    }
    @Test void upgradesLegacyExportsWithoutInventingHistoricalCostOrChangingNotes() {
        document(1,"EXPORT","EXPIRED");
        document(2,"EXPORT","Existing full note");
        document(3,"EXPORT",null);
        document(4,"IMPORT","Import note");
        jdbc.update("INSERT INTO stock_document_item(document_id,product_id,quantity) VALUES(2,1,3)");
        jdbc.update("UPDATE stock_document SET status='APPROVED',approved_by=2,approved_at=CURRENT_TIMESTAMP WHERE id=2");
        migrate("7"); migrate("7");
        assertThat(jdbc.queryForList("SELECT export_reason FROM stock_document ORDER BY id",String.class))
                .containsExactly("EXPIRED","OTHER","OTHER",null);
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_document WHERE id=2",String.class)).isEqualTo("Existing full note");
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_document WHERE id=3",String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_document WHERE id=4",String.class)).isEqualTo("Import note");
        assertThat(jdbc.queryForMap("SELECT export_cost_price FROM stock_document_item WHERE document_id=2"))
                .containsEntry("export_cost_price",null);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=1",Long.class)).isEqualTo(10000L);
    }
    @Test void constraintsRejectMissingInvalidOrMisplacedReasonsAndNegativeSnapshot() {
        document(1,"IMPORT","Import note");
        migrate("7");
        assertThatThrownBy(()->document(2,"EXPORT",null)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE stock_document SET export_reason='LOST' WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO stock_document(id,code,type,branch_id,created_by,export_reason,reason) VALUES(3,'E3','EXPORT',1,1,'OTHER','Explanation')");
        assertThatThrownBy(()->jdbc.update("UPDATE stock_document SET reason=' ' WHERE id=3"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE stock_document SET export_reason='UNKNOWN' WHERE id=3"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO stock_document_item(document_id,product_id,quantity,export_cost_price) VALUES(3,1,1,-1)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
