package vn.greenmart.customer.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    private static final String INACTIVE_CUSTOMER_MESSAGE =
            "Khách hàng đã ngừng hoạt động. Liên hệ Quản lý hoặc Chủ cửa hàng để kích hoạt lại.";

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
                existing.isActive() ? "Số điện thoại đã thuộc khách hàng khác" : INACTIVE_CUSTOMER_MESSAGE,
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

        Customer customer = customerRepository.findByIdForUpdate(customerId).orElseThrow(() ->
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
        if (request.active() != null) {
            SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");
            if (customer.isActive() != request.active()) {
                recordStatusChange(customerId, request.active());
            }
            customer.setActive(request.active());
        }

        Customer updated = customerRepository.save(customer);
        return CustomerResponse.from(updated);
    }

    @Override
    public CustomerResponse deactivateCustomer(Long customerId) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");

        Customer customer = customerRepository.findByIdForUpdate(customerId).orElseThrow(() ->
            new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng", Map.of("customerId", customerId))
        );

        if (customer.isActive()) recordStatusChange(customerId, false);
        customer.setActive(false);
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
        return listCustomers(q, phone, null, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> listCustomers(String q, String phone, Boolean active, Pageable pageable) {
        ensureAuthenticated();

        boolean targetActive = (active != null) ? active : true;
        if (!targetActive) {
            SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");
        }

        String trimmedQ = (q != null && !q.isBlank()) ? q.trim() : null;
        String trimmedPhone = (phone != null && !phone.isBlank()) ? phone.trim() : null;

        // Exact phone lookup distinguishes inactive customers without exposing their directory.
        if (trimmedPhone != null && targetActive) {
            Customer customer = customerRepository.findByPhone(trimmedPhone).orElse(null);
            if (customer != null && !customer.isActive()) {
                throw new AppException(ErrorCode.CUSTOMER_INACTIVE, INACTIVE_CUSTOMER_MESSAGE);
            }
        }

        Page<Customer> result = customerRepository.searchCustomers(trimmedQ, trimmedPhone, targetActive, pageable);
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

    private void recordStatusChange(Long customerId, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO audit_log(user_id,action,entity,entity_id,detail)
                VALUES (?,?, 'customer', ?, jsonb_build_object('active',CAST(? AS boolean)))
                """, SecurityUtils.getCurrentUserId(), active ? "REACTIVATE_CUSTOMER" : "DEACTIVATE_CUSTOMER",
                customerId.toString(), active);
    }

    @Override
    public boolean exists(Long customerId) {
        return customerRepository.existsById(customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public void validatePosCustomer(Long customerId) {
        requireActivePosCustomer(customerId, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActivePosCustomer(Long customerId) {
        // Held until POS commits: deactivation cannot race between validation and order creation.
        requireActivePosCustomer(customerId, true);
    }

    private void requireActivePosCustomer(Long customerId, boolean lock) {
        if (customerId == null) return;
        var states = jdbcTemplate.queryForList("SELECT is_active FROM customer WHERE id=?"
                + (lock ? " FOR SHARE" : ""), Boolean.class, customerId);
        if (states.isEmpty()) throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng");
        if (!Boolean.TRUE.equals(states.getFirst())) {
            throw new AppException(ErrorCode.CUSTOMER_INACTIVE, INACTIVE_CUSTOMER_MESSAGE);
        }
    }
}
