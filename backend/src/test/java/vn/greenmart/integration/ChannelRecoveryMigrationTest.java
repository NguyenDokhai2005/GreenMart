package vn.greenmart.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ChannelRecoveryMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @Test void upgradeFromReviewedV22PreservesExistingJobsAndTheirAttempts() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .placeholders(java.util.Map.of("forecast_user","forecast_user"))
                .target("22").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        int checksum=jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='22'",Integer.class);
        long failed=fixture(jdbc,"old-failed","FAILED",6);
        long pending=fixture(jdbc,"old-pending","RETRYING",2);
        long sent=fixture(jdbc,"old-success","SUCCESS",1);

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .placeholders(java.util.Map.of("forecast_user","forecast_user"))
                .load().migrate();
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='22'",Integer.class)).isEqualTo(checksum);
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='23'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForMap("SELECT status,attempts FROM sync_log WHERE id=?",failed))
                .containsEntry("status","FAILED").containsEntry("attempts",6);
        assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log WHERE id=?",Integer.class,pending)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempts FROM sync_log WHERE id=?",Integer.class,sent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cycle_attempts FROM channel_delivery WHERE sync_log_id=?",Integer.class,failed)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT cycle_attempts FROM channel_delivery WHERE sync_log_id=?",Integer.class,pending)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT cycle_attempts FROM channel_delivery WHERE sync_log_id=?",Integer.class,sent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_delivery WHERE recovery_count=0",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT body->>'status' FROM channel_delivery WHERE sync_log_id=?",String.class,failed)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL FROM channel_delivery WHERE sync_log_id=?",Boolean.class,failed)).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NOT NULL FROM channel_delivery WHERE sync_log_id=?",Boolean.class,pending)).isTrue();
    }

    private long fixture(JdbcTemplate jdbc,String event,String status,int attempts) {
        long log=jdbc.queryForObject("""
                INSERT INTO sync_log(channel_id,direction,external_id,status,attempts,error)
                VALUES ((SELECT id FROM sales_channel WHERE code='WEB'),'OUT',?,?,?,'HTTP 503') RETURNING id
                """,Long.class,event,status,attempts);
        jdbc.update("""
                INSERT INTO channel_delivery(event_id,sync_log_id,routing_key,channel_code,external_id,body,trace_id,next_attempt_at)
                VALUES (?,?,'channel.order.status','WEB',?,'{"status":"PAID"}'::jsonb,'migration-test',
                        CASE WHEN ?='RETRYING' THEN clock_timestamp() ELSE NULL END)
                """,event,log,event,status);
        return log;
    }
}
