package vn.greenmart.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.common.config.WebMvcConfig;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.customer.controller.CustomerController;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CustomerController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
    CustomerController.class,
    GlobalExceptionHandler.class
})
@Import(WebMvcConfig.class)
@DisplayName("CustomerIntegrationTest - WebMvc Tests")
class CustomerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void testCreateCustomer_Success_Returns201() throws Exception {
        CustomerRequest req = new CustomerRequest("0901234567", "Trần Thị Mai", "mai@gmail.com");
        CustomerResponse res = new CustomerResponse(1L, "0901234567", "Trần Thị Mai", "mai@gmail.com", Instant.now());

        when(customerService.createCustomer(any(CustomerRequest.class))).thenReturn(res);

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.phone").value("0901234567"));
    }

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

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
            .andExpect(jsonPath("$.details.customerId").value(125));
    }

    @Test
    void testCreateCustomer_InvalidPhone_Returns400() throws Exception {
        CustomerRequest req = new CustomerRequest("12345", "Trần Thị Mai", null);

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0].field").value("phone"));
    }

    @Test
    void testGetCustomer_Returns200() throws Exception {
        CustomerResponse res = new CustomerResponse(1L, "0901234567", "Trần Thị Mai", null, Instant.now());
        when(customerService.getCustomer(1L)).thenReturn(res);

        mockMvc.perform(get("/api/v1/customers/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.phone").value("0901234567"));
    }

    @Test
    void testUpdateCustomer_Success_Returns200() throws Exception {
        CustomerRequest req = new CustomerRequest("0901234567", "Trần Thị Mai Mới", null);
        CustomerResponse res = new CustomerResponse(1L, "0901234567", "Trần Thị Mai Mới", null, Instant.now());

        when(customerService.updateCustomer(eq(1L), any(CustomerRequest.class))).thenReturn(res);

        mockMvc.perform(put("/api/v1/customers/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Trần Thị Mai Mới"));
    }

    @Test
    void testListCustomers_Success_Returns200() throws Exception {
        CustomerResponse res = new CustomerResponse(1L, "0901234567", "Trần Thị Mai", null, Instant.now());
        PageResponse<CustomerResponse> pageRes = new PageResponse<>(List.of(res), 0, 20, 1, 1);

        when(customerService.listCustomers(any(), any(), any())).thenReturn(pageRes);

        mockMvc.perform(get("/api/v1/customers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].phone").value("0901234567"));
    }

    @Test
    void testListCustomerOrders_Success_Returns200() throws Exception {
        PageResponse<OrderSummaryResponse> pageRes = new PageResponse<>(Collections.emptyList(), 0, 20, 0, 0);
        when(customerService.listCustomerOrders(eq(1L), any())).thenReturn(pageRes);

        mockMvc.perform(get("/api/v1/customers/1/orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void testListCustomerOrders_InvalidSize_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1/orders").param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0].field").value("size"));
    }

    @Test
    void testListCustomerOrders_InvalidPage_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1/orders").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0].field").value("page"));
    }
}
