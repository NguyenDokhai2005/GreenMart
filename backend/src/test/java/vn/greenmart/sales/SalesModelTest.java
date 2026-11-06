package vn.greenmart.sales;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.greenmart.sales.domain.*;
import vn.greenmart.sales.dto.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SalesModelTest - Domain & DTO Coverage")
class SalesModelTest {

    @Test
    void testSalesEntities() {
        Instant now = Instant.now();

        SalesOrder order = new SalesOrder();
        order.setId(1L);
        order.setCode("POS-001");
        order.setChannelId(1L);
        order.setExternalOrderId("EXT-1");
        order.setBranchId(1L);
        order.setCustomerId(10L);
        order.setPromotionId(5L);
        order.setStatus(OrderStatus.COMPLETED);
        order.setSubtotal(100000L);
        order.setDiscount(10000L);
        order.setTotal(90000L);
        order.setReservedUntil(now);
        order.setCreatedBy(2L);
        order.setCompletedAt(now);

        assertThat(order.getId()).isEqualTo(1L);
        assertThat(order.getCode()).isEqualTo("POS-001");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);

        OrderItem item = new OrderItem();
        item.setId(2L);
        item.setOrderId(1L);
        item.setProductId(101L);
        item.setQuantity(2);
        item.setUnitPrice(50000L);
        item.setDiscount(5000L);
        item.setLineTotal(95000L);
        item.setPromotionId(11L);

        assertThat(item.getId()).isEqualTo(2L);
        assertThat(item.getLineTotal()).isEqualTo(95000L);

        Payment payment = new Payment();
        payment.setId(3L);
        payment.setOrderId(1L);
        payment.setMethod(PaymentMethod.CASH);
        payment.setAmount(90000L);
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderRef("REF-123");
        payment.setPaidAt(now);

        assertThat(payment.getId()).isEqualTo(3L);
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    void testSalesDtos() {
        PromotionRef promo = new PromotionRef(1L, "KM-1", "Giảm 10k", 10000L);
        QuoteLine line = new QuoteLine(101L, "SKU-1", "Sản phẩm", "cái", 1, 50000L, 0L, 50000L, promo, 10, true);
        Quote quote = new Quote(1L, 2L, List.of(line), promo, 50000L, 0L, 10000L, 0L, 10000L, 40000L, true);

        assertThat(quote.total()).isEqualTo(40000L);
        assertThat(quote.canComplete()).isTrue();
    }
}
