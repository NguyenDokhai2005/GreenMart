package vn.greenmart.promotion;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PromotionQuantityMigrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    private static JdbcTemplate jdbc;

    @BeforeAll static void upgrade() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("19").placeholders(Map.of("forecast_user","forecast_ro")).load().migrate();
        jdbc.update("INSERT INTO branch(id,code,name,address) VALUES(1,'GM425','Branch','A')");
        jdbc.update("INSERT INTO category(id,name) VALUES(1,'Legacy')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES(1,'GM1','Product','box',1,10000,5000)");
        jdbc.update("""
                INSERT INTO promotion(id,code,type,level,value,start_at,end_at,status,used_count)
                VALUES(1,'LEGACY-ITEM','AMOUNT','ITEM',1000,'2025-01-01','2025-02-01','ENDED',7),
                      (2,'LEGACY-ORDER','AMOUNT','ORDER',500,'2025-01-01','2025-02-01','ENDED',3)
                """);
        jdbc.update("""
                INSERT INTO sales_order(id,code,channel_id,branch_id,status,subtotal,discount,total)
                SELECT 1,'LEGACY425',id,1,'COMPLETED',29000,0,29000 FROM sales_channel WHERE code='POS'
                """);
        // Legacy policy discounted 1000 for the entire 3-unit line; this snapshot must never become 3000.
        jdbc.update("INSERT INTO order_item(order_id,product_id,quantity,unit_price,discount,line_total,promotion_id) VALUES(1,1,3,10000,1000,29000,1)");
        jdbc.update("INSERT INTO payment(order_id,method,amount,status) VALUES(1,'CASH',29000,'PAID')");
        var order = jdbc.queryForList("SELECT * FROM sales_order");
        var lines = jdbc.queryForList("SELECT * FROM order_item");
        var payment = jdbc.queryForList("SELECT * FROM payment");
        var counters = jdbc.queryForList("SELECT id,value,status,used_count FROM promotion ORDER BY id");
        Flyway.configure().dataSource(ds).target("21").placeholders(Map.of("forecast_user","forecast_ro")).load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM sales_order")).isEqualTo(order);
        assertThat(jdbc.queryForList("SELECT * FROM order_item")).isEqualTo(lines);
        assertThat(jdbc.queryForList("SELECT * FROM payment")).isEqualTo(payment);
        assertThat(jdbc.queryForList("SELECT id,value,status,used_count FROM promotion ORDER BY id")).isEqualTo(counters);
    }

    @Test void legacyThresholdDefaultsAtReadWithoutRewritingHistory() {
        assertThat(jdbc.queryForObject("SELECT COALESCE(min_quantity,1) FROM promotion WHERE id=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT min_quantity FROM promotion WHERE id=2",Integer.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT discount FROM order_item",Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion WHERE id=1",Integer.class)).isEqualTo(7);
    }

    @Test void invalidThresholdsCannotBypassDatabaseConstraint() {
        assertThatThrownBy(() -> jdbc.update("UPDATE promotion SET min_quantity=0 WHERE id=1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE promotion SET min_quantity=1 WHERE id=2")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE promotion SET min_quantity=3 WHERE id=1");
        assertThat(jdbc.queryForObject("SELECT min_quantity FROM promotion WHERE id=1",Integer.class)).isEqualTo(3);
        jdbc.update("UPDATE promotion SET min_quantity=NULL WHERE id=1");
    }
}
