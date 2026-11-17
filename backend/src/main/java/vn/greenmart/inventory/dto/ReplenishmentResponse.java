package vn.greenmart.inventory.dto;

import java.math.BigDecimal;

public record ReplenishmentResponse(Long branchId, Long productId, int available, long incoming,
        Integer reorderPoint, Integer manualReorderPoint, Integer safetyStock, BigDecimal forecastDemand,
        int packSize, Long suggestedQuantity) {}
