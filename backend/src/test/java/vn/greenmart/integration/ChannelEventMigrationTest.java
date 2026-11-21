package vn.greenmart.integration;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import static org.assertj.core.api.Assertions.*;

/** Upgrade checks only use this disposable PostgreSQL container, never a configured application DB. */
@Testcontainers
class ChannelEventMigrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    private JdbcTemplate jdbc;
    private Flyway flyway(String target) {
        var configuration=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
                .placeholders(Map.of("forecast_user","forecast_ro")).cleanDisabled(false);
        if(target!=null) configuration.target(target);
        return configuration.load();
    }
    @BeforeEach void historicalDatabase() {
        var old=flyway("18"); old.clean(); old.migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(416,'B416','Migration')");
    }
    private long order(String code) {
        return jdbc.queryForObject("""
                INSERT INTO sales_order(code,channel_id,external_order_id,branch_id,status,subtotal,total)
                SELECT ?,id,?,416,'COMPLETED',100,100 FROM sales_channel WHERE code='WEB' RETURNING id
                """,Long.class,code,code);
    }
    private void paid(long order,String ref) {
        jdbc.update("INSERT INTO payment(order_id,method,amount,status,provider_ref) VALUES(?,'QR',100,'PAID',?)",order,ref);
    }
    @Test void backfillsHistoricalPaymentsAndRetainsBindingAfterRefund() {
        long a=order("A"),b=order("B"); paid(a,"HISTORICAL");
        flyway(null).migrate();
        assertThat(jdbc.queryForObject("SELECT order_id FROM channel_payment_reference WHERE provider_ref='HISTORICAL'",Long.class)).isEqualTo(a);
        jdbc.update("UPDATE payment SET status='REFUNDED' WHERE order_id=?",a);
        assertThatThrownBy(()->paid(b,"HISTORICAL")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment",Integer.class)).isEqualTo(1);
    }
    @Test void refusesHistoricalReferenceAssignedToTwoOrdersWithoutDeletingOrRenamingPayments() {
        paid(order("A"),"SHARED"); paid(order("B"),"SHARED");
        assertThatThrownBy(()->flyway(null).migrate()).isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Ambiguous historical online payment references");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE provider_ref='SHARED'",Integer.class)).isEqualTo(2);
    }
    @Test void duplicateRowsOnTheSameHistoricalOrderDoNotInventAnotherReference() {
        long a=order("A"); paid(a,"SAME-ORDER"); paid(a,"SAME-ORDER");
        flyway(null).migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_payment_reference",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment",Integer.class)).isEqualTo(2);
    }
}
