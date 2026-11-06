package vn.greenmart.catalog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProductCostServiceImpl implements ProductCostService {
    private final JdbcTemplate jdbc;

    @Override
    public void lockForStockChange(Long productId) {
        // Ledger FK checks need KEY SHARE too. Taking it before inventory avoids
        // inventory -> product / product -> inventory deadlocks with IMPORT.
        jdbc.queryForList("SELECT id FROM product WHERE id=? FOR KEY SHARE", Long.class, productId);
    }

    @Override
    public long lockForImport(Long productId) {
        return jdbc.query("SELECT cost_price FROM product WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong("cost_price"), productId).stream().findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm nhập kho"));
    }

    @Override
    public void updateCostPrice(Long productId, long costPrice) {
        if (costPrice < 0) throw new AppException(ErrorCode.VALIDATION_ERROR, "Giá vốn phải không âm");
        if (jdbc.update("UPDATE product SET cost_price=? WHERE id=?", costPrice, productId) != 1) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm nhập kho");
        }
    }

    @Override
    public long lockForExport(Long productId) {
        // SHARE freezes cost (including non-key price updates), but permits other stock changes.
        return jdbc.query("SELECT cost_price FROM product WHERE id=? FOR SHARE",
                (rs, row) -> rs.getLong("cost_price"), productId).stream().findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm xuất hủy"));
    }
}
