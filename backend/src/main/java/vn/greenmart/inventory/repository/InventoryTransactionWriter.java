package vn.greenmart.inventory.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only ledger; the caller must update and lock the inventory row first. */
@Repository
@RequiredArgsConstructor
public class InventoryTransactionWriter {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendTransfer(Long branchId, Long productId, int delta, Long documentId, Long actorId) {
        int inserted = jdbc.update("""
                INSERT INTO inventory_transaction
                    (branch_id,product_id,type,delta_on_hand,on_hand_after,delta_reserved,reserved_after,ref_type,ref_id,created_by)
                SELECT branch_id,product_id,?,?,on_hand,0,reserved,'STOCK_DOCUMENT',?,?
                FROM inventory WHERE branch_id=? AND product_id=?
                """, delta < 0 ? "TRANSFER_OUT" : "TRANSFER_IN", delta, documentId, actorId, branchId, productId);
        if (inserted != 1) throw new IllegalStateException("Không tìm thấy tồn kho để ghi sổ chuyển kho");
    }

    public enum Type { SALE, RESERVE, RELEASE }

    /**
     * The UPDATE row lock lasts until commit, so these balances belong to this change.
     * JdbcTemplate shares the JPA transaction's datasource connection. Actor remains
     * nullable until Identity provides the authenticated user; never invent a user id.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendOrder(Long branchId, Long productId, Type type,
                            int deltaOnHand, int deltaReserved, Long orderId) {
        int inserted = jdbc.update("""
                INSERT INTO inventory_transaction
                    (branch_id, product_id, type, delta_on_hand, on_hand_after,
                     delta_reserved, reserved_after, ref_type, ref_id)
                SELECT branch_id, product_id, ?, ?, on_hand, ?, reserved, 'ORDER', ?
                FROM inventory WHERE branch_id = ? AND product_id = ?
                """, type.name(), deltaOnHand, deltaReserved, orderId, branchId, productId);
        if (inserted != 1) {
            throw new IllegalStateException("Không tìm thấy tồn kho để ghi sổ giao dịch");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendImport(Long branchId, Long productId, int qty, Long documentId, Long actorId) {
        int inserted = jdbc.update("""
                INSERT INTO inventory_transaction
                    (branch_id,product_id,type,delta_on_hand,on_hand_after,delta_reserved,reserved_after,ref_type,ref_id,created_by)
                SELECT branch_id,product_id,'IMPORT',?,on_hand,0,reserved,'STOCK_DOCUMENT',?,?
                FROM inventory WHERE branch_id=? AND product_id=?
                """, qty, documentId, actorId, branchId, productId);
        if (inserted != 1) throw new IllegalStateException("Không tìm thấy tồn kho để ghi sổ nhập hàng");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendExport(Long branchId, Long productId, int qty, Long documentId, Long actorId) {
        int inserted = jdbc.update("""
                INSERT INTO inventory_transaction
                    (branch_id,product_id,type,delta_on_hand,on_hand_after,delta_reserved,reserved_after,ref_type,ref_id,created_by)
                SELECT branch_id,product_id,'EXPORT',?,on_hand,0,reserved,'STOCK_DOCUMENT',?,?
                FROM inventory WHERE branch_id=? AND product_id=?
                """, -qty, documentId, actorId, branchId, productId);
        if (inserted != 1) throw new IllegalStateException("Không tìm thấy tồn kho để ghi sổ xuất hủy");
    }
}
