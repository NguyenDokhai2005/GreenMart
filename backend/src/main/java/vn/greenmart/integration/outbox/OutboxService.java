package vn.greenmart.integration.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Call inside the business transaction; rollback also removes the pending event. */
@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(String aggregateType, String aggregateId, String eventType, Object payload) {
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 40
                || aggregateId == null || aggregateId.isBlank() || aggregateId.length() > 60
                || eventType == null || !eventType.matches("[a-z][a-z0-9_.-]{0,59}")) {
            throw new IllegalArgumentException("Invalid outbox metadata");
        }
        try {
            return jdbc.queryForObject("""
                    INSERT INTO outbox_event(aggregate_type, aggregate_id, event_type, payload, trace_id)
                    VALUES (?, ?, ?, ?::jsonb, ?) RETURNING id
                    """, Long.class, aggregateType, aggregateId, eventType,
                    json.writeValueAsString(payload), MDC.get("traceId"));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Payload must be JSON serializable", ex);
        }
    }
}
