package vn.greenmart.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class PageUtils {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MIN_SIZE = 1;

    private PageUtils() {}

    // Tạo Pageable có kiểm tra danh sách trắng các trường được phép sắp xếp (whitelist).
    public static Pageable createPageable(
            Integer page,
            Integer size,
            List<String> sortParams,
            Sort defaultSort,
            Set<String> allowedSortFields) {

        int targetPage = (page != null) ? page : DEFAULT_PAGE;
        int targetSize = (size != null) ? size : DEFAULT_SIZE;

        validatePageAndSize(targetPage, targetSize);

        Sort sort = parseSorts(sortParams, defaultSort, allowedSortFields);
        return PageRequest.of(targetPage, targetSize, sort);
    }

    // Overload cho trường hợp truyền 1 chuỗi sortParam.
    public static Pageable createPageable(
            Integer page,
            Integer size,
            String sortParam,
            Sort defaultSort,
            Set<String> allowedSortFields) {

        List<String> sortParams = (sortParam != null && !sortParam.trim().isEmpty())
                ? List.of(sortParam)
                : Collections.emptyList();
        return createPageable(page, size, sortParams, defaultSort, allowedSortFields);
    }

    // Overload không giới hạn trường sort
    public static Pageable createPageable(Integer page, Integer size, String sortParam, Sort defaultSort) {
        return createPageable(page, size, sortParam, defaultSort, null);
    }

    public static Pageable createPageable(Integer page, Integer size, String sortParam) {
        return createPageable(page, size, sortParam, Sort.unsorted(), null);
    }

    private static void validatePageAndSize(int page, int size) {
        if (page < 0) {
            throw new AppException(
                ErrorCode.VALIDATION_ERROR,
                "Số trang (page) phải bắt đầu từ 0",
                List.of(new ValidationErrorDetail("page", "Phải lớn hơn hoặc bằng 0"))
            );
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new AppException(
                ErrorCode.VALIDATION_ERROR,
                "Kích thước trang (size) phải từ 1 đến 100",
                List.of(new ValidationErrorDetail("size", "Từ 1 đến 100"))
            );
        }
    }

    // Phân giải danh sách chuỗi sort: "field" hoặc "field,asc|desc".
    public static Sort parseSorts(List<String> sortParams, Sort fallbackSort, Set<String> allowedSortFields) {
        if (sortParams == null || sortParams.isEmpty()) {
            return fallbackSort != null ? fallbackSort : Sort.unsorted();
        }

        List<Sort.Order> orders = new ArrayList<>();
        for (String param : sortParams) {
            if (param == null || param.trim().isEmpty()) {
                continue;
            }

            String[] parts = param.trim().split(",");
            if (parts.length > 2) {
                throw new AppException(
                    ErrorCode.VALIDATION_ERROR,
                    "Định dạng sắp xếp không hợp lệ: " + param,
                    List.of(new ValidationErrorDetail("sort", "Chỉ chấp nhận định dạng 'field' hoặc 'field,asc|desc'"))
                );
            }

            String property = parts[0].trim();
            if (property.isEmpty()) {
                throw new AppException(
                    ErrorCode.VALIDATION_ERROR,
                    "Tên trường sắp xếp không được để trống",
                    List.of(new ValidationErrorDetail("sort", "Tên trường không được để trống"))
                );
            }

            if (allowedSortFields != null && !allowedSortFields.isEmpty() && !allowedSortFields.contains(property)) {
                throw new AppException(
                    ErrorCode.VALIDATION_ERROR,
                    "Trường sắp xếp không được phép: " + property,
                    List.of(new ValidationErrorDetail("sort", "Trường sắp xếp không hợp lệ hoặc không được phép: " + property))
                );
            }

            Sort.Direction direction = Sort.Direction.ASC;
            if (parts.length == 2) {
                String dirStr = parts[1].trim();
                if ("asc".equalsIgnoreCase(dirStr)) {
                    direction = Sort.Direction.ASC;
                } else if ("desc".equalsIgnoreCase(dirStr)) {
                    direction = Sort.Direction.DESC;
                } else {
                    throw new AppException(
                        ErrorCode.VALIDATION_ERROR,
                        "Chiều sắp xếp không hợp lệ: " + dirStr,
                        List.of(new ValidationErrorDetail("sort", "Chiều sắp xếp phải là 'asc' hoặc 'desc'"))
                    );
                }
            }

            orders.add(new Sort.Order(direction, property));
        }

        return orders.isEmpty() ? (fallbackSort != null ? fallbackSort : Sort.unsorted()) : Sort.by(orders);
    }
}
