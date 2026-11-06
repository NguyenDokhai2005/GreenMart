package vn.greenmart.sales.service;

import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PaymentResponse;

public interface PaymentService {
    void validatePayment(Long totalAmount, PaymentRequest request);
    PaymentResponse processPayment(Long orderId, Long totalAmount, PaymentRequest request);
}
