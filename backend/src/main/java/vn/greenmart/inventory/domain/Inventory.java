package vn.greenmart.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Tồn kho theo chi nhánh × sản phẩm.
 * <p>
 * Khóa chính tổng hợp (branchId, productId).
 * Không dùng {@code @Version} JPA vì cập nhật bảo vệ bằng conditional UPDATE.
 * Cột {@code version} giữ để audit; không annotate {@code @Version}.
 */
@Entity
@Table(name = "inventory")
@IdClass(Inventory.InventoryId.class)
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "reorder_point")
    private Integer reorderPoint;

    /** Tăng sau mỗi conditional UPDATE – dùng để audit, không phải @Version JPA. */
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Tồn khả dụng = on_hand − reserved. Tính runtime, không lưu DB. */
    public int getAvailable() {
        return onHand - reserved;
    }

    // -----------------------------------------------------------------------
    // Composite Primary Key
    // -----------------------------------------------------------------------

    public static class InventoryId implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long branchId;
        private Long productId;

        public InventoryId() {}

        public InventoryId(Long branchId, Long productId) {
            this.branchId  = branchId;
            this.productId = productId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InventoryId other)) return false;
            return Objects.equals(branchId, other.branchId)
                && Objects.equals(productId, other.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(branchId, productId);
        }
    }
}
