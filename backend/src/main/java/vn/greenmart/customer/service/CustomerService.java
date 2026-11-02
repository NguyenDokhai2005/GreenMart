package vn.greenmart.customer.service;

import org.springframework.data.domain.Pageable;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.sales.dto.OrderSummaryResponse;

public interface CustomerService {

    CustomerResponse createCustomer(CustomerRequest request);

    CustomerResponse updateCustomer(Long customerId, CustomerRequest request);

    CustomerResponse getCustomer(Long customerId);

    PageResponse<CustomerResponse> listCustomers(String q, String phone, Pageable pageable);

    PageResponse<OrderSummaryResponse> listCustomerOrders(Long customerId, Pageable pageable);
}
