package vn.greenmart.sales.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.service.PosOrderService;
import vn.greenmart.sales.service.QuoteService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final QuoteService quoteService;
    private final PosOrderService posOrderService;
    private final PosIdempotencyService posIdempotencyService;

    @GetMapping
    public ResponseEntity<PageResponse<OrderSummaryResponse>> listOrders(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "customerId", required = false) Long customerId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            HttpServletRequest request
    ) {
        String[] sortParams = (request != null) ? request.getParameterValues("sort") : null;
        return ResponseEntity.ok(posOrderService.listOrders(
                branchId, channel, status, from, to, customerId, q, page, size, sortParams
        ));
    }

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
}
