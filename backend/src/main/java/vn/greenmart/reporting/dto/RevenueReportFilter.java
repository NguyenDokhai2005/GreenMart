package vn.greenmart.reporting.dto;

import java.time.LocalDate;

public record RevenueReportFilter(
        LocalDate from,
        LocalDate to,
        String groupBy,
        Long branchId,
        Long channelId
) {
}
