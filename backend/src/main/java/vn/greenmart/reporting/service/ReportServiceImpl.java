package vn.greenmart.reporting.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.reporting.dto.*;
import vn.greenmart.reporting.repository.ReportQueryRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportQueryRepository repository;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RevenueReportResponse getRevenueReport(RevenueReportFilter filter) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");
        var allowed = SecurityUtils.getAccessibleBranchIds(jdbc);

        if (filter.branchId() != null) {
            if (filter.branchId() <= 0) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "branchId phải lớn hơn 0");
            }
            if (allowed != null && !allowed.contains(filter.branchId())) {
                throw AppException.branchForbidden(filter.branchId());
            }
        }
        if (filter.channelId() != null && filter.channelId() <= 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "channelId phải lớn hơn 0");
        }

        LocalDate today = LocalDate.now(ReportQueryRepository.BUSINESS_ZONE);
        LocalDate to = filter.to() != null ? filter.to() : today;
        LocalDate from = filter.from() != null ? filter.from() : to.minusDays(29);

        if (from.isAfter(to)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Ngày bắt đầu không được sau ngày kết thúc");
        }

        String groupBy = filter.groupBy() != null ? filter.groupBy().trim().toLowerCase() : "day";
        List<String> allBuckets = new ArrayList<>();

        switch (groupBy) {
            case "day" -> {
                LocalDate cur = from;
                while (!cur.isAfter(to)) {
                    allBuckets.add(cur.toString());
                    cur = cur.plusDays(1);
                }
            }
            case "week" -> {
                LocalDate cur = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                while (!cur.isAfter(to)) {
                    allBuckets.add(cur.toString());
                    cur = cur.plusWeeks(1);
                }
            }
            case "month" -> {
                YearMonth cur = YearMonth.from(from);
                YearMonth end = YearMonth.from(to);
                while (!cur.isAfter(end)) {
                    allBuckets.add(cur.toString());
                    cur = cur.plusMonths(1);
                }
            }
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "groupBy chỉ chấp nhận: day, week, month");
        }

        var salesList = repository.querySales(from, to, groupBy, allowed, filter.branchId(), filter.channelId());
        var returnsList = repository.queryReturns(from, to, groupBy, allowed, filter.branchId(), filter.channelId());

        Map<String, Long> salesMap = salesList.stream().collect(Collectors.toMap(
                ReportQueryRepository.DailyRevenueRow::bucket,
                ReportQueryRepository.DailyRevenueRow::grossRevenue,
                Long::sum
        ));
        Map<String, Long> salesCountMap = salesList.stream().collect(Collectors.toMap(
                ReportQueryRepository.DailyRevenueRow::bucket,
                ReportQueryRepository.DailyRevenueRow::orderCount,
                Long::sum
        ));

        Map<String, Long> returnsMap = returnsList.stream().collect(Collectors.toMap(
                ReportQueryRepository.DailyReturnRow::bucket,
                ReportQueryRepository.DailyReturnRow::returnAmount,
                Long::sum
        ));
        Map<String, Long> returnCountMap = returnsList.stream().collect(Collectors.toMap(
                ReportQueryRepository.DailyReturnRow::bucket,
                ReportQueryRepository.DailyReturnRow::returnCount,
                Long::sum
        ));

        List<RevenuePointResponse> points = new ArrayList<>();
        long totalGrossRevenue = 0;
        long totalReturnAmount = 0;
        long totalNetRevenue = 0;
        long totalOrders = 0;
        long totalReturns = 0;

        for (String bucket : allBuckets) {
            long gross = salesMap.getOrDefault(bucket, 0L);
            long orders = salesCountMap.getOrDefault(bucket, 0L);
            long retAmt = returnsMap.getOrDefault(bucket, 0L);
            long rets = returnCountMap.getOrDefault(bucket, 0L);
            long net = gross - retAmt;

            totalGrossRevenue += gross;
            totalReturnAmount += retAmt;
            totalNetRevenue += net;
            totalOrders += orders;
            totalReturns += rets;

            points.add(new RevenuePointResponse(bucket, net, gross, retAmt, orders, rets));
        }

        RevenueSummaryResponse summary = new RevenueSummaryResponse(
                totalGrossRevenue,
                totalReturnAmount,
                totalNetRevenue,
                totalOrders,
                totalReturns
        );

        return new RevenueReportResponse(summary, points);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TopProductsResponse getTopProductsReport(TopProductsFilter filter) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");
        var allowed = SecurityUtils.getAccessibleBranchIds(jdbc);

        if (filter.branchId() != null) {
            if (filter.branchId() <= 0) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "branchId phải lớn hơn 0");
            }
            if (allowed != null && !allowed.contains(filter.branchId())) {
                throw AppException.branchForbidden(filter.branchId());
            }
        }
        if (filter.channelId() != null && filter.channelId() <= 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "channelId phải lớn hơn 0");
        }

        int limit = filter.limit() != null ? filter.limit() : 10;
        if (limit <= 0 || limit > 100) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "limit phải từ 1 đến 100");
        }

        LocalDate from;
        LocalDate to;

        if (filter.month() != null && !filter.month().isBlank()) {
            try {
                YearMonth ym = YearMonth.parse(filter.month().trim());
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } catch (DateTimeParseException e) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Tháng phải có định dạng YYYY-MM");
            }
        } else {
            LocalDate today = LocalDate.now(ReportQueryRepository.BUSINESS_ZONE);
            to = filter.to() != null ? filter.to() : today;
            from = filter.from() != null ? filter.from() : to.withDayOfMonth(1);
        }

        if (from.isAfter(to)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Ngày bắt đầu không được sau ngày kết thúc");
        }

        var topSelling = repository.queryTopProducts(from, to, allowed, filter.branchId(), filter.channelId(), limit, false);
        var slowSelling = repository.queryTopProducts(from, to, allowed, filter.branchId(), filter.channelId(), limit, true);

        return new TopProductsResponse(new TopProductsResponse.Period(from, to), topSelling, slowSelling);
    }
}
