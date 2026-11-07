package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.domain.PaymentStatus;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PaymentResponse;
import vn.greenmart.sales.repository.PaymentRepository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;

    @Override
    public void validatePayment(Long totalAmount, PaymentRequest request) {
        if (request == null || request.method() == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Phương thức thanh toán không được để trống");
        }

        if (request.method() == PaymentMethod.CASH) {
            Long cashReceived = request.cashReceived();
            if (cashReceived == null || cashReceived < totalAmount) {
                throw new AppException(
                        ErrorCode.CASH_INSUFFICIENT,
                        "Tiền khách đưa nhỏ hơn tổng phải trả",
                        Map.of("total", totalAmount, "cashReceived", cashReceived != null ? cashReceived : 0L)
                );
            }
        } else {
            // Thanh toán giả lập qua CARD hoặc QR
            PaymentSimulation sim = request.simulate();
            if (sim == PaymentSimulation.FAILED) {
                throw new AppException(
                        ErrorCode.PAYMENT_FAILED,
                        "Thanh toán thẻ/QR không thành công, vui lòng chọn phương thức khác"
                );
            }
            if (sim == PaymentSimulation.TIMEOUT) {
                throw new AppException(
                        ErrorCode.PAYMENT_TIMEOUT,
                        "Thanh toán quá thời gian chờ (60s)"
                );
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentResponse processPayment(Long orderId, Long totalAmount, PaymentRequest request) {
        validatePayment(totalAmount, request);

        Long cashReceived = null;
        Long change = null;
        String providerRef = null;

        if (request.method() == PaymentMethod.CASH) {
            cashReceived = request.cashReceived();
            change = cashReceived - totalAmount;
        } else {
            providerRef = request.method().name() + "-SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }

        Instant now = Instant.now();
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setMethod(request.method());
        payment.setAmount(totalAmount);
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderRef(providerRef);
        payment.setPaidAt(now);

        payment = paymentRepository.save(payment);

        return new PaymentResponse(
                payment.getId(),
                payment.getMethod(),
                payment.getAmount(),
                payment.getStatus(),
                cashReceived,
                change,
                payment.getProviderRef(),
                payment.getPaidAt()
        );
    }
}
