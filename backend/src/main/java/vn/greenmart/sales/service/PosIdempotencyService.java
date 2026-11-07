package vn.greenmart.sales.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.sales.dto.IdempotencyRecord;
import vn.greenmart.sales.dto.IdempotencyResult;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.PosOrderRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Slf4j
public class PosIdempotencyService {

    private static final String KEY_PREFIX = "idempotency:pos:";
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TTL_24_HOURS = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PosIdempotencyService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public IdempotencyResult execute(
            String idempotencyKey,
            PosOrderRequest request,
            Supplier<OrderResponse> action
    ) {
        validateIdempotencyKey(idempotencyKey);

        // Trường hợp chạy Integration Test không có container Redis
        if (redisTemplate == null) {
            log.warn("StringRedisTemplate không khả dụng, bỏ qua kiểm tra Idempotency");
            OrderResponse response = action.get();
            return new IdempotencyResult(true, response);
        }

        String requestHash = computeSha256(request);
        String redisKey = KEY_PREFIX + idempotencyKey;

        // Payload trạng thái đang xử lý
        String processingJson;
        try {
            processingJson = objectMapper.writeValueAsString(
                    new IdempotencyRecord("PROCESSING", requestHash, null)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Lỗi serialize processing record", e);
        }

        // KỊCH BẢN 4: Thử lấy lock nguyên tử bằng SET NX PX 30s
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, processingJson, LOCK_TIMEOUT);

        if (Boolean.TRUE.equals(acquired)) {
            // KỊCH BẢN 1: Request lần đầu tiên
            OrderResponse response;
            try {
                response = action.get();
            } catch (RuntimeException ex) {
                // Xảy ra lỗi nghiệp vụ (tồn kho, KM, thẻ...) -> Giải phóng lock ngay để lần sau thử lại
                redisTemplate.delete(redisKey);
                throw ex;
            } catch (Exception ex) {
                redisTemplate.delete(redisKey);
                throw new RuntimeException("Lỗi trong quá trình tạo đơn", ex);
            }

            try {
                IdempotencyRecord completedRecord = new IdempotencyRecord("COMPLETED", requestHash, response);
                String completedJson = objectMapper.writeValueAsString(completedRecord);

                // Lưu kết quả hoàn tất với TTL 24 giờ
                redisTemplate.opsForValue().set(redisKey, completedJson, TTL_24_HOURS);

                return new IdempotencyResult(true, response);
            } catch (JsonProcessingException e) {
                redisTemplate.delete(redisKey);
                throw new RuntimeException("Lỗi serialize idempotency completed record", e);
            }
        }

        // Key đã tồn tại -> Kiểm tra giá trị đã lưu
        String existingJson = redisTemplate.opsForValue().get(redisKey);
        if (existingJson == null) {
            throw new AppException(ErrorCode.IDEMPOTENCY_CONFLICT, "Yêu cầu trước chưa xử lý xong hoặc xung đột khóa");
        }

        IdempotencyRecord existingRecord;
        try {
            existingRecord = objectMapper.readValue(existingJson, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Lỗi deserialize idempotency record", e);
        }

        // Đang xử lý bởi luồng khác (Kịch bản 4: gửi song song)
        if ("PROCESSING".equals(existingRecord.status())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng yêu cầu trước chưa xử lý xong"
            );
        }

        // KỊCH BẢN 3: Gửi lại cùng Key nhưng KHÁC body
        if (!requestHash.equals(existingRecord.requestHash())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng nội dung khác với giao dịch trước đó"
            );
        }

        // KỊCH BẢN 2: Gửi lại cùng Key + CÙNG body -> Trả đơn cũ với HTTP 200
        return new IdempotencyResult(false, existingRecord.response());
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key không được để trống");
        }
        try {
            UUID parsed = UUID.fromString(key);
            if (!key.equalsIgnoreCase(parsed.toString())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key không đúng định dạng UUID");
        }
    }

    private String computeSha256(PosOrderRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi tính hash request body", e);
        }
    }
}
