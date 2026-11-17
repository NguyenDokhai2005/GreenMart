package vn.greenmart.reporting.dto;

public record RevenuePointResponse(
        String date,
        Long revenue,
        Long grossRevenue,
        Long returnAmount,
        Long orderCount,
        Long returnCount
) {
}
