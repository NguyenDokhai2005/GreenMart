package vn.greenmart.promotion.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

@Component
public class PromotionExpiryScheduler {
    private final PromotionExpiryService expiry;
    private final TaskScheduler scheduler;
    private final String cron;
    private ScheduledFuture<?> scheduled;

    public PromotionExpiryScheduler(PromotionExpiryService expiry,
            @Qualifier("taskScheduler") TaskScheduler scheduler,
            @Value("${greenmart.promotion.expiry.cron:0 * * * * *}") String cron) {
        this.expiry = expiry;
        this.scheduler = scheduler;
        this.cron = cron;
    }

    @PostConstruct
    void start() {
        if (!ScheduledTaskRegistrar.CRON_DISABLED.equals(cron)) {
            scheduled = scheduler.schedule(() -> expiry.endExpiredPromotions(), new CronTrigger(cron, ZoneOffset.UTC));
        }
    }

    @PreDestroy
    void stop() {
        if (scheduled != null) scheduled.cancel(false);
    }
}
