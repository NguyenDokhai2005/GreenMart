package vn.greenmart.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // bắt lỗi validation
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<ValidationErrorDetail> errorDetails = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> new ValidationErrorDetail(error.getField(), error.getDefaultMessage()))
            .collect(Collectors.toList());

        ErrorResponse response = new ErrorResponse(
            "VALIDATION_ERROR",
            "Dữ liệu không hợp lệ",
            errorDetails,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // lỗi AppException do nghiệp vụ ném ra
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex) {
        ErrorResponse response = new ErrorResponse(
            ex.getCode(),
            ex.getMessage(),
            ex.getDetails(),
            generateTraceId()
        );
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    // các lỗi khác
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        ErrorResponse response = new ErrorResponse(
            "INTERNAL_SERVER_ERROR",
            "Đã có lỗi hệ thống xảy ra, vui lòng thử lại sau",
            null,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public record ValidationErrorDetail(String field, String message) {}
}
