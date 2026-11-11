package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.greenmart.catalog.service.ProductCostService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import vn.greenmart.integration.outbox.OutboxService;
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
    private final ProductCostService productCost;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final OutboxService outbox;

    /**
     *      WHERE (on_hand - reserved) >= qty
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long branchId, Long productId, int qty, Long refId) {
        validateQty(qty);
        productCost.lockForStockChange(productId);
        int rows = inventoryRepository.reserve(branchId, productId, qty);
        if (rows == 0) {
            // Read diagnostics only after the conditional update has failed.

            throw insufficientStock(branchId, productId, qty);
        }
        log.debug("reserve: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.RESERVE, 0, qty, refId);
        enqueueStockChanged(branchId, productId);
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
        productCost.lockForStockChange(productId);
        int rows = inventoryRepository.release(branchId, productId, qty);
        if (rows == 0) {
            log.error("release failed: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
            throw new IllegalStateException(String.format(
                    "release thất bại: branch=%d product=%d qty=%d — reserved < qty hoặc row không tồn tại",
                    branchId, productId, qty));
        }
        log.debug("release: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.RELEASE, 0, -qty, refId);
        enqueueStockChanged(branchId, productId);
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
        productCost.lockForStockChange(productId);
        int rows = inventoryRepository.commitSale(branchId, productId, qty);
        if (rows == 0) {
            log.error("commitSale failed: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
            throw new IllegalStateException(String.format(
                    "commitSale thất bại: branch=%d product=%d qty=%d — on_hand hoặc reserved < qty",
                    branchId, productId, qty));
        }
        log.debug("commitSale: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.SALE, -qty, -qty, refId);
        enqueueStockChanged(branchId, productId);
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
        productCost.lockForStockChange(productId);
        int rows = inventoryRepository.sellDirect(branchId, productId, qty);
        if (rows == 0) {
            throw insufficientStock(branchId, productId, qty);
        }
        log.debug("sellDirect: branch={} product={} qty={} refId={}", branchId, productId, qty, refId);
        transactionWriter.appendOrder(branchId, productId, Type.SALE, -qty, 0, refId);
        enqueueStockChanged(branchId, productId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public int getAvailable(Long branchId, Long productId) {
        if (branchId == null || productId == null) {
            return 0;
        }
        return inventoryRepository.findById(
                new vn.greenmart.inventory.domain.Inventory.InventoryId(branchId, productId))
                .map(vn.greenmart.inventory.domain.Inventory::getAvailable)
                .orElse(0);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void importStock(Long branchId, Long productId, int qty, long unitCost, Long documentId, Long actorId) {
        validateQty(qty);
        if (unitCost < 0 || documentId == null || actorId == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Thiếu tham chiếu nhập kho hoặc giá nhập không hợp lệ");
        }
        entityManager.flush();
        long oldCost = productCost.lockForImport(productId);
        long oldStock = jdbc.queryForObject("SELECT COALESCE(SUM(on_hand),0) FROM inventory WHERE product_id=?",
                Long.class, productId);
        long newCost;
        try {
            long numerator = Math.addExact(Math.multiplyExact(oldStock, oldCost), Math.multiplyExact((long) qty, unitCost));
            newCost = BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(Math.addExact(oldStock, qty)), 0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Giá trị nhập kho vượt giới hạn số tiền");
        }
        increaseOnHand(branchId, productId, qty);
        productCost.updateCostPrice(productId, newCost);
        transactionWriter.appendImport(branchId, productId, qty, documentId, actorId);
        enqueueStockChanged(branchId, productId);
        entityManager.clear(); // Native writes must not leave stale Inventory/Product entities in the caller.
    }

    private void increaseOnHand(Long branchId, Long productId, int qty) {
        int rows = jdbc.update("""
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,version,updated_at)
                VALUES(?,?,?,0,1,CURRENT_TIMESTAMP)
                ON CONFLICT(branch_id,product_id) DO UPDATE
                SET on_hand=inventory.on_hand+EXCLUDED.on_hand, version=inventory.version+1, updated_at=CURRENT_TIMESTAMP
                WHERE inventory.on_hand<=2147483647-EXCLUDED.on_hand
                """, branchId, productId, qty);
        if (rows != 1) throw new AppException(ErrorCode.VALIDATION_ERROR, "Số lượng tồn vượt giới hạn lưu trữ");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferOut(Long branchId, Long productId, int qty, Long documentId, Long actorId) {
        validateQty(qty);
        productCost.lockForStockChange(productId);
        if (inventoryRepository.sellDirect(branchId, productId, qty) != 1) {
            throw insufficientStock(branchId, productId, qty);
        }
        transactionWriter.appendTransfer(branchId, productId, -qty, documentId, actorId);
        enqueueStockChanged(branchId, productId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferIn(Long branchId, Long productId, int qty, Long documentId, Long actorId) {
        validateQty(qty);
        entityManager.flush();
        productCost.lockForStockChange(productId);
        increaseOnHand(branchId, productId, qty);
        transactionWriter.appendTransfer(branchId, productId, qty, documentId, actorId);
        enqueueStockChanged(branchId, productId);
        entityManager.clear();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long exportStock(Long branchId, Long productId, int qty, Long documentId, Long actorId) {
        validateQty(qty);
        if (documentId == null || actorId == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Thiếu tham chiếu phiếu xuất hủy hoặc người duyệt");
        }
        entityManager.flush();
        long cost = productCost.lockForExport(productId);
        try { Math.multiplyExact((long) qty, cost); }
        catch (ArithmeticException ex) { throw new AppException(ErrorCode.VALIDATION_ERROR, "Giá trị hủy vượt giới hạn số tiền"); }
        int rows = inventoryRepository.sellDirect(branchId, productId, qty);
        if (rows != 1) throw insufficientStock(branchId, productId, qty);
        transactionWriter.appendExport(branchId, productId, qty, documentId, actorId);
        enqueueStockChanged(branchId, productId);
        entityManager.clear();
        return cost;
    }

    private void enqueueStockChanged(Long branchId, Long productId) {
        // The stock update holds the row lock until commit, so this is its exact version.
        Long version = jdbc.queryForObject("""
                SELECT version FROM inventory WHERE branch_id=? AND product_id=?
                """, Long.class, branchId, productId);
        outbox.enqueue("stock", branchId + ":" + productId, "stock.changed",
                Map.of("branchId", branchId, "productId", productId, "version", version));
    }

    // Private helpers

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
