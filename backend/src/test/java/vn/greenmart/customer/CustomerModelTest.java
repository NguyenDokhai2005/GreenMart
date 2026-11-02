package vn.greenmart.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.greenmart.customer.domain.Customer;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CustomerModelTest - Entity & DTO Coverage")
class CustomerModelTest {

    @Test
    void testCustomerEntity_GettersAndSetters() {
        Customer customer = new Customer();
        customer.setId(10L);
        customer.setPhone("0901234567");
        customer.setName("Nguyễn Văn A");
        customer.setEmail("a@gmail.com");

        assertEquals(10L, customer.getId());
        assertEquals("0901234567", customer.getPhone());
        assertEquals("Nguyễn Văn A", customer.getName());
        assertEquals("a@gmail.com", customer.getEmail());
        assertNotNull(customer.getCreatedAt());

        Customer customerParam = new Customer("0988888888", "Nguyễn Văn B", null);
        assertEquals("0988888888", customerParam.getPhone());
        assertEquals("Nguyễn Văn B", customerParam.getName());
        assertNull(customerParam.getEmail());
        assertNotNull(customerParam.getCreatedAt());
    }

    @Test
    void testCustomerDto_FromAndRecords() {
        Customer customer = new Customer("0901234567", "Nguyễn Văn A", "a@gmail.com");
        customer.setId(1L);

        CustomerResponse response = CustomerResponse.from(customer);
        assertEquals(1L, response.id());
        assertEquals("0901234567", response.phone());
        assertEquals("Nguyễn Văn A", response.name());
        assertEquals("a@gmail.com", response.email());

        CustomerRequest request = new CustomerRequest("0901234567", "Tên", "email@test.com");
        assertEquals("0901234567", request.phone());
        assertEquals("Tên", request.name());
        assertEquals("email@test.com", request.email());
    }
}
