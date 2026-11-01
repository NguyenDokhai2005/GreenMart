package vn.greenmart.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.customer.controller.CustomerController;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.service.CustomerService;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CustomerController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
    CustomerController.class,
    GlobalExceptionHandler.class
})
class CustomerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    // Tạo khách hàng thành công -> 201 Created
    @Test
    void testCreateCustomer_Success_Returns201() throws Exception {
        CustomerRequest req = new CustomerRequest("0901234567", "Trần Thị Mai", "mai@gmail.com");
        CustomerResponse res = new CustomerResponse(1L, "0901234567", "Trần Thị Mai", "mai@gmail.com", Instant.now());

        when(customerService.createCustomer(any(CustomerRequest.class))).thenReturn(res);

        mockMvc.perform(post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.phone").value("0901234567"));
    }

    // Trùng số điện thoại -> 409 DUPLICATE_PHONE, details.customerId
    @Test
    void testCreateCustomer_DuplicatePhone_Returns409() throws Exception {
        CustomerRequest req = new CustomerRequest("0901234567", "Trần Thị Mai", null);

        when(customerService.createCustomer(any(CustomerRequest.class))).thenThrow(
            new AppException(
                ErrorCode.DUPLICATE_PHONE,
                "Số điện thoại đã thuộc khách hàng khác",
                Map.of("phone", "0901234567", "customerId", 125)
            )
        );

        mockMvc.perform(post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
            .andExpect(jsonPath("$.details.customerId").value(125));
    }

    // SĐT sai định dạng (12345) -> 400 VALIDATION_ERROR, details[0].field = phone
    @Test
    void testCreateCustomer_InvalidPhone_Returns400() throws Exception {
        CustomerRequest req = new CustomerRequest("12345", "Trần Thị Mai", null);

        mockMvc.perform(post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0].field").value("phone"));
    }
}
