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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(customerRepository, jdbcTemplate);
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
}
