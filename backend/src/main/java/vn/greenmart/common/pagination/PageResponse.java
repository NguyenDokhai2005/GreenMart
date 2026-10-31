package vn.greenmart.common.pagination;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> springPage) {
        return new PageResponse<>(
            springPage.getContent(),
            springPage.getNumber(),
            springPage.getSize(),
            springPage.getTotalElements(),
            springPage.getTotalPages()
        );
    }

    public static <T, R> PageResponse<T> of(List<T> content, Page<R> originalPage) {
        return new PageResponse<>(
            content,
            originalPage.getNumber(),
            originalPage.getSize(),
            originalPage.getTotalElements(),
            originalPage.getTotalPages()
        );
    }
}
