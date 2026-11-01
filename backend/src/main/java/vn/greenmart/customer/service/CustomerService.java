package vn.greenmart.customer.service;

import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.dto.OrderSummaryResponse;

public interface CustomerService {

    CustomerResponse createCustomer(CustomerRequest request);

    CustomerResponse updateCustomer(Long customerId, CustomerRequest request);

    CustomerResponse getCustomer(Long customerId);

    PageResponse<CustomerResponse> listCustomers(String q, String phone, int page, int size, String[] sort);

    PageResponse<OrderSummaryResponse> listCustomerOrders(Long customerId, int page, int size);
}
