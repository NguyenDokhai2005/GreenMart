package vn.greenmart.reporting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.reporting.dto.RevenueReportFilter;
import vn.greenmart.reporting.dto.RevenueReportResponse;
import vn.greenmart.reporting.dto.TopProductsFilter;
import vn.greenmart.reporting.dto.TopProductsResponse;
import vn.greenmart.reporting.service.ReportService;

import java.time.LocalDate;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService service;

    @GetMapping("/revenue")
    public RevenueReportResponse getRevenueReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "day") String groupBy,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long channelId
    ) {
        return service.getRevenueReport(new RevenueReportFilter(from, to, groupBy, branchId, channelId));
    }

    @GetMapping("/top-products")
    public TopProductsResponse getTopProductsReport(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "10") Integer limit,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long channelId
    ) {
        return service.getTopProductsReport(new TopProductsFilter(month, from, to, limit, branchId, channelId));
    }
}
