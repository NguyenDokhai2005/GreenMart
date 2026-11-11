package vn.greenmart.integration.orderstatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.integration.outbox.OutboxService;

@Configuration
@RequiredArgsConstructor
public class ChannelOrderStatusPublisher {
    private static final String QUEUE = "greenmart.channel.order-status";
    private static final java.util.List<String> STATES = java.util.List.of(
            "confirmed", "completed", "cancelled", "expired", "rejected");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OutboxService outbox;

    @Bean
    Declarables orderStatusTopology() {
        var declarations = new java.util.ArrayList<Declarable>();
        declarations.add(QueueBuilder.durable(QUEUE).deadLetterExchange("greenmart.dlx")
                .deadLetterRoutingKey("integration.failed").build());
        for (String state : STATES) declarations.add(new Binding(QUEUE, Binding.DestinationType.QUEUE,
                "greenmart.events", "order." + state, Map.of()));
        return new Declarables(declarations);
    }

    record Destination(String channel, String externalId) {}

    @RabbitListener(queues = QUEUE)
    @Transactional
    public void onOrderStatus(Message message) throws IOException {
        var props = message.getMessageProperties();
        String source = props.getMessageId();
        String routing = props.getReceivedRoutingKey();
        var event = json.readTree(message.getBody());
        long id = event.path("orderId").asLong();
        if (source == null || routing == null || !routing.startsWith("order.")
                || !STATES.contains(routing.substring(6)) || id <= 0)
            throw new AmqpRejectAndDontRequeueException("Invalid order status event");
        var destinations = jdbc.query("""
                SELECT c.code,o.external_order_id FROM sales_order o JOIN sales_channel c ON c.id=o.channel_id
                WHERE o.id=? AND o.external_order_id IS NOT NULL AND c.code IN ('WEB','MARKET')
                """, (rs,row) -> new Destination(rs.getString(1),rs.getString(2)), id);
        if (destinations.isEmpty()) return;
        var d = destinations.getFirst();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "channel-status-event:" + source);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM outbox_event WHERE event_type='channel.order.status'
                AND payload->>'sourceEventId'=?)
                """, Boolean.class, source))) return;
        var body = new LinkedHashMap<String,Object>();
        body.put("status", routing.substring(6).toUpperCase(Locale.ROOT));
        var timestamp = event.path("occurredAt").asText(event.path("completedAt").asText(null));
        if (timestamp == null) timestamp = jdbc.queryForObject("SELECT created_at FROM outbox_event WHERE id::text=?",
                (rs,row) -> rs.getObject(1, java.time.OffsetDateTime.class).toString(), source);
        body.put("occurredAt", timestamp);
        var reason = event.path("reason").asText(event.path("statusReason").asText(null));
        if (reason != null && !reason.isBlank()) body.put("reason", reason);
        outbox.enqueue("order", Long.toString(id), "channel.order.status",
                Map.of("channelCode", d.channel(), "externalId", d.externalId(), "body", body, "sourceEventId", source));
    }
}
