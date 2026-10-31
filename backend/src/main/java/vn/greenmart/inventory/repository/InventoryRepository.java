package vn.greenmart.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.inventory.domain.Inventory;

/**
 * Repository tồn kho.
 * <p>
 * Các {@code @Modifying @Query} trả về {@code int} (số dòng bị tác động).
 * {@code InventoryServiceImpl} kiểm tra kết quả == 1; nếu == 0 thì điều kiện
 * WHERE không thỏa (không đủ tồn hoặc row không tồn tại).
 * <p>
 * JPQL dùng tên entity class {@code Inventory} (Java), không phải tên bảng SQL.
 * Dùng conditional update pattern (BR-01): toàn bộ kiểm tra nằm trong WHERE.
 */
public interface InventoryRepository extends JpaRepository<Inventory, Inventory.InventoryId> {

    /**
     * Giữ chỗ: tăng reserved khi available = (on_hand − reserved) >= qty.
     * on_hand không đổi.
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.reserved  = i.reserved + :qty,
                i.version   = i.version + 1,
                i.updatedAt = CURRENT_TIMESTAMP
            WHERE i.branchId  = :branchId
              AND i.productId = :productId
              AND (i.onHand - i.reserved) >= :qty
            """)
    int reserve(
            @Param("branchId")  Long branchId,
            @Param("productId") Long productId,
            @Param("qty")       int  qty);

    /**
     * Nhả giữ chỗ: giảm reserved khi reserved >= qty.
     * on_hand không đổi.
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.reserved  = i.reserved - :qty,
                i.version   = i.version + 1,
                i.updatedAt = CURRENT_TIMESTAMP
            WHERE i.branchId  = :branchId
              AND i.productId = :productId
              AND i.reserved >= :qty
            """)
    int release(
            @Param("branchId")  Long branchId,
            @Param("productId") Long productId,
            @Param("qty")       int  qty);

    /**
     * Hoàn tất bán online: giảm đồng thời on_hand và reserved trong 1 UPDATE.
     * Dùng khi đơn CONFIRMED → COMPLETED (UC-02).
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.onHand    = i.onHand   - :qty,
                i.reserved  = i.reserved - :qty,
                i.version   = i.version + 1,
                i.updatedAt = CURRENT_TIMESTAMP
            WHERE i.branchId  = :branchId
              AND i.productId = :productId
              AND i.onHand   >= :qty
              AND i.reserved >= :qty
            """)
    int commitSale(
            @Param("branchId")  Long branchId,
            @Param("productId") Long productId,
            @Param("qty")       int  qty);

    /**
     * Bán POS: giảm on_hand khi available >= qty.
     * Không ăn vào phần reserved của đơn online (BR-02).
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.onHand    = i.onHand - :qty,
                i.version   = i.version + 1,
                i.updatedAt = CURRENT_TIMESTAMP
            WHERE i.branchId  = :branchId
              AND i.productId = :productId
              AND (i.onHand - i.reserved) >= :qty
            """)
    int sellDirect(
            @Param("branchId")  Long branchId,
            @Param("productId") Long productId,
            @Param("qty")       int  qty);
}
