package vn.greenmart.inventory.service;

/**
 * Lõi chống bán vượt tồn (INV-07, BR-01).
 * <p>
 * Mỗi phương thức thực hiện đúng 1 conditional UPDATE nguyên tử.
 * Caller (SalesService) chịu trách nhiệm:
 * <ul>
 *   <li>Mở {@code @Transactional} bao ngoài.</li>
 *   <li>Với đơn nhiều dòng: sort theo productId ASC trước khi gọi để tránh deadlock.</li>
 *   <li>Cung cấp đúng qty đã reserve khi gọi release/commitSale.</li>
 * </ul>
 */
public interface InventoryService {

    /**
     * Giữ chỗ cho đơn online (NEW → RESERVED).
     * Tăng {@code reserved}; {@code on_hand} không đổi.
     *
     * @param branchId  id chi nhánh
     * @param productId id sản phẩm
     * @param qty       số lượng cần giữ (phải > 0)
     * @param refId     id đơn hàng tham chiếu (dành cho inventory_transaction ở #50)
     * @throws vn.greenmart.inventory.exception.InsufficientStockException
     *         nếu available = (on_hand − reserved) &lt; qty
     */
    void reserve(Long branchId, Long productId, int qty, Long refId);

    /**
     * Nhả giữ chỗ khi đơn hủy hoặc hết hạn (→ CANCELLED/EXPIRED).
     * Giảm {@code reserved}; {@code on_hand} không đổi.
     *
     * @throws IllegalStateException nếu reserved &lt; qty (lỗi logic của caller)
     */
    void release(Long branchId, Long productId, int qty, Long refId);

    /**
     * Hoàn tất bán online (CONFIRMED → COMPLETED).
     * Giảm đồng thời {@code on_hand} và {@code reserved} trong 1 UPDATE nguyên tử.
     *
     * @throws IllegalStateException nếu on_hand &lt; qty hoặc reserved &lt; qty
     */
    void commitSale(Long branchId, Long productId, int qty, Long refId);

    /**
     * Bán POS trực tiếp (UC-01, BR-02).
     * Giảm {@code on_hand}; {@code reserved} không đổi.
     * Kiểm tra available = (on_hand − reserved) để không ăn vào phần đơn online.
     *
     * @throws vn.greenmart.inventory.exception.InsufficientStockException
     *         nếu available &lt; qty
     */
    void sellDirect(Long branchId, Long productId, int qty, Long refId);
}
