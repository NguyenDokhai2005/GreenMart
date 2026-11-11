package vn.greenmart.reporting.dto;

import java.time.LocalDate;
import java.util.List;

public record TopProductsResponse(
        Period period,
        List<ProductSalesResponse> topSelling,
        List<ProductSalesResponse> slowSelling
) {
    public record Period(LocalDate from, LocalDate to) {}
}
