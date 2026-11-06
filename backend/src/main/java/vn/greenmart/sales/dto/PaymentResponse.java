package vn.greenmart.sales.dto;

import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentStatus;

import java.time.Instant;

public record PaymentResponse(
    Long id,
    PaymentMethod method,
    Long amount,
    PaymentStatus status,
    Long cashReceived,
    Long change,
    String providerRef,
    Instant paidAt
) {}
