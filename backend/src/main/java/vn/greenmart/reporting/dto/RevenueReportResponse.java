package vn.greenmart.reporting.dto;

import java.util.List;

public record RevenueReportResponse(
        RevenueSummaryResponse summary,
        List<RevenuePointResponse> points
) {
}
