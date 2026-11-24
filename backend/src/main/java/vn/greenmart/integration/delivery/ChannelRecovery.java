package vn.greenmart.integration.delivery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.greenmart.integration.channel.ChannelSignatures;

@Service
public class ChannelRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelRecovery.class);
    private final JdbcTemplate jdbc;
    private final HttpClient http;
    private final ChannelSignatures signatures;
    private final URI base;
    private final boolean enabled;
    private final TransactionTemplate tx;

    public ChannelRecovery(JdbcTemplate jdbc, HttpClient channelCallbackHttpClient, ChannelSignatures signatures,
            PlatformTransactionManager transactions,
            @Value("${MOCK_CONNECTOR_URL:http://mock-connector:8090}") URI base,
            @Value("${greenmart.channel.recovery.enabled:true}") boolean enabled,
            @Value("${greenmart.outbox.enabled:true}") boolean workers) {
        this.jdbc=jdbc; this.http=channelCallbackHttpClient; this.signatures=signatures;
        this.base=base; this.enabled=enabled && workers; this.tx=new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelay=30000, scheduler="channelRecoveryScheduler")
    public void poll() {
        if (!enabled) return;
        for (String channel : List.of("WEB","MARKET")) recover(channel);
    }

    public int recover(String channel) {
        return tx.execute(ignored -> {
            // Persist the probe gate, so multiple backend instances cannot probe one channel together.
            var gate=jdbc.queryForList("""
                    SELECT channel_code FROM channel_recovery
                    WHERE channel_code=? AND next_probe_at<=clock_timestamp() FOR UPDATE SKIP LOCKED
                    """, String.class, channel);
            if (gate.isEmpty()) return 0;
            jdbc.update("UPDATE channel_recovery SET next_probe_at=clock_timestamp()+interval '30 seconds' WHERE channel_code=?",channel);
            warnOverdue(channel);
            var jobs=jdbc.queryForList("""
                    SELECT d.sync_log_id FROM channel_delivery d JOIN sync_log l ON l.id=d.sync_log_id
                    WHERE d.channel_code=? AND l.status='FAILED'
                      AND (l.error='Kết nối callback thất bại' OR l.error ~ '^HTTP (5[0-9]{2}|408|429)$')
                    ORDER BY d.sync_log_id LIMIT 20 FOR UPDATE OF d SKIP LOCKED
                    """, Long.class, channel);
            if (jobs.isEmpty() || !healthy(channel)) return 0;
            int changed=0;
            for (long id : jobs) {
                if (jdbc.update("UPDATE sync_log SET status='RETRYING',attempts=0,error=NULL WHERE id=? AND status='FAILED'",id)==1) {
                    jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp() WHERE sync_log_id=?",id);
                    changed++;
                }
            }
            return changed;
        });
    }

    private boolean healthy(String channel) {
        byte[] body="{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        try {
            var request=HttpRequest.newBuilder(base.resolve("/mock/"+channel+"/stock"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                    .header("X-Signature",signatures.sign(channel,body))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            return http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==204;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.io.IOException | IllegalStateException ex) {
            return false;
        }
    }

    private void warnOverdue(String channel) {
        var ids=jdbc.queryForList("""
                UPDATE channel_delivery d SET last_recovery_warning_at=clock_timestamp()
                FROM sync_log l WHERE l.id=d.sync_log_id AND d.channel_code=? AND l.status='FAILED'
                  AND d.first_failed_at<clock_timestamp()-interval '24 hours'
                  AND (d.last_recovery_warning_at IS NULL OR d.last_recovery_warning_at<clock_timestamp()-interval '24 hours')
                RETURNING d.event_id
                """,String.class,channel);
        for (String id : ids) LOG.warn("Callback FAILED over 24h: channel={}, eventId={}",channel,id);
    }
}
