package vn.greenmart.sales.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.IdempotencyRecord;
import vn.greenmart.sales.dto.IdempotencyResult;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.PosOrderRequest;
import vn.greenmart.sales.repository.SalesOrderRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
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
    private final SalesOrderRepository salesOrderRepository;
    private final PosOrderService posOrderService;
    private final JdbcTemplate jdbcTemplate;

    public IdempotencyResult execute(
            String idempotencyKey,
            PosOrderRequest request,
            Supplier<OrderResponse> action
    ) {
        validateIdempotencyKey(idempotencyKey);

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

        Boolean acquired = null;
        boolean redisAvailable = true;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, processingJson, LOCK_TIMEOUT);
        } catch (DataAccessException ex) {
            redisAvailable = false;
            log.warn("Redis không khả dụng khi khóa idempotency key={}, chuyển sang kiểm tra DB: {}", idempotencyKey, ex.getMessage());
        }

        if (!redisAvailable || Boolean.TRUE.equals(acquired)) {
            Optional<IdempotencyResult> existingFromDb = findExistingOrderInDb(currentUserId, idempotencyKey, requestHash);
            if (existingFromDb.isPresent()) {
                cacheCompletedSilently(redisKey, requestHash, existingFromDb.get().response());
                return existingFromDb.get();
            }

            OrderResponse response;
            try {
                response = action.get();
                saveRequestHashToDb(response.id(), requestHash);
            } catch (DataIntegrityViolationException dive) {
                deleteRedisKeySilently(redisKey);
                Optional<IdempotencyResult> concurrentOrder = findExistingOrderInDb(currentUserId, idempotencyKey, requestHash);
                if (concurrentOrder.isPresent()) {
                    cacheCompletedSilently(redisKey, requestHash, concurrentOrder.get().response());
                    return concurrentOrder.get();
                }
                throw dive;
            } catch (Exception ex) {
                deleteRedisKeySilently(redisKey);
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Lỗi trong quá trình tạo đơn", ex);
            }

            cacheCompletedSilently(redisKey, requestHash, response);
            return new IdempotencyResult(true, response);
        }

        String existingJson = null;
        try {
            existingJson = redisTemplate.opsForValue().get(redisKey);
        } catch (DataAccessException ex) {
            log.warn("Redis không khả dụng khi đọc idempotency key={}, chuyển sang kiểm tra DB: {}", idempotencyKey, ex.getMessage());
            Optional<IdempotencyResult> existingFromDb = findExistingOrderInDb(currentUserId, idempotencyKey, requestHash);
            if (existingFromDb.isPresent()) {
                return existingFromDb.get();
            }
            throw new AppException(ErrorCode.IDEMPOTENCY_CONFLICT, "Yêu cầu trước chưa xử lý xong hoặc xung đột khóa");
        }

        if (existingJson == null) {
            Optional<IdempotencyResult> existingFromDb = findExistingOrderInDb(currentUserId, idempotencyKey, requestHash);
            if (existingFromDb.isPresent()) {
                cacheCompletedSilently(redisKey, requestHash, existingFromDb.get().response());
                return existingFromDb.get();
            }
            throw new AppException(ErrorCode.IDEMPOTENCY_CONFLICT, "Yêu cầu trước chưa xử lý xong hoặc xung đột khóa");
        }

        IdempotencyRecord existingRecord;
        try {
            existingRecord = objectMapper.readValue(existingJson, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Lỗi deserialize idempotency record", e);
        }

        if ("PROCESSING".equals(existingRecord.status())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng yêu cầu trước chưa xử lý xong"
            );
        }

        if (!requestHash.equals(existingRecord.requestHash())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng nội dung khác với giao dịch trước đó"
            );
        }

        return new IdempotencyResult(false, existingRecord.response());
    }

    private Optional<IdempotencyResult> findExistingOrderInDb(Long userId, String idempotencyKey, String requestHash) {
        if (userId == null) {
            return Optional.empty();
        }
        Optional<SalesOrder> existingOpt = salesOrderRepository.findByCreatedByAndIdempotencyKey(userId, idempotencyKey);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        SalesOrder existingOrder = existingOpt.get();
        if (existingOrder.getCreatedAt() != null
                && existingOrder.getCreatedAt().isBefore(Instant.now().minus(TTL_24_HOURS))) {
            return Optional.empty();
        }
        if (existingOrder.getRequestHash() != null && !requestHash.equals(existingOrder.getRequestHash())) {
            throw new AppException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Cùng Idempotency-Key nhưng nội dung khác với giao dịch trước đó"
            );
        }
        OrderResponse orderResponse = posOrderService.getOrder(existingOrder.getId());
        return Optional.of(new IdempotencyResult(false, orderResponse));
    }

    private void saveRequestHashToDb(Long orderId, String requestHash) {
        if (orderId == null || requestHash == null) {
            return;
        }
        try {
            jdbcTemplate.update("UPDATE sales_order SET request_hash = ? WHERE id = ?", requestHash, orderId);
        } catch (Exception e) {
            log.warn("Không thể lưu request_hash cho đơn id={}: {}", orderId, e.getMessage());
        }
    }

    private void cacheCompletedSilently(String redisKey, String requestHash, OrderResponse response) {
        try {
            IdempotencyRecord completedRecord = new IdempotencyRecord("COMPLETED", requestHash, response);
            String completedJson = objectMapper.writeValueAsString(completedRecord);
            redisTemplate.opsForValue().set(redisKey, completedJson, TTL_24_HOURS);
        } catch (Exception e) {
            log.error("Lỗi lưu trạng thái COMPLETED vào Redis cho đơn id={}: {}", response.id(), e.getMessage(), e);
        }
    }

    private void deleteRedisKeySilently(String redisKey) {
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("Không thể xóa khóa Redis {}: {}", redisKey, e.getMessage());
        }
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
