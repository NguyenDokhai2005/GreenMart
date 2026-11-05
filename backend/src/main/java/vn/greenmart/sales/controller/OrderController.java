package vn.greenmart.sales.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.sales.dto.Quote;
import vn.greenmart.sales.dto.QuoteRequest;
import vn.greenmart.sales.service.QuoteService;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final QuoteService quoteService;

    @PostMapping("/quote")
    public ResponseEntity<Quote> quoteOrder(@Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(quoteService.calculateQuote(request));
    }
}
