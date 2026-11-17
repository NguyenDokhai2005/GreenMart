package vn.greenmart.reporting.service;

import vn.greenmart.reporting.dto.RevenueReportFilter;
import vn.greenmart.reporting.dto.RevenueReportResponse;
import vn.greenmart.reporting.dto.TopProductsFilter;
import vn.greenmart.reporting.dto.TopProductsResponse;

public interface ReportService {
    RevenueReportResponse getRevenueReport(RevenueReportFilter filter);
    TopProductsResponse getTopProductsReport(TopProductsFilter filter);
}
