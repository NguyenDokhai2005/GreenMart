package vn.greenmart.integration.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
    @Bean
    Declarables greenmartTopology() {
        var events = ExchangeBuilder.topicExchange("greenmart.events").durable(true).build();
        var dead = ExchangeBuilder.topicExchange("greenmart.dlx").durable(true).build();
        var queue = QueueBuilder.durable("greenmart.integration.events")
                .deadLetterExchange("greenmart.dlx").deadLetterRoutingKey("integration.failed").build();
        var dlq = QueueBuilder.durable("greenmart.integration.dlq").build();
        return new Declarables(events, dead, queue, dlq,
                BindingBuilder.bind(queue).to(events).with("order.#").noargs(),
                BindingBuilder.bind(queue).to(events).with("stock.#").noargs(),
                BindingBuilder.bind(queue).to(events).with("forecast.completed").noargs(),
                BindingBuilder.bind(dlq).to(dead).with("integration.failed").noargs());
    }
}
