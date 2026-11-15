package vn.greenmart.sales.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ChannelOrderStore {
    private final JdbcTemplate jdbc;

    public record Stored(long id, String code, long branchId, String externalOrderId, String status,
                         OffsetDateTime reservedUntil) {}
    public record Line(long productId, String sku, int quantity, long unitPrice, long total) {}

    public Optional<Stored> lockAndFind(long channelId, String externalId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "channel-order:" + channelId + ":" + externalId);
        return jdbc.query("""
                SELECT id,code,branch_id,external_order_id,status,reserved_until
                FROM sales_order WHERE channel_id=? AND external_order_id=?
                """, (rs, row) -> new Stored(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)),
                channelId, externalId).stream().findFirst();
    }

    public Stored insert(long channelId, String channel, String externalId, long branch, Long customer,
                         String status, OffsetDateTime reservedUntil, Instant orderedAt, long total, List<Line> lines) {
        long id = jdbc.queryForObject("SELECT nextval('sales_order_id_seq')", Long.class);
        String code = channel + "-" + id;
        jdbc.update("""
                INSERT INTO sales_order(id,code,channel_id,external_order_id,branch_id,customer_id,status,
                                        subtotal,total,reserved_until,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, code, channelId, externalId, branch, customer, status, total, total, reservedUntil,
                java.sql.Timestamp.from(orderedAt));
        for (Line line : lines) {
            jdbc.update("""
                    INSERT INTO order_item(order_id,product_id,quantity,unit_price,line_total) VALUES (?,?,?,?,?)
                    """, id, line.productId(), line.quantity(), line.unitPrice(), line.total());
        }
        return new Stored(id, code, branch, externalId, status, reservedUntil);
    }
}
