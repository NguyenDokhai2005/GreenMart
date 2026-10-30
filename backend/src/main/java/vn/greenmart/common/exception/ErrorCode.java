package vn.greenmart.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // 400
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),

    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn hoặc không hợp lệ"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu"),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "Tài khoản tạm khóa do đăng nhập sai nhiều lần"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token không hợp lệ hoặc đã hết hạn"),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    BRANCH_FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không được thao tác trên chi nhánh này"),

    // 404
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên"),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức HTTP không được hỗ trợ"),

    // 409
    CONFLICT(HttpStatus.CONFLICT, "Dữ liệu bị xung đột hoặc đã bị thay đổi bởi giao dịch khác"),
    DUPLICATE_SKU(HttpStatus.CONFLICT, "Mã SKU đã tồn tại"),
    DUPLICATE_BARCODE(HttpStatus.CONFLICT, "Mã vạch barcode đã tồn tại"),
    DUPLICATE_PHONE(HttpStatus.CONFLICT, "Số điện thoại đã thuộc khách hàng khác"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Số lượng tồn kho không đủ"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Yêu cầu đang được xử lý hoặc trùng lặp với nội dung khác"),
    CATEGORY_IN_USE(HttpStatus.CONFLICT, "Danh mục đang có sản phẩm hoặc danh mục con"),

    // 422
    PRODUCT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Sản phẩm đã ngừng kinh doanh"),
    TOTAL_CHANGED(HttpStatus.UNPROCESSABLE_ENTITY, "Tổng giá trị đơn hàng đã thay đổi"),
    CASH_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "Số tiền khách đưa nhỏ hơn tổng tiền phải thanh toán"),
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Thanh toán thất bại"),
    PAYMENT_TIMEOUT(HttpStatus.UNPROCESSABLE_ENTITY, "Thanh toán đã quá thời gian quy định"),
    MANUAL_DISCOUNT_FORBIDDEN(HttpStatus.UNPROCESSABLE_ENTITY, "Không có quyền giảm giá thủ công hoặc thiếu lý do"),
    CATEGORY_CYCLE(HttpStatus.UNPROCESSABLE_ENTITY, "Danh mục cha tạo vòng lặp không hợp lệ"),
    WRONG_PASSWORD(HttpStatus.UNPROCESSABLE_ENTITY, "Mật khẩu hiện tại không chính xác"),

    // 500
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi hệ thống xảy ra, vui lòng thử lại sau");

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
