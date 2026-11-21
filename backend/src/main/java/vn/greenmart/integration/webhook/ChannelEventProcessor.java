package vn.greenmart.integration.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.integration.channel.ChannelCatalog;
import vn.greenmart.integration.channel.ChannelEventSignatures.Verified;
import vn.greenmart.sales.dto.ChannelOrderEventRequest;
import vn.greenmart.sales.service.OnlineOrderService;

@Service
@RequiredArgsConstructor
public class ChannelEventProcessor {
    private final JdbcTemplate jdbc;
    private final ChannelCatalog channels;
    private final OnlineOrderService orders;
    private final ObjectMapper json;
    private record Receipt(String hash, String response) {}

    @Transactional
    public ChannelOrderIntake.Ack receive(String channel, String externalId,
            ChannelOrderEventRequest event, Verified verified) {
        long channelId = channels.findChannel(channel).id();
        // Two-key namespace stays separate from the order store's 64-bit advisory locks.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(416,hashtext(?))",
                channelId + ":" + verified.eventId());
        var previous = jdbc.query("""
                SELECT request_hash,response::text FROM channel_event_receipt
                WHERE channel_id=? AND event_id=CAST(? AS uuid)
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)), channelId, verified.eventId().toString());
        if (!previous.isEmpty()) {
            var receipt = previous.getFirst();
            if (!receipt.hash().equals(verified.requestHash())) {
                throw new AppException(ErrorCode.CONFLICT, "Mã sự kiện đã được dùng với đơn hoặc nội dung khác");
            }
            try {
                return json.readValue(receipt.response(), ChannelOrderIntake.Ack.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Không đọc được kết quả sự kiện đã lưu", ex);
            }
        }
        var ack = orders.handleChannelEvent(channel, externalId, event);
        try {
            // Insert the FK only after the order is locked, avoiding KEY SHARE -> UPDATE upgrade races.
            jdbc.update("""
                    INSERT INTO channel_event_receipt(channel_id,event_id,order_id,request_hash,response)
                    VALUES (?,CAST(? AS uuid),?,?,CAST(? AS jsonb))
                    """, channelId, verified.eventId().toString(), ack.orderId(), verified.requestHash(), json.writeValueAsString(ack));
            jdbc.update("""
                    INSERT INTO audit_log(action,entity,entity_id,detail)
                    VALUES ('CHANNEL_EVENT_RECEIVED','order',?,CAST(? AS jsonb))
                    """, Long.toString(ack.orderId()), json.writeValueAsString(Map.of(
                    "eventId", verified.eventId().toString(), "type", event.type().name(), "status", ack.status())));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không lưu được kết quả sự kiện", ex);
        }
        return ack;
    }
}
