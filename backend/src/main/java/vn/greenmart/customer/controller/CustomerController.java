package vn.greenmart.customer.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.util.Collections;
import java.util.Set;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private static final Set<String> ALLOWED_CUSTOMER_SORT_FIELDS = Set.of("id", "name", "phone", "createdAt");

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CustomerResponse>> listCustomers(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "phone", required = false) String phone,
        @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "size", required = false) Integer size,
        HttpServletRequest request
    ) {
        String[] sort = request.getParameterValues("sort");
        Pageable pageable = PageUtils.createPageable(
            page,
            size,
            sort,
            Sort.by(Sort.Direction.DESC, "createdAt"),
            ALLOWED_CUSTOMER_SORT_FIELDS
        );
        return ResponseEntity.ok(customerService.listCustomers(q, phone, pageable));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.createCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable("customerId") Long customerId) {
        return ResponseEntity.ok(customerService.getCustomer(customerId));
    }

    @PutMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> updateCustomer(
        @PathVariable("customerId") Long customerId,
        @Valid @RequestBody CustomerRequest request
    ) {
        return ResponseEntity.ok(customerService.updateCustomer(customerId, request));
    }

    @GetMapping("/{customerId}/orders")
    public ResponseEntity<PageResponse<OrderSummaryResponse>> listCustomerOrders(
        @PathVariable("customerId") Long customerId,
        @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "size", required = false) Integer size
    ) {
        Pageable pageable = PageUtils.createPageable(
            page,
            size,
            (String[]) null,
            Sort.by(Sort.Direction.DESC, "createdAt"),
            Collections.emptySet()
        );
        return ResponseEntity.ok(customerService.listCustomerOrders(customerId, pageable));
    }
}
