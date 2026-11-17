package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.dto.ReplenishmentResponse;
import vn.greenmart.inventory.repository.InventoryReplenishmentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class InventoryReplenishmentService {
    private final InventoryReplenishmentRepository repository;
    private final StockAlertService alerts;
    private final JdbcTemplate jdbc;

    @Transactional
    public void refresh(LocalDate day) {
        if (!repository.tryLockRefresh()) return;
        for (var plan : repository.plans(day)) {
            if (repository.save(plan)) {
                alerts.reconcileAfterStockUpdate(plan.branchId(), plan.productId());
            }
        }
    }

    @Transactional(readOnly = true)
    public ReplenishmentResponse get(Long branchId, Long productId) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "STOREKEEPER");
        requireBranch(branchId, productId);
        return response(branchId, productId);
    }

    @Transactional
    public ReplenishmentResponse configure(Long branchId, Long productId, Integer manualReorderPoint, int packSize) {
        SecurityUtils.requireRole("BRANCH_MANAGER");
        requireBranch(branchId, productId);
        if (packSize < 1 || (manualReorderPoint != null && manualReorderPoint < 0)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Quy cách hoặc ROP nhập tay không hợp lệ");
        }
        if (!repository.configure(branchId, productId, manualReorderPoint, packSize)) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        alerts.reconcileAfterStockUpdate(branchId, productId);
        return response(branchId, productId);
    }

    private void requireBranch(Long branchId, Long productId) {
        if (branchId <= 0 || productId <= 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Mã chi nhánh và sản phẩm phải lớn hơn 0");
        }
        var allowed = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (allowed != null && !allowed.contains(branchId)) throw AppException.branchForbidden(branchId);
    }

    private ReplenishmentResponse response(Long branchId, Long productId) {
        var position = repository.find(branchId, productId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        Long suggestedQuantity = null;
        if (position.demand() != null) {
            BigDecimal deficit = position.demand().add(BigDecimal.valueOf(position.safetyStock()))
                    .subtract(BigDecimal.valueOf(position.available()))
                    .subtract(BigDecimal.valueOf(position.incoming())).max(BigDecimal.ZERO);
            BigDecimal packSize = BigDecimal.valueOf(position.packSize());
            suggestedQuantity = deficit.divide(packSize, 0, RoundingMode.CEILING).multiply(packSize).longValueExact();
        }
        return new ReplenishmentResponse(branchId, productId, position.available(), position.incoming(), position.reorderPoint(),
                position.manualReorderPoint(), position.safetyStock(), position.demand(), position.packSize(),
                suggestedQuantity);
    }
}
