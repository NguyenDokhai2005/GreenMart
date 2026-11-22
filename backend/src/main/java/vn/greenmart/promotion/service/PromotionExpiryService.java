package vn.greenmart.promotion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PromotionExpiryService {
    private final JdbcTemplate jdbc;
    private final AuditLogRepository audit;
    private final Clock clock;

    @Transactional
    public int endExpiredPromotions() {
        var now = clock.instant();
        var ended = jdbc.query("""
                WITH due AS (
                    SELECT id,status FROM promotion
                    WHERE status IN ('DRAFT','ACTIVE','PAUSED') AND end_at<=?
                    ORDER BY id FOR NO KEY UPDATE SKIP LOCKED
                )
                UPDATE promotion p SET status='ENDED' FROM due
                WHERE p.id=due.id RETURNING p.id,due.status AS previous_status
                """, (rs, row) -> {
            AuditLog log = new AuditLog();
            log.setAction("CHANGE_PROMOTION_STATUS");
            log.setEntity("promotion");
            log.setEntityId(Long.toString(rs.getLong("id")));
            log.setDetail(Map.of("from", rs.getString("previous_status"), "to", "ENDED", "reason", "EXPIRED"));
            log.setCreatedAt(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            return log;
        }, Timestamp.from(now));
        // System action has no fabricated user. Audit failure rolls back the status transition.
        audit.saveAll(ended);
        return ended.size();
    }
}
