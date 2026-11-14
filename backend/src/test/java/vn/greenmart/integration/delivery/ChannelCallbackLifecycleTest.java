package vn.greenmart.integration.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.integration.outbox.OutboxService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChannelCallbackLifecycleTest {
    @Test
    void contextClosesTheCallbackClientAndSchedulers() {
        HttpClient client;
        ThreadPoolTaskScheduler callbackScheduler;
        ThreadPoolTaskScheduler defaultScheduler;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(ChannelSignatures.class, () -> mock(ChannelSignatures.class));
            context.registerBean(OutboxService.class, () -> mock(OutboxService.class));
            context.register(ChannelDelivery.class);
            context.refresh();
            client = context.getBean(HttpClient.class);
            callbackScheduler = context.getBean("channelDeliveryScheduler", ThreadPoolTaskScheduler.class);
            defaultScheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(client.isTerminated()).isFalse();
        }
        assertThat(client.isTerminated()).isTrue();
        assertThat(callbackScheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(defaultScheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }
}
