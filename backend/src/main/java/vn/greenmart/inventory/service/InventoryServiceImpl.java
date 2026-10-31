package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.inventory.exception.InsufficientStockException;
import vn.greenmart.inventory.repository.InventoryRepository;

/**
 * Triển khai InventoryService dùng conditional UPDATE pattern (BR-01).
 * <p>
 * Tất cả phương thức yêu cầu transaction đang mở từ caller (MANDATORY).
 * Mục đích: ghi inventory_transaction (issue #50) và cập nhật inventory
 * trong CÙNG một transaction, đảm bảo consistency.
 * <p>
 * Tại sao không dùng SELECT-then-UPDATE hay SELECT FOR UPDATE?<br>
 * PostgreSQL serialize row-level UPDATE: khi T1 UPDATE một row, T2 phải chờ.
 * Sau khi T1 commit, T2 thấy giá trị mới và điều kiện WHERE được re-evaluate.
 * Không có race condition. SELECT trước không cần thiết và gây thêm round-trip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;

    /** Sentinel: dùng khi không biết chính xác available (không SELECT trước). */
    private static final int UNKNOWN_AVAILABLE = -1;

    /**
     *      WHERE (on_hand - reserved) >= qty
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long branchId, Long productId, int qty, Long refId) {
        validateQty(qty);
        int rows = inventoryRepository.reserve(branchId, productId, qty);
        if (rows == 0) {
            // Không SELECT trước nên không biết available chính xác; truyền -1.
            // Caller fetch lại nếu cần hiển thị số cụ thể cho người dùng.
            throw new InsufficientStockException(branchId, productId, qty, UNKNOWN_AVAILABLE);
        }
        log.debug("reserve: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        // TODO(#50): ghi inventory_transaction type=RESERVE, delta_on_hand=0, delta_reserved=+qty
    }

    /**
     * {@inheritDoc}
     * SQL: UPDATE inventory SET reserved = reserved - qty
     *      WHERE reserved >= qty
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long branchId, Long productId, int qty, Long refId) {
        validateQty(qty);
        int rows = inventoryRepository.release(branchId, productId, qty);
        if (rows == 0) {
            // 0 dòng = reserved < qty hoặc row không tồn tại — lỗi logic của caller
            throw new IllegalStateException(String.format(
                    "release thất bại: branch=%d product=%d qty=%d — reserved < qty hoặc row không tồn tại",
                    branchId, productId, qty));
        }
        log.debug("release: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        // TODO(#50): ghi inventory_transaction type=RELEASE, delta_on_hand=0, delta_reserved=-qty
    }

    /**
     * {@inheritDoc}
     * SQL: UPDATE inventory SET on_hand = on_hand - qty, reserved = reserved - qty
     *      WHERE on_hand >= qty AND reserved >= qty
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitSale(Long branchId, Long productId, int qty, Long refId) {
        validateQty(qty);
        int rows = inventoryRepository.commitSale(branchId, productId, qty);
        if (rows == 0) {
            throw new IllegalStateException(String.format(
                    "commitSale thất bại: branch=%d product=%d qty=%d — on_hand hoặc reserved < qty",
                    branchId, productId, qty));
        }
        log.debug("commitSale: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        // TODO(#50): ghi inventory_transaction type=SALE, delta_on_hand=-qty, delta_reserved=-qty
    }

    /**
     * {@inheritDoc}
     * SQL: UPDATE inventory SET on_hand = on_hand - qty
     *      WHERE (on_hand - reserved) >= qty
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void sellDirect(Long branchId, Long productId, int qty, Long refId) {
        validateQty(qty);
        int rows = inventoryRepository.sellDirect(branchId, productId, qty);
        if (rows == 0) {
            throw new InsufficientStockException(branchId, productId, qty, UNKNOWN_AVAILABLE);
        }
        log.debug("sellDirect: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        // TODO(#50): ghi inventory_transaction type=SALE, delta_on_hand=-qty, delta_reserved=0
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void validateQty(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty phải > 0, nhận: " + qty);
        }
    }
}
