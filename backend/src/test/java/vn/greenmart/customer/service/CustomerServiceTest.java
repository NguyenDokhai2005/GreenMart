package vn.greenmart.customer.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.customer.domain.Customer;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.repository.CustomerRepository;
import vn.greenmart.sales.service.OrderQueryService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private OrderQueryService orderQueryService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(customerRepository, orderQueryService, jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String role, String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            userId,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testCreateCustomer_Success() {
        authenticate("CASHIER", "2");
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), eq(2L)))
                .thenReturn(List.of(1L));
        CustomerRequest req = new CustomerRequest("0901234567", "Trần Thị Mai", "mai@gmail.com");

        when(customerRepository.findByPhone("0901234567")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(10L);
            return c;
        });

        CustomerResponse res = customerService.createCustomer(req);
        assertNotNull(res.id());
        assertEquals("0901234567", res.phone());
        assertEquals("Trần Thị Mai", res.name());
    }

    @Test
    void testCreateCustomer_DuplicatePhone_ThrowsConflict() {
        authenticate("CASHIER", "2");
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), eq(2L)))
                .thenReturn(List.of(1L));
        Customer existing = new Customer("0901234567", "Khách Cũ", null);
        existing.setId(99L);

        when(customerRepository.findByPhone("0901234567")).thenReturn(Optional.of(existing));

        CustomerRequest req = new CustomerRequest(" 0901234567 ", "Khách Mới", null);
        AppException ex = assertThrows(AppException.class, () -> customerService.createCustomer(req));
        assertEquals(ErrorCode.DUPLICATE_PHONE.name(), ex.getCode());
    }

    @Test
    void testCreateCustomer_ForbiddenForStorekeeper() {
        authenticate("STOREKEEPER", "3");
        CustomerRequest req = new CustomerRequest("0901234567", "Khách Mới", null);

        AppException ex = assertThrows(AppException.class, () -> customerService.createCustomer(req));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getCode());
    }

    @Test
    void testExists_ReturnsCorrectBoolean() {
        when(customerRepository.existsById(10L)).thenReturn(true);
        when(customerRepository.existsById(999L)).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertTrue(customerService.exists(10L));
        org.junit.jupiter.api.Assertions.assertFalse(customerService.exists(999L));
    }

    @Test
    void testDeactivateCustomer_BranchManager_Success() {
        authenticate("BRANCH_MANAGER", "5");
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(10L), eq(5L))).thenReturn(true);
        Customer existing = new Customer("0901234567", "Khách Hàng A", "a@gmail.com");
        existing.setId(10L);

        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse res = customerService.deactivateCustomer(10L);
        org.junit.jupiter.api.Assertions.assertFalse(res.active());
    }

    @Test
    void testDeactivateCustomer_Cashier_ThrowsForbidden() {
        authenticate("CASHIER", "2");

        AppException ex = assertThrows(AppException.class, () -> customerService.deactivateCustomer(10L));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getCode());
    }

    @Test
    void testUpdateCustomer_CashierCannotChangeActive_ThrowsForbidden() {
        authenticate("CASHIER", "2");
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(10L), eq(2L))).thenReturn(true);
        Customer existing = new Customer("0901234567", "Khách Hàng A", null);
        existing.setId(10L);

        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(existing));
        when(customerRepository.findByPhone("0901234567")).thenReturn(Optional.of(existing));

        CustomerRequest req = new CustomerRequest("0901234567", "Khách Hàng A", null, false);
        AppException ex = assertThrows(AppException.class, () -> customerService.updateCustomer(10L, req));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getCode());
    }

    @Test
    void testListCustomers_ActiveFalse_BranchManager_Success() {
        authenticate("BRANCH_MANAGER", "5");
        Customer inactive = new Customer("0901234567", "Khách Đã Ngừng", null);
        inactive.setId(10L);
        inactive.setActive(false);

        when(customerRepository.searchCustomers(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(false), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(inactive)));

        vn.greenmart.common.pagination.PageResponse<CustomerResponse> res =
            customerService.listCustomers(null, null, false, org.springframework.data.domain.PageRequest.of(0, 20));
        assertEquals(1, res.totalElements());
        org.junit.jupiter.api.Assertions.assertFalse(res.content().get(0).active());
    }

    @Test
    void testListCustomers_ActiveFalse_Cashier_ThrowsForbidden() {
        authenticate("CASHIER", "2");

        AppException ex = assertThrows(AppException.class, () ->
            customerService.listCustomers(null, null, false, org.springframework.data.domain.PageRequest.of(0, 20))
        );
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getCode());
    }
}
