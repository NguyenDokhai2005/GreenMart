package vn.greenmart.sales.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
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
@RequiredArgsConstructor
@Slf4j
public class PosIdempotencyService {

    private static final String KEY_PREFIX = "idempotency:pos:";
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TTL_24_HOURS = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyResult execute(
            String idempotencyKey,
            PosOrderRequest request,
            Supplier<OrderResponse> action
    ) {
        validateIdempotencyKey(idempotencyKey);

        // Gắn userId vào Redis key: idempotency:pos:{userId}:{key}
        Long currentUserId = SecurityUtils.getCurrentUserId();
        String redisKey = KEY_PREFIX + currentUserId + ":" + idempotencyKey;
        String requestHash = computeSha256(request);

        String processingJson;
        try {
            processingJson = objectMapper.writeValueAsString(
                    new IdempotencyRecord("PROCESSING", requestHash, null)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Lỗi serialize processing record", e);
        }

        // SET NX PX 30000 để khóa request song song
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, processingJson, LOCK_TIMEOUT);

        if (Boolean.TRUE.equals(acquired)) {
            OrderResponse response;
            try {
                response = action.get();
            } catch (Exception ex) {
                redisTemplate.delete(redisKey);
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Lỗi trong quá trình tạo đơn", ex);
            }

            try {
                IdempotencyRecord completedRecord = new IdempotencyRecord("COMPLETED", requestHash, response);
                String completedJson = objectMapper.writeValueAsString(completedRecord);
                redisTemplate.opsForValue().set(redisKey, completedJson, TTL_24_HOURS);
            } catch (Exception e) {
                log.error("Lỗi lưu trạng thái COMPLETED vào Redis cho đơn orderId={}: {}", response.id(), e.getMessage(), e);
            }

            return new IdempotencyResult(true, response);
        }

        // Key đã tồn tại
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

        // Request trước đang PROCESSING
        if ("PROCESSING".equals(existingRecord.status())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng yêu cầu trước chưa xử lý xong"
            );
        }

        // Gửi lại cùng Key nhưng khác body
        if (!requestHash.equals(existingRecord.requestHash())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng nội dung khác với giao dịch trước đó"
            );
        }

        // Gửi lại cùng Key + cùng body
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
