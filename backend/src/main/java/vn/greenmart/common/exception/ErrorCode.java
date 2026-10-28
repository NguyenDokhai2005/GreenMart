package vn.greenmart.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Tài nguyên không tồn tại"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Số lượng tồn kho không đủ"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Yêu cầu đang được xử lý hoặc đã hoàn tất"),
    TOTAL_CHANGED(HttpStatus.UNPROCESSABLE_ENTITY, "Tổng giá trị đơn hàng đã thay đổi"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi hệ thống xảy ra");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
