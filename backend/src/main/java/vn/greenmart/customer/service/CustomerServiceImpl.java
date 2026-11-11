package vn.greenmart.customer.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.customer.domain.Customer;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.repository.CustomerRepository;
import vn.greenmart.sales.dto.OrderSummaryResponse;
import vn.greenmart.sales.service.OrderQueryService;

import java.util.List;
import java.util.Map;

@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderQueryService orderQueryService;
    private final JdbcTemplate jdbcTemplate;

    public CustomerServiceImpl(CustomerRepository customerRepository,
                               OrderQueryService orderQueryService,
                               JdbcTemplate jdbcTemplate) {
        this.customerRepository = customerRepository;
        this.orderQueryService = orderQueryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long resolveChannelCustomer(String phone, String name) {
        jdbcTemplate.update("INSERT INTO customer(phone, name) VALUES (?, ?) ON CONFLICT (phone) DO NOTHING",
                phone, name.trim());
        return jdbcTemplate.queryForObject("SELECT id FROM customer WHERE phone=?", Long.class, phone);
    }

    @Override
    public CustomerResponse createCustomer(CustomerRequest request) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "CASHIER");

        String normalizedPhone = request.phone().trim();
        customerRepository.findByPhone(normalizedPhone).ifPresent(existing -> {
            throw new AppException(
                ErrorCode.DUPLICATE_PHONE,
                "Số điện thoại đã thuộc khách hàng khác",
                Map.of("phone", normalizedPhone, "customerId", existing.getId())
            );
        });

        String normalizedName = request.name().trim();
        String normalizedEmail = (request.email() != null && !request.email().isBlank())
                ? request.email().trim()
                : null;

        Customer customer = new Customer(normalizedPhone, normalizedName, normalizedEmail);
        Customer saved = customerRepository.save(customer);
        return CustomerResponse.from(saved);
    }

    @Override
    public CustomerResponse updateCustomer(Long customerId, CustomerRequest request) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "CASHIER");

        Customer customer = customerRepository.findById(customerId).orElseThrow(() ->
            new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng", Map.of("customerId", customerId))
        );

        String normalizedPhone = request.phone().trim();
        customerRepository.findByPhone(normalizedPhone).ifPresent(existing -> {
            if (!existing.getId().equals(customerId)) {
                throw new AppException(
                    ErrorCode.DUPLICATE_PHONE,
                    "Số điện thoại đã thuộc khách hàng khác",
                    Map.of("phone", normalizedPhone, "customerId", existing.getId())
                );
            }
        });

        customer.setPhone(normalizedPhone);
        customer.setName(request.name().trim());
        customer.setEmail((request.email() != null && !request.email().isBlank()) ? request.email().trim() : null);

        Customer updated = customerRepository.save(customer);
        return CustomerResponse.from(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(Long customerId) {
        ensureAuthenticated();
        Customer customer = customerRepository.findById(customerId).orElseThrow(() ->
            new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng", Map.of("customerId", customerId))
        );
        return CustomerResponse.from(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> listCustomers(String q, String phone, Pageable pageable) {
        ensureAuthenticated();

        String trimmedQ = (q != null && !q.isBlank()) ? q.trim() : null;
        String trimmedPhone = (phone != null && !phone.isBlank()) ? phone.trim() : null;

        Page<Customer> result = customerRepository.searchCustomers(trimmedQ, trimmedPhone, pageable);
        return PageResponse.from(result.map(CustomerResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listCustomerOrders(Long customerId, Pageable pageable) {
        ensureAuthenticated();

        if (!customerRepository.existsById(customerId)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng", Map.of("customerId", customerId));
        }

        // Lấy danh sách chi nhánh user được phép truy cập qua SecurityUtils (null nếu là ADMIN/OWNER)
        List<Long> accessibleBranchIds = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);

        // Gọi sang module Sales qua interface
        return orderQueryService.findByCustomer(customerId, accessibleBranchIds, pageable);
    }

    private void ensureAuthenticated() {
        if (SecurityUtils.getCurrentRole() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập");
        }
    }

    @Override
    public boolean exists(Long customerId) {
        return customerRepository.existsById(customerId);
    }
}
