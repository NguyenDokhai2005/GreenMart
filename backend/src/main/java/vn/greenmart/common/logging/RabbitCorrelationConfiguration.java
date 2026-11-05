package vn.greenmart.common.logging;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;

@Configuration
public class RabbitCorrelationConfiguration {
    @Bean RabbitTemplateCustomizer correlationHeaders() {
        return template -> template.addBeforePublishPostProcessors(message -> {
            var properties = message.getMessageProperties();
            Object stored = properties.getHeaders().get(TraceContext.HEADER);
            String traceId = TraceContext.normalize(stored == null ? MDC.get("traceId") : stored);
            properties.setHeader(TraceContext.HEADER, traceId);
            return message;
        });
    }

    @Bean static BeanPostProcessor correlationListeners() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof SimpleRabbitListenerContainerFactory factory) {
                    factory.setContainerCustomizer(container -> {
                        Object delegate = container.getMessageListener();
                        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                            String previous = MDC.get("traceId");
                            MDC.put("traceId", TraceContext.normalize(message.getMessageProperties().getHeaders().get(TraceContext.HEADER)));
                            try {
                                LoggerFactory.getLogger("vn.greenmart.messaging").info("RabbitMQ delivery received");
                                if (delegate instanceof ChannelAwareMessageListener aware) aware.onMessage(message, channel);
                                else if (delegate instanceof MessageListener listener) listener.onMessage(message);
                                else throw new IllegalStateException("Unsupported message listener");
                            } finally {
                                if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
                            }
                        });
                    });
                }
                return bean;
            }
        };
    }
}
