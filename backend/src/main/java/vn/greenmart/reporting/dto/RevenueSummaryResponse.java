package vn.greenmart.reporting.dto;

public record RevenueSummaryResponse(
        Long totalGrossRevenue,
        Long totalReturnAmount,
        Long totalNetRevenue,
        Long totalOrders,
        Long totalReturns
) {
}
