package vn.greenmart.integration.delivery;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@Service
public class SyncLogService {
    private final JdbcTemplate jdbc;
    public SyncLogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Entry(long id, String channelCode, String direction, String externalId,
                        String status, int attempts, String error, OffsetDateTime createdAt) {}
    public record Page(List<Entry> content, int page, int size, long totalElements) {}

    @Transactional(readOnly = true)
    public Page list(String channel, String status, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100
                || (channel != null && !List.of("WEB", "MARKET").contains(channel))
                || (status != null && !List.of("SUCCESS", "FAILED", "RETRYING").contains(status))) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        String where = " FROM sync_log l JOIN sales_channel c ON c.id=l.channel_id "
                + "WHERE (?::text IS NULL OR c.code=?) AND (?::text IS NULL OR l.status=?)";
        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, channel, channel, status, status);
        var rows = jdbc.query("SELECT l.*,c.code AS channel_code" + where
                + " ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?", (rs, n) -> new Entry(
                rs.getLong("id"), rs.getString("channel_code"), rs.getString("direction"),
                rs.getString("external_id"), rs.getString("status"), rs.getInt("attempts"),
                rs.getString("error"), rs.getObject("created_at", OffsetDateTime.class)),
                channel, channel, status, status, size, (long) page * size);
        return new Page(rows, page, size, total);
    }

    @Transactional
    public void retry(long id) {
        if (id <= 0) throw new AppException(ErrorCode.VALIDATION_ERROR);
        // Same lock order as the delivery worker. Concurrent retries queue a single delivery.
        var deliveries = jdbc.queryForList("SELECT event_id FROM channel_delivery WHERE sync_log_id=? FOR UPDATE", id);
        if (deliveries.isEmpty()) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM sync_log WHERE id=?)", Boolean.class, id));
            throw new AppException(exists ? ErrorCode.INVALID_STATUS : ErrorCode.NOT_FOUND);
        }
        int changed = jdbc.update("UPDATE sync_log SET status='RETRYING',error=NULL WHERE id=? AND status='FAILED'", id);
        if (changed == 0) throw new AppException(ErrorCode.INVALID_STATUS);
        // Manual retry starts a fresh finite cycle but does not erase history or replenish auto recovery.
        jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp(),cycle_attempts=0 WHERE sync_log_id=?", id);
    }
}
