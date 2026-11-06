package vn.greenmart.sales;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.greenmart.sales.domain.OrderItem;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.domain.PaymentStatus;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.InsufficientStockLine;
import vn.greenmart.sales.dto.OrderItemResponse;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PaymentResponse;
import vn.greenmart.sales.dto.PromotionRef;
import vn.greenmart.sales.dto.Quote;
import vn.greenmart.sales.dto.QuoteLine;
import vn.greenmart.sales.dto.UserRef;

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

    @Test
    void testNewSalesDtos() {
        PaymentRequest pr = new PaymentRequest(PaymentMethod.CARD, null, null);
        assertThat(pr.simulate()).isEqualTo(PaymentSimulation.SUCCESS);

        UserRef userRef = new UserRef(1L, "Thu Ngân");
        assertThat(userRef.id()).isEqualTo(1L);

        OrderItemResponse itemRes = new OrderItemResponse(1L, 101L, "SKU", "Tên", "hộp", 2, 50000L, 0L, 100000L, null);
        assertThat(itemRes.lineTotal()).isEqualTo(100000L);

        PaymentResponse payRes = new PaymentResponse(1L, PaymentMethod.CASH, 100000L, PaymentStatus.PAID, 120000L, 20000L, null, Instant.now());
        assertThat(payRes.change()).isEqualTo(20000L);

        InsufficientStockLine isl = new InsufficientStockLine(1L, "SKU", 2, 1);
        assertThat(isl.requested()).isEqualTo(2);
        assertThat(isl.available()).isEqualTo(1);
    }
}
