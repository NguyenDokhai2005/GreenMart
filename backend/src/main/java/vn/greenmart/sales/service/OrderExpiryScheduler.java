package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "greenmart.order.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

    private final OnlineOrderService onlineOrderService;

    @Scheduled(
            fixedDelayString = "${greenmart.order.expiry.poll-ms:15000}",
            initialDelayString = "${greenmart.order.expiry.initial-delay-ms:15000}",
            scheduler = "taskScheduler"
    )
    public void runExpirySweep() {
        try {
            int count = onlineOrderService.expireOverdueOrders();
            if (count > 0) {
                log.info("Đã giải phóng giữ chỗ cho {} đơn online quá hạn", count);
            }
        } catch (Exception ex) {
            log.error("Lỗi khi chạy job hết hạn giữ chỗ đơn online: {}", ex.getMessage(), ex);
        }
    }
}
