package vn.greenmart.common.pagination;

import org.junit.jupiter.api.Test;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateFilterUtilsTest {

    @Test
    void testToOffsetDateTimeRange_ValidRange() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 5);

        DateFilterUtils.DateRange range = DateFilterUtils.toOffsetDateTimeRange(from, to);

        assertNotNull(range.startInclusive());
        assertNotNull(range.endExclusive());
        assertEquals("2026-10-01T00:00+07:00", range.startInclusive().toString());
        assertEquals("2026-10-06T00:00+07:00", range.endExclusive().toString());
    }

    @Test
    void testToOffsetDateTimeRange_FromAfterTo_Throws400() {
        LocalDate from = LocalDate.of(2026, 10, 10);
        LocalDate to = LocalDate.of(2026, 10, 5);

        AppException ex = assertThrows(AppException.class, () ->
            DateFilterUtils.toOffsetDateTimeRange(from, to)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("from", details.get(0).field());
    }

    @Test
    void testParse_ValidStringDates() {
        DateFilterUtils.DateRange range = DateFilterUtils.parse("2026-11-01", "2026-11-05");

        assertEquals("2026-11-01T00:00+07:00", range.startInclusive().toString());
        assertEquals("2026-11-06T00:00+07:00", range.endExclusive().toString());
    }

    @Test
    void testParse_InvalidFormat_Throws400() {
        AppException ex = assertThrows(AppException.class, () ->
            DateFilterUtils.parse("01-11-2026", "2026-11-05")
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("from", details.get(0).field());
    }

    @Test
    void testParse_NullDates() {
        DateFilterUtils.DateRange range = DateFilterUtils.parse(null, null);
        assertNull(range.startInclusive());
        assertNull(range.endExclusive());
    }

    @SuppressWarnings("unchecked")
    private List<ValidationErrorDetail> getDetails(AppException ex) {
        return (List<ValidationErrorDetail>) ex.getDetails();
    }
}
