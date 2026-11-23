package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;

@Service
@RequiredArgsConstructor
public class PromotionService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordUsage(Long promotionId) {
        return recordUsage(promotionId, 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordUsage(Long promotionId, int smallestAppliedLineQuantity) {
        if (promotionId == null) {
            return true;
        }
        // Read the clock after taking the row lock: a checkout waiting across endAt must fail.
        if (jdbcTemplate.queryForList("SELECT id FROM promotion WHERE id=? FOR NO KEY UPDATE", Long.class, promotionId).isEmpty()) {
            return false;
        }
        Timestamp now = Timestamp.from(clock.instant());
        int rows = jdbcTemplate.update("""
                UPDATE promotion SET used_count = used_count + 1 WHERE id = ?
                AND status='ACTIVE' AND start_at<=? AND end_at>?
                AND (level='ORDER' OR COALESCE(min_quantity,1)<=?)
                AND (usage_limit IS NULL OR used_count < usage_limit)
                """, promotionId, now, now, smallestAppliedLineQuantity);
        return rows > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseUsage(Long promotionId) {
        if (promotionId == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE promotion SET used_count = GREATEST(used_count - 1, 0) WHERE id = ?",
                promotionId
        );
    }
}
