package vn.greenmart.integration.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RabbitEventPublisher implements EventPublisher {
    private final ObjectProvider<RabbitTemplate> templates;

    public RabbitEventPublisher(ObjectProvider<RabbitTemplate> templates) {
        this.templates = templates;
    }

    @Override
    public void publish(long eventId, String eventType, String payload, String traceId) throws Exception {
        RabbitTemplate template = templates.getIfAvailable();
        if (template == null) throw new IllegalStateException("RabbitMQ is not configured");
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(Long.toString(eventId));
        properties.setType(eventType);
        if (traceId != null) properties.setHeader("X-Correlation-ID", traceId);
        CorrelationData confirmation = new CorrelationData(Long.toString(eventId));
        template.send("greenmart.events", eventType,
                new Message(payload.getBytes(StandardCharsets.UTF_8), properties), confirmation);
        var result = confirmation.getFuture().get(5, TimeUnit.SECONDS);
        if (!result.isAck() || confirmation.getReturned() != null) {
            throw new IllegalStateException("Event was not confirmed and routed");
        }
    }
}
