package vn.greenmart.promotion.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "promotion")
@Getter
@Setter
@NoArgsConstructor
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PromotionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PromotionLevel level;

    @Column(nullable = false)
    private Long value;

    @Column(name = "max_discount")
    private Long maxDiscount;

    @Column(name = "min_order")
    private Long minOrder;

    @Column(name = "min_quantity")
    private Integer minQuantity;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PromotionStatus status = PromotionStatus.DRAFT;
}
