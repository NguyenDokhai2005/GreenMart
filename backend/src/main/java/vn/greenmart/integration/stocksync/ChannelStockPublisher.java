package vn.greenmart.integration.stocksync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.TreeSet;
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
public class ChannelStockPublisher {
    private static final String QUEUE = "greenmart.channel.stock";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OutboxService outbox;

    @Bean
    Declarables stockSyncTopology() {
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange("greenmart.dlx")
                .deadLetterRoutingKey("integration.failed").build();
        return new Declarables(queue, new Binding(QUEUE, Binding.DestinationType.QUEUE,
                "greenmart.events", "stock.changed", Map.of()));
    }

    record Mapping(long channel, long product, String code, String sku) {}

    @RabbitListener(queues = QUEUE, autoStartup = "${greenmart.outbox.enabled:true}")
    @Transactional
    public void onStockChanged(Message message) throws IOException {
        var event = json.readTree(message.getBody());
        String source = message.getMessageProperties().getMessageId();
        long branch = event.path("branchId").asLong();
        var products = new TreeSet<Long>();
        if (event.hasNonNull("productId")) products.add(event.path("productId").asLong());
        for (var product : event.path("productIds")) products.add(product.asLong());
        if (branch <= 0 || source == null || products.isEmpty() || products.first() <= 0)
            throw new AmqpRejectAndDontRequeueException("Invalid stock.changed event");

        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "channel-stock-event:" + source);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM outbox_event WHERE event_type='channel.stock'
                AND payload->>'sourceEventId'=?)
                """, Boolean.class, source))) return;

        for (long product : products) {
            var mappings = jdbc.query("""
                    SELECT c.id, cp.product_id, c.code, cp.external_sku
                    FROM channel_product cp JOIN sales_channel c ON c.id=cp.channel_id
                    JOIN channel_branch cb ON cb.channel_id=c.id
                    WHERE cb.branch_id=? AND cp.product_id=? AND c.code IN ('WEB','MARKET') ORDER BY c.id
                    """, (rs, row) -> new Mapping(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                    branch, product);
            for (var m : mappings) {
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                        "channel-stock:" + m.channel() + ":" + m.product());
                long available = jdbc.queryForObject("""
                        SELECT GREATEST(COALESCE(SUM(i.on_hand-i.reserved),0)-c.safety_stock,0)
                        FROM sales_channel c JOIN channel_branch cb ON cb.channel_id=c.id
                        LEFT JOIN inventory i ON i.branch_id=cb.branch_id AND i.product_id=?
                        WHERE c.id=? GROUP BY c.safety_stock
                        """, Long.class, m.product(), m.channel());
                long version = jdbc.queryForObject("SELECT nextval('channel_stock_version_seq')", Long.class);
                outbox.enqueue("channel", Long.toString(m.channel()), "channel.stock", Map.of(
                        "channelCode", m.code(), "sourceEventId", source, "body", Map.of("items", java.util.List.of(
                        Map.of("externalSku", m.sku(), "available", available, "version", version)))));
            }
        }
    }
}
