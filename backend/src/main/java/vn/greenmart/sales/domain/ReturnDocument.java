package vn.greenmart.sales.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "return_document")
@Getter
@Setter
@NoArgsConstructor
public class ReturnDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReturnStatus status = ReturnStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    @OneToMany(mappedBy = "returnDocument", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ReturnItem> items = new ArrayList<>();

    public void addItem(ReturnItem item) {
        item.setReturnDocument(this);
        this.items.add(item);
    }
}
