package vn.greenmart.promotion.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PromotionExpirySchedulerTest {
    @Test
    void schedulesMinuteTickDelegatesWorkAndCancelsOnShutdown() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        PromotionExpiryService expiry = mock(PromotionExpiryService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Trigger.class))).thenAnswer(inv -> future);
        var job = new PromotionExpiryScheduler(expiry, scheduler, "0 * * * * *");
        job.start();
        var task = ArgumentCaptor.forClass(Runnable.class);
        var trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).schedule(task.capture(), trigger.capture());
        assertThat(((CronTrigger) trigger.getValue()).getExpression()).isEqualTo("0 * * * * *");
        task.getValue().run();
        verify(expiry).endExpiredPromotions();
        job.stop();
        verify(future).cancel(false);
    }

    @Test
    void disabledCronStartsNoBackgroundWork() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        PromotionExpiryService expiry = mock(PromotionExpiryService.class);
        var job = new PromotionExpiryScheduler(expiry, scheduler, "-");
        job.start();
        job.stop();
        verifyNoInteractions(scheduler, expiry);
    }
}
