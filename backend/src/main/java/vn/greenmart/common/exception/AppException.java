package vn.greenmart.common.exception;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Object details;
    private final Long deniedBranchId;

    public static AppException branchForbidden(Long branchId) {
        return branchForbidden(branchId, ErrorCode.BRANCH_FORBIDDEN.getDefaultMessage());
    }

    public static AppException branchForbidden(Long branchId, String message) {
        return new AppException(ErrorCode.BRANCH_FORBIDDEN, message, null, branchId);
    }

    public AppException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public AppException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public AppException(ErrorCode errorCode, String message, Object details) {
        this(errorCode, message, details, null);
    }

    private AppException(ErrorCode errorCode, String message, Object details, Long deniedBranchId) {
        super(message);
        this.status = errorCode.getHttpStatus();
        this.code = errorCode.name();
        this.details = details;
        this.deniedBranchId = deniedBranchId;
    }

    public Long getDeniedBranchId() {
        return deniedBranchId;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
