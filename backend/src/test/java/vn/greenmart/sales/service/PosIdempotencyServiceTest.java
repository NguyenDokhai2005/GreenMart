package vn.greenmart.sales.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PosIdempotencyService - Unit Tests (Issue #34)")
class PosIdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Supplier<OrderResponse> action;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PosIdempotencyService idempotencyService;
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private PosOrderRequest defaultRequest;
    private OrderResponse mockResponse;
    private String key;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new PosIdempotencyService(redisTemplate, objectMapper);

        key = UUID.randomUUID().toString();
        defaultRequest = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 2)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 250000L, PaymentSimulation.SUCCESS),
                200700L
        );

        mockResponse = new OrderResponse(
                500L, "POS-GM001-20261106-0001", "POS", null,
                new BranchRef(1L, "GM-001", "GreenMart Q1"), null,
                OrderStatus.COMPLETED, 2, 200700L,
                Instant.now(), Instant.now(), null,
                Collections.emptyList(), null, null,
                200700L, 0L, 0L, 0L, 0L,
                Collections.emptyList(), null, null, null
        );
    }

    @AfterEach
    void tearDown() {
        if (securityUtilsMock != null) {
            securityUtilsMock.close();
        }
    }

    @Test
    @DisplayName("Kịch bản 1: Request lần đầu thành công -> Key theo userId, đặt TTL 24h và trả isCreated = true")
    void testExecute_FirstTime_Success() {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(action.get()).thenReturn(mockResponse);

        IdempotencyResult result = idempotencyService.execute(key, defaultRequest, action);

        assertThat(result.isCreated()).isTrue();
        assertThat(result.response().id()).isEqualTo(500L);

        verify(valueOperations).set(eq(expectedRedisKey), anyString(), eq(Duration.ofHours(24)));
        verify(action, times(1)).get();
    }

    @Test
    @DisplayName("User khác gửi cùng key -> Được xử lý như request mới")
    void testExecute_DifferentUser_SameKey_ProcessedAsNew() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(2L);
        String user2RedisKey = "idempotency:pos:2:" + key;

        when(valueOperations.setIfAbsent(eq(user2RedisKey), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(action.get()).thenReturn(mockResponse);

        IdempotencyResult result = idempotencyService.execute(key, defaultRequest, action);

        assertThat(result.isCreated()).isTrue();
        verify(valueOperations).set(eq(user2RedisKey), anyString(), eq(Duration.ofHours(24)));
        verify(action, times(1)).get();
    }

    @Test
    @DisplayName("TC-POS-03: Gửi lại cùng Key + CÙNG Body trong 24h -> Trả đơn cũ, isCreated = false, không gọi lại DB")
    void testExecute_DuplicateKey_SameBody_ReturnsCached() throws Exception {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(false);

        IdempotencyRecord savedRecord = new IdempotencyRecord(
                "COMPLETED",
                computeHash(defaultRequest),
                mockResponse
        );
        when(valueOperations.get(expectedRedisKey))
                .thenReturn(objectMapper.writeValueAsString(savedRecord));

        IdempotencyResult result = idempotencyService.execute(key, defaultRequest, action);

        assertThat(result.isCreated()).isFalse();
        assertThat(result.response().code()).isEqualTo("POS-GM001-20261106-0001");

        verify(action, never()).get();
    }

    @Test
    @DisplayName("TC-POS-04: Gửi lại cùng Key nhưng KHÁC Body -> Ném HTTP 409 IDEMPOTENCY_CONFLICT")
    void testExecute_DuplicateKey_DifferentBody_Throws409() throws Exception {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(false);

        IdempotencyRecord savedRecord = new IdempotencyRecord(
                "COMPLETED",
                "different-hash-abc-123",
                mockResponse
        );
        when(valueOperations.get(expectedRedisKey))
                .thenReturn(objectMapper.writeValueAsString(savedRecord));

        assertThatThrownBy(() -> idempotencyService.execute(key, defaultRequest, action))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT.name());
                });

        verify(action, never()).get();
    }

    @Test
    @DisplayName("TC-CONC-01: Yêu cầu song song gửi cùng lúc (PROCESSING) -> Ném HTTP 409 IDEMPOTENCY_CONFLICT")
    void testExecute_ConcurrentRequests_Throws409() throws Exception {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(false);

        IdempotencyRecord processingRecord = new IdempotencyRecord(
                "PROCESSING",
                computeHash(defaultRequest),
                null
        );
        when(valueOperations.get(expectedRedisKey))
                .thenReturn(objectMapper.writeValueAsString(processingRecord));

        assertThatThrownBy(() -> idempotencyService.execute(key, defaultRequest, action))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT.name());
                });

        verify(action, never()).get();
    }

    @Test
    @DisplayName("Định dạng Idempotency-Key không phải UUID -> Ném 400 VALIDATION_ERROR")
    void testExecute_InvalidUuid_Throws400() {
        assertThatThrownBy(() -> idempotencyService.execute("invalid-uuid-string", defaultRequest, action))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.name());
                });
    }

    @Test
    @DisplayName("Lỗi nghiệp vụ khi tạo đơn -> Xóa khóa Redis để cho phép thử lại")
    void testExecute_ActionThrowsException_DeletesKey() {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(action.get()).thenThrow(new AppException(ErrorCode.INSUFFICIENT_STOCK, "Hết hàng"));

        assertThatThrownBy(() -> idempotencyService.execute(key, defaultRequest, action))
                .isInstanceOf(AppException.class);

        verify(redisTemplate).delete(expectedRedisKey);
    }

    private String computeHash(PosOrderRequest request) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(objectMapper.writeValueAsString(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }
}
