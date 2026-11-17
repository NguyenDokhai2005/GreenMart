package vn.greenmart.promotion.service;

import jakarta.servlet.http.HttpServletRequest;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.promotion.dto.PromotionRequest;
import vn.greenmart.promotion.dto.PromotionResponse;
import vn.greenmart.promotion.dto.PromotionStatusRequest;

import java.time.Instant;
import java.util.List;

public interface PromotionCrudService {
    PageResponse<PromotionResponse> listPromotions(
            List<String> status,
            String level,
            Instant activeAt,
            Long branchId,
            String channel,
            String q,
            Integer page,
            Integer size,
            HttpServletRequest request
    );

    PromotionResponse getPromotion(Long promotionId);

    PromotionResponse createPromotion(PromotionRequest request);

    PromotionResponse updatePromotion(Long promotionId, PromotionRequest request);

    PromotionResponse changeStatus(Long promotionId, PromotionStatusRequest request);
}
