package vn.greenmart.customer.dto;

import vn.greenmart.customer.domain.Customer;

import java.time.Instant;

public record CustomerResponse(
    Long id,
    String phone,
    String name,
    String email,
    Instant createdAt
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getPhone(), c.getName(), c.getEmail(), c.getCreatedAt());
    }
}
