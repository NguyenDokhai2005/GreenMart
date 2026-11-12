package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.inventory.dto.StockStatus;

@Service
@RequiredArgsConstructor
public class StockAlertService {
    private final JdbcTemplate jdbc;

    /** The caller must already hold the lock on the updated inventory row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileAfterStockUpdate(Long branchId, Long productId) {
        StockStatus status = jdbc.queryForObject("""
                SELECT on_hand - reserved AS available, reorder_point
                FROM inventory WHERE branch_id=? AND product_id=?
                """, (rs, row) -> classify(rs.getInt("available"), rs.getObject("reorder_point", Integer.class)),
                branchId, productId);

        if (status == StockStatus.OK) {
            jdbc.update("""
                    UPDATE stock_alert SET closed_at=clock_timestamp()
                    WHERE branch_id=? AND product_id=? AND closed_at IS NULL
                    """, branchId, productId);
        } else {
            jdbc.update("""
                    INSERT INTO stock_alert(branch_id, product_id, severity, opened_at)
                    VALUES (?, ?, ?, clock_timestamp())
                    ON CONFLICT (branch_id, product_id) WHERE closed_at IS NULL
                    DO UPDATE SET severity=EXCLUDED.severity
                    WHERE stock_alert.severity <> EXCLUDED.severity
                    """, branchId, productId, status.name());
        }
    }

    static StockStatus classify(int available, Integer reorderPoint) {
        if (available == 0) return StockStatus.OUT;
        return reorderPoint != null && available <= reorderPoint ? StockStatus.LOW : StockStatus.OK;
    }
}
