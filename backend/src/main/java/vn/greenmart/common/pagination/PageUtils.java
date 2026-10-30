package vn.greenmart.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

public final class PageUtils {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MIN_SIZE = 1;

    private PageUtils() {
    }

    // Tạo Pageable từ các query params.

    public static Pageable createPageable(Integer page, Integer size, String sortParam, Sort defaultSort) {
        int targetPage = (page != null) ? page : DEFAULT_PAGE;
        int targetSize = (size != null) ? size : DEFAULT_SIZE;

        validatePageAndSize(targetPage, targetSize);

        Sort sort = parseSort(sortParam, defaultSort);
        return PageRequest.of(targetPage, targetSize, sort);
    }

    //  Tạo Pageable khi không có Sort mặc định.

    public static Pageable createPageable(Integer page, Integer size, String sortParam) {
        return createPageable(page, size, sortParam, Sort.unsorted());
    }

    private static void validatePageAndSize(int page, int size) {
        if (page < 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Số trang (page) phải bắt đầu từ 0");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Kích thước trang (size) phải từ 1 đến 100");
        }
    }

    //  Phân giải chuỗi

    public static Sort parseSort(String sortParam, Sort fallbackSort) {
        if (sortParam == null || sortParam.trim().isEmpty()) {
            return fallbackSort != null ? fallbackSort : Sort.unsorted();
        }

        String[] parts = sortParam.trim().split(",");
        String property = parts[0].trim();
        if (property.isEmpty()) {
            return fallbackSort != null ? fallbackSort : Sort.unsorted();
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.DESC;
        }

        return Sort.by(direction, property);
    }
}
