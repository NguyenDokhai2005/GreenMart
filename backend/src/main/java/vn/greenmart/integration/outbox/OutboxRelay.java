package vn.greenmart.integration.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;

    public OutboxRelay(JdbcTemplate jdbc, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    /** One locked row per transaction; multiple application instances skip each other's rows. */
    @Transactional
    public boolean publishNext() {
        var rows = jdbc.queryForList("""
                SELECT id, event_type, payload::text AS payload, trace_id
                FROM outbox_event WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        long id = ((Number) row.get("id")).longValue();
        String previous = MDC.get("traceId");
        String traceId = (String) row.get("trace_id");
        try {
            if (traceId == null) MDC.remove("traceId"); else MDC.put("traceId", traceId);
            publisher.publish(id, (String) row.get("event_type"), (String) row.get("payload"), traceId);
            jdbc.update("UPDATE outbox_event SET status='SENT', attempts=attempts+1 WHERE id=?", id);
            log.info("Outbox event sent eventId={}", id);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            jdbc.update("""
                    UPDATE outbox_event SET attempts=attempts+1,
                      next_attempt_at=now() + LEAST(60, power(2, LEAST(attempts, 6))) * interval '1 second'
                    WHERE id=?
                    """, id);
            // Do not log payloads, credentials or broker URLs.
            log.warn("Outbox event pending eventId={} failure={}", id, ex.getClass().getSimpleName());
        } finally {
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
        return true;
    }
}
