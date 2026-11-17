package vn.greenmart.inventory.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ReplenishmentSchedulerTest {
    @Test
    void registersTheReplenishmentJobDirectlyWithTheScheduler() {
        var scheduler = mock(TaskScheduler.class);
        var service = mock(InventoryReplenishmentService.class);
        var job = new ReplenishmentScheduler(service, scheduler, "0 0 * * * *");
        var task = ArgumentCaptor.forClass(Runnable.class);

        job.start();
        verify(scheduler).schedule(task.capture(), any(CronTrigger.class));
        task.getValue().run();
        verify(service).refresh(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @Test
    void respectsTheDisabledCronValue() {
        var scheduler = mock(TaskScheduler.class);
        var job = new ReplenishmentScheduler(mock(InventoryReplenishmentService.class), scheduler, "-");

        job.start();
        verifyNoInteractions(scheduler);
    }
}
