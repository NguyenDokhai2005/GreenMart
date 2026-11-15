package vn.greenmart.sales.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.service.PosOrderService;
import vn.greenmart.sales.service.QuoteService;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final QuoteService quoteService;
    private final PosOrderService posOrderService;
    private final PosIdempotencyService posIdempotencyService;

    @PostMapping("/quote")
    public ResponseEntity<Quote> quoteOrder(@Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(quoteService.calculateQuote(request));
    }

    @PostMapping("/pos")
    public ResponseEntity<OrderResponse> createPosOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PosOrderRequest request
    ) {
        IdempotencyResult result = posIdempotencyService.execute(
                idempotencyKey,
                request,
                () -> posOrderService.createPosOrder(request, idempotencyKey)
        );

        HttpStatus status = result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable("orderId") Long orderId) {
        return ResponseEntity.ok(posOrderService.getOrder(orderId));
    }

    @PostMapping("/{orderId}/complete")
    public ResponseEntity<OrderResponse> completeOnlineOrder(@PathVariable("orderId") Long orderId) {
        return ResponseEntity.ok(posOrderService.completeOnlineOrder(orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOnlineOrder(
            @PathVariable("orderId") Long orderId,
            @Valid @RequestBody CancelOrderRequest request
    ) {
        return ResponseEntity.ok(posOrderService.cancelOnlineOrder(orderId, request));
    }
}
