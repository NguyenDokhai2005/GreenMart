package vn.greenmart.customer.service;

import org.springframework.data.domain.Pageable;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.sales.dto.OrderSummaryResponse;

public interface CustomerService {
    long resolveChannelCustomer(String phone, String name);

    CustomerResponse createCustomer(CustomerRequest request);

    CustomerResponse deactivateCustomer(Long customerId);

    CustomerResponse updateCustomer(Long customerId, CustomerRequest request);

    CustomerResponse getCustomer(Long customerId);

    PageResponse<CustomerResponse> listCustomers(String q, String phone, Pageable pageable);

    PageResponse<CustomerResponse> listCustomers(String q, String phone, Boolean active, Pageable pageable);

    PageResponse<OrderSummaryResponse> listCustomerOrders(Long customerId, Pageable pageable);

    boolean exists(Long customerId);

    void validatePosCustomer(Long customerId);

    void lockActivePosCustomer(Long customerId);
}
