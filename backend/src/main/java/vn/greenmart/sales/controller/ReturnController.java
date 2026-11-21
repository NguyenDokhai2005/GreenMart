package vn.greenmart.sales.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.sales.dto.CreateReturnRequest;
import vn.greenmart.sales.dto.ReturnResponse;
import vn.greenmart.sales.service.ReturnService;

import java.util.List;

@RestController
@RequestMapping("/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returnService;

    @PostMapping
    public ResponseEntity<ReturnResponse> createReturn(@Valid @RequestBody CreateReturnRequest request) {
        ReturnResponse created = returnService.createReturn(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<ReturnResponse>> listReturnsByOrder(@RequestParam("orderId") Long orderId) {
        return ResponseEntity.ok(returnService.listReturnsByOrder(orderId));
    }

    @GetMapping("/{returnId}")
    public ResponseEntity<ReturnResponse> getReturn(@PathVariable("returnId") Long returnId) {
        return ResponseEntity.ok(returnService.getReturn(returnId));
    }

    @PostMapping("/{returnId}/approve")
    public ResponseEntity<ReturnResponse> approveReturn(@PathVariable("returnId") Long returnId) {
        return ResponseEntity.ok(returnService.approveReturn(returnId));
    }

    @PostMapping("/{returnId}/cancel")
    public ResponseEntity<ReturnResponse> cancelReturn(@PathVariable("returnId") Long returnId) {
        return ResponseEntity.ok(returnService.cancelReturn(returnId));
    }
}
