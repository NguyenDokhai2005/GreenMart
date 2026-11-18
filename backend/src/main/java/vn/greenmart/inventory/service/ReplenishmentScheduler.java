package vn.greenmart.inventory.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;

@Component
public class ReplenishmentScheduler {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final InventoryReplenishmentService replenishment;
    private final TaskScheduler scheduler;
    private final String cron;
    private ScheduledFuture<?> scheduled;

    public ReplenishmentScheduler(InventoryReplenishmentService replenishment,
            @Qualifier("taskScheduler") TaskScheduler scheduler,
            @Value("${greenmart.inventory.replenishment.cron:0 0 * * * *}") String cron) {
        this.replenishment = replenishment;
        this.scheduler = scheduler;
        this.cron = cron;
    }

    @PostConstruct
    void start() {
        if (!ScheduledTaskRegistrar.CRON_DISABLED.equals(cron)) {
            scheduled = scheduler.schedule(() -> replenishment.refresh(LocalDate.now(BUSINESS_ZONE)),
                    new CronTrigger(cron, BUSINESS_ZONE));
        }
    }

    @PreDestroy
    void stop() {
        if (scheduled != null) scheduled.cancel(false);
    }
}
