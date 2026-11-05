package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;


@Service
@RequiredArgsConstructor
public class PromotionService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUsage(Long promotionId) {
        if (promotionId == null) return;
        int rows = jdbcTemplate.update(
                "UPDATE promotion SET used_count = used_count + 1 WHERE id = ? AND (usage_limit IS NULL OR used_count < usage_limit)",
                promotionId);
        if (rows == 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Chương trình khuyến mãi đã hết lượt sử dụng (PROMOTION_EXHAUSTED)");
        }
    }
}
