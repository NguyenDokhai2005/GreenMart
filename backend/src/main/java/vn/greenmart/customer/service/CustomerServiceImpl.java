package vn.greenmart.customer.service;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.customer.domain.Customer;
import vn.greenmart.customer.dto.BranchRef;
import vn.greenmart.customer.dto.CustomerRef;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.dto.OrderSummaryResponse;
import vn.greenmart.customer.repository.CustomerRepository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "name", "phone", "createdAt");

    private final CustomerRepository customerRepository;
    private final JdbcTemplate jdbcTemplate;

    public CustomerServiceImpl(CustomerRepository customerRepository, JdbcTemplate jdbcTemplate) {
        this.customerRepository = customerRepository;
        this.jdbcTemplate = jdbcTemplate;
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
        String normalizedEmail = request.email() != null && !request.email().isBlank()
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
        customer.setEmail(request.email() != null && !request.email().isBlank() ? request.email().trim() : null);

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
    public PageResponse<CustomerResponse> listCustomers(String q, String phone, int page, int size, String[] sort) {
        ensureAuthenticated();

        String trimmedQ = (q != null && !q.isBlank()) ? q.trim() : null;
        String trimmedPhone = (phone != null && !phone.isBlank()) ? phone.trim() : null;

        Pageable pageable = PageUtils.createPageable( page, size, sort, Sort.by(Sort.Direction.DESC, "createdAt"),
    ALLOWED_SORT_FIELDS
);
        Page<Customer> result = customerRepository.searchCustomers(trimmedQ, trimmedPhone, pageable);
        return PageResponse.from(result.map(CustomerResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listCustomerOrders(Long customerId, int page, int size) {
        ensureAuthenticated();

        Customer customer = customerRepository.findById(customerId).orElseThrow(() ->
            new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng", Map.of("customerId", customerId))
        );

        String currentRole = SecurityUtils.getCurrentRole();
        List<Long> assignedBranches = Collections.emptyList();

        // ADMIN & OWNER xem mọi chi nhánh; vai trò gán chi nhánh chỉ xem đơn chi nhánh mình
        boolean isAllBranches = "ADMIN".equalsIgnoreCase(currentRole) || "OWNER".equalsIgnoreCase(currentRole);
        if (!isAllBranches) {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId != null) {
                assignedBranches = jdbcTemplate.query(
                    "SELECT branch_id FROM user_branch WHERE user_id = ?",
                    (rs, rowNum) -> rs.getLong("branch_id"),
                    userId
                );
            }
            if (assignedBranches.isEmpty()) {
                return new PageResponse<>(Collections.emptyList(), page, size, 0L, 0);
            }
        }

        StringBuilder whereClause = new StringBuilder(" WHERE o.customer_id = ? ");
        if (!isAllBranches) {
            String inSql = String.join(",", Collections.nCopies(assignedBranches.size(), "?"));
            whereClause.append(" AND o.branch_id IN (").append(inSql).append(") ");
        }

        // Đếm tổng số đơn
        String countSql = "SELECT COUNT(*) FROM sales_order o " + whereClause;
        Object[] countParams;
        if (isAllBranches) {
            countParams = new Object[]{customerId};
        } else {
            countParams = new Object[1 + assignedBranches.size()];
            countParams[0] = customerId;
            for (int i = 0; i < assignedBranches.size(); i++) {
                countParams[1 + i] = assignedBranches.get(i);
            }
        }
        Long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, countParams);
        long total = (totalElements != null) ? totalElements : 0L;
        int totalPages = (int) Math.ceil((double) total / size);

        String querySql = """
            SELECT o.id, o.code, sc.code AS channel_code, o.external_order_id,
                   b.id AS branch_id, b.code AS branch_code, b.name AS branch_name,
                   o.status, o.total, o.created_at, o.completed_at,
                   COALESCE((SELECT SUM(oi.quantity) FROM order_item oi WHERE oi.order_id = o.id), 0) AS item_count
            FROM sales_order o
            JOIN sales_channel sc ON sc.id = o.channel_id
            JOIN branch b ON b.id = o.branch_id
        """ + whereClause + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?";

        Object[] queryParams = new Object[countParams.length + 2];
        System.arraycopy(countParams, 0, queryParams, 0, countParams.length);
        queryParams[countParams.length] = size;
        queryParams[countParams.length + 1] = (long) page * size;

        List<OrderSummaryResponse> content = jdbcTemplate.query(querySql, (rs, rowNum) -> {
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp completedTs = rs.getTimestamp("completed_at");

            return new OrderSummaryResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("channel_code"),
                rs.getString("external_order_id"),
                new BranchRef(rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name")),
                new CustomerRef(customer.getId(), customer.getPhone(), customer.getName()),
                rs.getString("status"),
                rs.getInt("item_count"),
                rs.getLong("total"),
                createdTs != null ? createdTs.toInstant() : null,
                completedTs != null ? completedTs.toInstant() : null
            );
        }, queryParams);

        return new PageResponse<>(content, page, size, total, totalPages);
    }

    private void ensureAuthenticated() {
        if (SecurityUtils.getCurrentRole() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập");
        }
    }
}
