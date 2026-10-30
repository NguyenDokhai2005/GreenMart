package vn.greenmart.common.pagination;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageUtilsTest {

    @Test
    void testCreatePageable_DefaultValues() {
        Pageable pageable = PageUtils.createPageable(null, null, null);

        assertEquals(PageUtils.DEFAULT_PAGE, pageable.getPageNumber());
        assertEquals(PageUtils.DEFAULT_SIZE, pageable.getPageSize());
        assertTrue(pageable.getSort().isUnsorted());
    }

    @Test
    void testCreatePageable_CustomValuesAndSortDesc() {
        Pageable pageable = PageUtils.createPageable(2, 50, "createdAt,desc");

        assertEquals(2, pageable.getPageNumber());
        assertEquals(50, pageable.getPageSize());
        Sort.Order order = pageable.getSort().getOrderFor("createdAt");
        assertNotNull(order);
        assertEquals(Sort.Direction.DESC, order.getDirection());
    }

    @Test
    void testCreatePageable_SortAsc() {
        Pageable pageable = PageUtils.createPageable(0, 10, "salePrice,asc");

        Sort.Order order = pageable.getSort().getOrderFor("salePrice");
        assertNotNull(order);
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void testCreatePageable_PageNegative_ThrowsValidationException() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(-1, 20, null)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        assertTrue(ex.getMessage().contains("page"));
    }

    @Test
    void testCreatePageable_SizeOver100_ThrowsValidationException() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 101, null)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        assertTrue(ex.getMessage().contains("size"));
    }

    @Test
    void testCreatePageable_SizeZeroOrNegative_ThrowsValidationException() {
        AppException ex = assertThrows(AppException.class, () ->
            PageUtils.createPageable(0, 0, null)
        );

        assertEquals(ErrorCode.VALIDATION_ERROR.name(), ex.getCode());
        assertTrue(ex.getMessage().contains("size"));
    }
}
