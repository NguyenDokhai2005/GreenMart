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
import org.springframework.jdbc.core.JdbcTemplate;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.repository.SalesOrderRepository;

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
@DisplayName("PosIdempotencyService - Unit Tests (Issue #34 & #515)")
class PosIdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Supplier<OrderResponse> action;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private PosOrderService posOrderService;

    @Mock
    private JdbcTemplate jdbcTemplate;

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
        securityUtilsMock.when(() -> SecurityUtils.getAccessibleBranchIds(jdbcTemplate)).thenReturn(List.of(1L));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new PosIdempotencyService(
                redisTemplate, objectMapper, salesOrderRepository, posOrderService, jdbcTemplate);

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

    @Test
    @DisplayName("Kịch bản A (#313): Redis lỗi không lưu được COMPLETED -> Lần gửi lại đọc từ DB và trả đơn cũ")
    void testExecute_RedisFailureOnSave_FallbackToDbReturnsExistingOrder() {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(action.get()).thenReturn(mockResponse);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis down"))
                .when(valueOperations).set(eq(expectedRedisKey), anyString(), eq(Duration.ofHours(24)));

        IdempotencyResult firstResult = idempotencyService.execute(key, defaultRequest, action);
        assertThat(firstResult.isCreated()).isTrue();

        vn.greenmart.sales.domain.SalesOrder existingOrder = new vn.greenmart.sales.domain.SalesOrder();
        existingOrder.setId(500L);
        existingOrder.setBranchId(1L);
        existingOrder.setCreatedBy(1L);
        existingOrder.setIdempotencyKey(key);
        existingOrder.setCreatedAt(Instant.now());

        when(salesOrderRepository.findByCreatedByAndIdempotencyKey(1L, key))
                .thenReturn(java.util.Optional.of(existingOrder));
        when(posOrderService.getOrder(500L)).thenReturn(mockResponse);

        IdempotencyResult retryResult = idempotencyService.execute(key, defaultRequest, action);

        assertThat(retryResult.isCreated()).isFalse();
        assertThat(retryResult.response().id()).isEqualTo(500L);
        verify(action, times(1)).get();
    }

    @Test
    @DisplayName("Kịch bản B (#313): Khóa Redis hết hạn sau 30s và 2 request đụng UNIQUE DB -> Trả đơn cũ, không tạo trùng")
    void testExecute_LockExpiredAfter30s_DbUniqueConstraintReturnsExistingOrder() {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(true);

        vn.greenmart.sales.domain.SalesOrder committedOrder = new vn.greenmart.sales.domain.SalesOrder();
        committedOrder.setId(500L);
        committedOrder.setBranchId(1L);
        committedOrder.setCreatedBy(1L);
        committedOrder.setIdempotencyKey(key);
        committedOrder.setCreatedAt(Instant.now());

        when(salesOrderRepository.findByCreatedByAndIdempotencyKey(1L, key))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(committedOrder));
        when(action.get()).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_sales_order_creator_idempotency"));
        when(posOrderService.getOrder(500L)).thenReturn(mockResponse);

        IdempotencyResult result = idempotencyService.execute(key, defaultRequest, action);

        assertThat(result.isCreated()).isFalse();
        assertThat(result.response().id()).isEqualTo(500L);
    }

    @Test
    @DisplayName("Issue #313 (Review): Gửi lại cùng Idempotency-Key sau 24 giờ -> Ném 409 IDEMPOTENCY_CONFLICT thay vì 500")
    void testExecute_ReuseKeyAfter24Hours_Throws409Conflict() {
        String expectedRedisKey = "idempotency:pos:1:" + key;
        when(valueOperations.setIfAbsent(eq(expectedRedisKey), anyString(), any(Duration.class)))
                .thenReturn(true);

        vn.greenmart.sales.domain.SalesOrder expiredOrder = new vn.greenmart.sales.domain.SalesOrder();
        expiredOrder.setId(500L);
        expiredOrder.setBranchId(1L);
        expiredOrder.setCreatedBy(1L);
        expiredOrder.setIdempotencyKey(key);
        expiredOrder.setCreatedAt(Instant.now().minus(Duration.ofHours(25)));

        when(salesOrderRepository.findByCreatedByAndIdempotencyKey(1L, key))
                .thenReturn(java.util.Optional.of(expiredOrder));

        assertThatThrownBy(() -> idempotencyService.execute(key, defaultRequest, action))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT.name());
                    assertThat(appEx.getMessage()).contains("quá 24 giờ");
                });

        verify(action, never()).get();
    }

    @Test
    @DisplayName("Issue #515: Thu hồi quyền chi nhánh sau khi đã cache Redis -> Retry key cũ ném 403 BRANCH_FORBIDDEN")
    void testExecute_BranchRevokedAfterCache_Throws403BranchForbidden() {
        securityUtilsMock.when(() -> SecurityUtils.getAccessibleBranchIds(jdbcTemplate)).thenReturn(List.of(2L));

        assertThatThrownBy(() -> idempotencyService.execute(key, defaultRequest, action))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.BRANCH_FORBIDDEN.name());
                });

        verify(redisTemplate, never()).opsForValue();
        verify(action, never()).get();
    }

    @Test
    @DisplayName("Issue #515: Đổi sang role không có quyền POS (STOREKEEPER) -> Retry key cũ ném 403 FORBIDDEN")
    void testExecute_RoleRevokedAfterCache_Throws403Forbidden() {
        securityUtilsMock.when(() -> SecurityUtils.requireRole("BRANCH_MANAGER", "CASHIER"))
                .thenThrow(new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"));

        assertThatThrownBy(() -> idempotencyService.execute(key, defaultRequest, action))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.FORBIDDEN.name());
                });

        verify(redisTemplate, never()).opsForValue();
        verify(action, never()).get();
    }
}
