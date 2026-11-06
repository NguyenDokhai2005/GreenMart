package vn.greenmart.sales.controller;

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
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosOrderService;
import vn.greenmart.sales.service.QuoteService;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
    OrderController.class,
    GlobalExceptionHandler.class
})
@Import(WebMvcConfig.class)
@DisplayName("OrderController - WebMvc Tests")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QuoteService quoteService;

    @Test
    @DisplayName("POST /api/v1/orders/quote thành công trả về HTTP 200")
    void testQuoteOrder_Success_Returns200() throws Exception {
        QuoteRequest request = new QuoteRequest(
                1L,
                125L,
                List.of(new OrderLineRequest(101L, 1)),
                null
        );

        Quote mockQuote = new Quote(
                1L,
                125L,
                Collections.emptyList(),
                null,
                180000L,
                15000L,
                0L,
                0L,
                15000L,
                165000L,
                true
        );

        when(quoteService.calculateQuote(any(QuoteRequest.class))).thenReturn(mockQuote);

        mockMvc.perform(post("/api/v1/orders/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subtotal").value(180000))
            .andExpect(jsonPath("$.total").value(165000))
            .andExpect(jsonPath("$.canComplete").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/orders/quote thiếu branchId trả về HTTP 400")
    void testQuoteOrder_MissingBranchId_Returns400() throws Exception {
        QuoteRequest request = new QuoteRequest(
                null,
                125L,
                List.of(new OrderLineRequest(101L, 1)),
                null
        );

        mockMvc.perform(post("/api/v1/orders/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @MockitoBean
    private PosOrderService posOrderService;

    @Test
    @DisplayName("POST /api/v1/orders/pos thành công trả về HTTP 201")
    void testCreatePosOrder_Success_Returns201() throws Exception {
        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, "SUCCESS"),
                180000L
        );

        OrderResponse mockRes = new OrderResponse(
                500L, "POS-GM001-20261105-0042", "POS", null,
                new BranchRef(1L, "GM-001", "GreenMart Q1"), null,
                OrderStatus.COMPLETED, 1, 180000L,
                Instant.now(), Instant.now(), null,
                Collections.emptyList(), null, null,
                180000L, 0L, 0L, 0L, 0L,
                Collections.emptyList(), null, null, null
        );

        when(posOrderService.createPosOrder(any(), anyString())).thenReturn(mockRes);

        mockMvc.perform(post("/api/v1/orders/pos")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(500))
            .andExpect(jsonPath("$.code").value("POS-GM001-20261105-0042"))
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/pos thiếu Idempotency-Key trả về HTTP 400")
    void testCreatePosOrder_MissingIdempotencyKey_Returns400() throws Exception {
        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 1)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, "SUCCESS"),
                180000L
        );

        mockMvc.perform(post("/api/v1/orders/pos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }
}
