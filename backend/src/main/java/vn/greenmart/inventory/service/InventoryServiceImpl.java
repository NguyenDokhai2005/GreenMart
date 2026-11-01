package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.inventory.exception.InsufficientStockException;
import vn.greenmart.inventory.repository.InventoryRepository;
import vn.greenmart.inventory.repository.InventoryTransactionWriter;
import vn.greenmart.inventory.repository.InventoryTransactionWriter.Type;

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
    private final InventoryTransactionWriter transactionWriter;



    /**
     *      WHERE (on_hand - reserved) >= qty
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long branchId, Long productId, int qty, Long refId) {
        validateQty(qty);
        int rows = inventoryRepository.reserve(branchId, productId, qty);
        if (rows == 0) {
            // Read diagnostics only after the conditional update has failed.

            throw insufficientStock(branchId, productId, qty);
        }
        log.debug("reserve: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.RESERVE, 0, qty, refId);
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
            log.error("release failed: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
            throw new IllegalStateException(String.format(
                    "release thất bại: branch=%d product=%d qty=%d — reserved < qty hoặc row không tồn tại",
                    branchId, productId, qty));
        }
        log.debug("release: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.RELEASE, 0, -qty, refId);
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
            log.error("commitSale failed: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
            throw new IllegalStateException(String.format(
                    "commitSale thất bại: branch=%d product=%d qty=%d — on_hand hoặc reserved < qty",
                    branchId, productId, qty));
        }
        log.debug("commitSale: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.SALE, -qty, -qty, refId);
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
            throw insufficientStock(branchId, productId, qty);
        }
        log.debug("sellDirect: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.SALE, -qty, 0, refId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private InsufficientStockException insufficientStock(Long branchId, Long productId, int qty) {
        int available = inventoryRepository.findById(
                new vn.greenmart.inventory.domain.Inventory.InventoryId(branchId, productId))
                .map(vn.greenmart.inventory.domain.Inventory::getAvailable).orElse(0);
        String sku = inventoryRepository.findProductSku(productId).orElse("");
        return new InsufficientStockException(branchId, productId, sku, qty, available);
    }

    private void validateQty(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty phải > 0, nhận: " + qty);
        }
    }
}
