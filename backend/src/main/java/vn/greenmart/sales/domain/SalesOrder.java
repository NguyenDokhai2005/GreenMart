package vn.greenmart.sales.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sales_order")
@Getter
@Setter
@NoArgsConstructor
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "external_order_id", length = 60)
    private String externalOrderId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "promotion_id")
    private Long promotionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private OrderStatus status = OrderStatus.NEW;

    @Column(nullable = false)
    private Long subtotal = 0L;

    @Column(nullable = false)
    private Long discount = 0L;

    @Column(name = "manual_discount", nullable = false)
    private Long manualDiscount = 0L;

    @Column(name = "manual_discount_reason", length = 255)
    private String manualDiscountReason;

    @Column(nullable = false)
    private Long total = 0L;

    @Column(name = "reserved_until")
    private Instant reservedUntil;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
