package vn.greenmart.common.pagination;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageUtilsTest {

    private static final Set<String> ALLOWED_FIELDS = Set.of("createdAt", "salePrice", "name");

    @SuppressWarnings("unchecked")
    private List<ValidationErrorDetail> getDetails(AppException ex) {
        return (List<ValidationErrorDetail>) ex.getDetails();
    }

    @Test
    void testCreatePageable_DefaultValues() {
        Pageable pageable = PageUtils.createPageable(null, null, (String) null, null, ALLOWED_FIELDS);

        assertEquals(PageUtils.DEFAULT_PAGE, pageable.getPageNumber());
        assertEquals(PageUtils.DEFAULT_SIZE, pageable.getPageSize());
        assertTrue(pageable.getSort().isUnsorted());
    }

    @Test
    void testCreatePageable_CustomValuesAndSortDesc() {
        Pageable pageable = PageUtils.createPageable(2, 50, "createdAt,desc", null, ALLOWED_FIELDS);

        assertEquals(2, pageable.getPageNumber());
        assertEquals(50, pageable.getPageSize());
        Sort.Order order = pageable.getSort().getOrderFor("createdAt");
        assertNotNull(order);
        assertEquals(Sort.Direction.DESC, order.getDirection());
    }

    @Test
    void testCreatePageable_MultipleSortFields() {
        Pageable pageable = PageUtils.createPageable(
            0, 20, List.of("createdAt,desc", "name,asc"), null, ALLOWED_FIELDS
        );

        Sort.Order order1 = pageable.getSort().getOrderFor("createdAt");
        Sort.Order order2 = pageable.getSort().getOrderFor("name");
        assertNotNull(order1);
        assertEquals(Sort.Direction.DESC, order1.getDirection());
        assertNotNull(order2);
        assertEquals(Sort.Direction.ASC, order2.getDirection());
    }

    @Test
    void testCreatePageable_DisallowedField_Throws400() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 20, "passwordHash,asc", null, ALLOWED_FIELDS)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertNotNull(details);
        assertEquals("sort", details.get(0).field());
    }

    @Test
    void testCreatePageable_EmptyAllowedSortFields_Throws400WhenSortProvided() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 20, "name,asc", null, Collections.emptySet())
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("sort", details.get(0).field());
    }

    @Test
    void testCreatePageable_NullAllowedSortFields_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
            PageUtils.createPageable(0, 20, "name,asc", null, null)
        );
    }

    @Test
    void testCreatePageable_InvalidDirection_Throws400() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 20, "name,abc", null, ALLOWED_FIELDS)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("sort", details.get(0).field());
    }

    @Test
    void testCreatePageable_TooManySortParts_Throws400() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 20, "name,asc,x", null, ALLOWED_FIELDS)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("sort", details.get(0).field());
    }

    @Test
    void testCreatePageable_PageNegative_ThrowsValidationExceptionWithDetails() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(-1, 20, (String) null, null, ALLOWED_FIELDS)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("page", details.get(0).field());
    }

    @Test
    void testCreatePageable_SizeOver100_ThrowsValidationExceptionWithDetails() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 101, (String) null, null, ALLOWED_FIELDS)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        List<ValidationErrorDetail> details = getDetails(ex);
        assertEquals("size", details.get(0).field());
    }
}
