package vn.greenmart.common.pagination;

import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

public final class DateFilterUtils {

    public static final ZoneId ZONE_VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private DateFilterUtils() {}

    // Chuyển đổi cặp ngày from, to thành khoảng thời gian
    public static DateRange toOffsetDateTimeRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(
                ErrorCode.VALIDATION_ERROR,
                "Khoảng ngày không hợp lệ: ngày bắt đầu (from) phải trước hoặc bằng ngày kết thúc (to)",
                List.of(new ValidationErrorDetail("from", "from phải nhỏ hơn hoặc bằng to"))
            );
        }

        OffsetDateTime start = (from != null)
                ? from.atStartOfDay(ZONE_VN).toOffsetDateTime()
                : null;

        OffsetDateTime end = (to != null)
                ? to.plusDays(1).atStartOfDay(ZONE_VN).toOffsetDateTime()
                : null;

        return new DateRange(start, end);
    }

    // Phân giải chuỗi YYYY-MM-DD
    public static DateRange parse(String fromStr, String toStr) {
        LocalDate from = parseDate(fromStr, "from");
        LocalDate to = parseDate(toStr, "to");
        return toOffsetDateTimeRange(from, to);
    }

    private static LocalDate parseDate(String dateStr, String fieldName) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException ex) {
            throw new AppException(
                ErrorCode.VALIDATION_ERROR,
                "Định dạng ngày không hợp lệ cho trường " + fieldName + ", yêu cầu YYYY-MM-DD",
                List.of(new ValidationErrorDetail(fieldName, "Phải đúng định dạng YYYY-MM-DD"))
            );
        }
    }

    public record DateRange(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {}
}
