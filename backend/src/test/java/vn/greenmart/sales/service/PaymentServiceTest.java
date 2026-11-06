package vn.greenmart.sales.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentStatus;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PaymentResponse;
import vn.greenmart.sales.repository.PaymentRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService - Unit Tests (PAY-01)")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Test
    @DisplayName("TC-POS-01: Tiền mặt đủ -> Thành công, tính đúng tiền thối, status PAID")
    void testProcessPayment_Cash_Success() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(101L);
            return p;
        });

        PaymentRequest req = new PaymentRequest(PaymentMethod.CASH, 250000L, "SUCCESS");
        PaymentResponse res = paymentService.processPayment(500L, 200700L, req);

        assertThat(res.id()).isEqualTo(101L);
        assertThat(res.method()).isEqualTo(PaymentMethod.CASH);
        assertThat(res.amount()).isEqualTo(200700L);
        assertThat(res.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(res.cashReceived()).isEqualTo(250000L);
        assertThat(res.change()).isEqualTo(49300L);
        assertThat(res.providerRef()).isNull();

        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("TC-POS-08: Tiền mặt đưa thiếu -> 422 CASH_INSUFFICIENT, mang details total và cashReceived")
    void testProcessPayment_Cash_Insufficient_Throws422() {
        PaymentRequest req = new PaymentRequest(PaymentMethod.CASH, 200000L, "SUCCESS");

        assertThatThrownBy(() -> paymentService.processPayment(500L, 200700L, req))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(ErrorCode.CASH_INSUFFICIENT.name());

                    assertThat(appEx.getDetails()).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) appEx.getDetails();
                    assertThat(details.get("total")).isEqualTo(200700L);
                    assertThat(details.get("cashReceived")).isEqualTo(200000L);
                });

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-POS-06: Thẻ simulate = FAILED -> 422 PAYMENT_FAILED, không lưu DB")
    void testProcessPayment_Card_Failed_Throws422() {
        PaymentRequest req = new PaymentRequest(PaymentMethod.CARD, null, "FAILED");

        assertThatThrownBy(() -> paymentService.processPayment(500L, 200700L, req))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.PAYMENT_FAILED.name()));

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-POS-07: QR simulate = TIMEOUT -> 422 PAYMENT_TIMEOUT, không lưu DB")
    void testProcessPayment_Qr_Timeout_Throws422() {
        PaymentRequest req = new PaymentRequest(PaymentMethod.QR, null, "TIMEOUT");

        assertThatThrownBy(() -> paymentService.processPayment(500L, 200700L, req))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getCode().equals(ErrorCode.PAYMENT_TIMEOUT.name()));

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Thẻ/QR simulate = SUCCESS -> Tạo bản ghi PAID và sinh providerRef tiền tố phương thức")
    void testProcessPayment_Card_Success() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(102L);
            return p;
        });

        PaymentRequest req = new PaymentRequest(PaymentMethod.CARD, null, "SUCCESS");
        PaymentResponse res = paymentService.processPayment(500L, 150000L, req);

        assertThat(res.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(res.cashReceived()).isNull();
        assertThat(res.change()).isNull();
        assertThat(res.providerRef()).startsWith("CARD-SIM-");

        verify(paymentRepository).save(any(Payment.class));
    }
}
