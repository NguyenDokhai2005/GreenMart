package vn.greenmart.customer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CustomerController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {CustomerController.class, GlobalExceptionHandler.class})
@DisplayName("CustomerController - WebMvc Tests")
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    @Test
    @DisplayName("POST /customers: Tạo khách hàng thành công -> 201 Created")
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

    @Test
    @DisplayName("POST /customers: SĐT sai định dạng -> 400 VALIDATION_ERROR")
    void testCreateCustomer_InvalidPhone_Returns400() throws Exception {
        CustomerRequest req = new CustomerRequest("12345", "Trần Thị Mai", null);

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("phone"));
    }

    @Test
    @DisplayName("POST /customers: Trùng SĐT -> 409 DUPLICATE_PHONE")
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

    @Test
    @DisplayName("GET /customers: Phân trang page=-1 -> 400 VALIDATION_ERROR")
    void testListCustomers_InvalidPage_Returns400() throws Exception {
        mockMvc.perform(get("/customers").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("page"));
    }

    @Test
    @DisplayName("GET /customers/{id}/orders: Phân trang size=101 -> 400 VALIDATION_ERROR")
    void testListCustomerOrders_InvalidSize_Returns400() throws Exception {
        mockMvc.perform(get("/customers/1/orders").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("size"));
    }
}
