package vn.greenmart.sales.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosOrderService;
import vn.greenmart.sales.service.QuoteService;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final QuoteService quoteService;
    private final PosOrderService posOrderService;

    @PostMapping("/quote")
    public ResponseEntity<Quote> quoteOrder(@Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(quoteService.calculateQuote(request));
    }

    @PostMapping("/pos")
    public ResponseEntity<OrderResponse> createPosOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PosOrderRequest request) {

        // TODO(#34): Gọi phương thức cache Redis để chặn lặp tạo đơn trong 24h dựa trên idempotencyKey

        OrderResponse response = posOrderService.createPosOrder(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
