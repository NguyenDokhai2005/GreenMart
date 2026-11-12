package vn.greenmart.reporting.dto;

import java.time.LocalDate;

public record TopProductsFilter(
        String month,
        LocalDate from,
        LocalDate to,
        Integer limit,
        Long branchId,
        Long channelId
) {
}
