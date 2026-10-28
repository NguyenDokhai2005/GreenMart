package vn.greenmart.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Validation lỗi DTO Body
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.add(new ValidationErrorDetail(error.getField(), error.getDefaultMessage()));
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            details.add(new ValidationErrorDetail(error.getObjectName(), error.getDefaultMessage()));
        }

        ErrorResponse response = new ErrorResponse(
            ErrorCode.VALIDATION_ERROR.name(),
            "Dữ liệu không hợp lệ",
            details,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //  Validation trên @RequestParam, @PathVariable
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ValidationErrorDetail> details = ex.getConstraintViolations().stream()
            .map(cv -> new ValidationErrorDetail(cv.getPropertyPath().toString(), cv.getMessage()))
            .toList();

        ErrorResponse response = new ErrorResponse(
            ErrorCode.VALIDATION_ERROR.name(),
            "Dữ liệu không hợp lệ",
            details,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // JSON hỏng cú pháp / sai kiểu dữ liệu enum
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ErrorResponse response = new ErrorResponse(
            ErrorCode.VALIDATION_ERROR.name(),
            "Định dạng JSON gửi lên không hợp lệ hoặc sai kiểu dữ liệu",
            null,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Sai kiểu dữ liệu trên URL
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName();
        String message = "Giá trị không đúng định dạng cho tham số: " + field;
        List<ValidationErrorDetail> details = List.of(new ValidationErrorDetail(field, message));

        ErrorResponse response = new ErrorResponse(
            ErrorCode.VALIDATION_ERROR.name(),
            "Tham số yêu cầu không hợp lệ",
            details,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Thiếu tham số bắt buộc hoặc thiếu header
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleMissingParamsOrHeaders(Exception ex) {
        ErrorResponse response = new ErrorResponse(
            ErrorCode.VALIDATION_ERROR.name(),
            ex.getMessage(),
            null,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //  URL không tồn tại -> 404 NOT_FOUND
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        ErrorResponse response = new ErrorResponse(
            ErrorCode.NOT_FOUND.name(),
            "Đường dẫn không tồn tại: " + ex.getResourcePath(),
            null,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    // Sai HTTP Method -> 405
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ErrorResponse response = new ErrorResponse(
            "METHOD_NOT_ALLOWED",
            "Phương thức " + ex.getMethod() + " không được hỗ trợ cho tài nguyên này",
            null,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    // Lỗi xung đột Database / Optimistic lock -> 409 CONFLICT
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseConflict(Exception ex) {
        ErrorResponse response = new ErrorResponse(
            "CONFLICT",
            "Dữ liệu bị xung đột hoặc đã bị thay đổi bởi giao dịch khác",
            null,
            generateTraceId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    // Lỗi nghiệp vụ chủ động ném ra
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

    // Lỗi hệ thống không mong muốn
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        String traceId = generateTraceId();
        log.error("Unhandled error traceId={}", traceId, ex);

        ErrorResponse response = new ErrorResponse(
            ErrorCode.INTERNAL_SERVER_ERROR.name(),
            "Đã có lỗi hệ thống xảy ra, vui lòng thử lại sau",
            null,
            traceId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public record ValidationErrorDetail(String field, String message) {}
}
