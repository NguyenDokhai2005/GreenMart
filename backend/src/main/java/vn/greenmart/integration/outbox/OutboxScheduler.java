package vn.greenmart.integration.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "greenmart.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private final OutboxRelay relay;
    public OutboxScheduler(OutboxRelay relay) { this.relay = relay; }

    @Scheduled(fixedDelayString = "${greenmart.outbox.poll-ms:1000}", initialDelayString = "${greenmart.outbox.initial-delay-ms:10000}")
    public void poll() {
        for (int i = 0; i < 25 && !Thread.currentThread().isInterrupted(); i++) {
            if (!relay.publishNext()) break;
        }
    }
}
