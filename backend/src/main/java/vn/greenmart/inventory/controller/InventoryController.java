package vn.greenmart.inventory.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.inventory.domain.InventoryReferenceType;
import vn.greenmart.inventory.domain.InventoryTransactionType;
import vn.greenmart.inventory.dto.InventoryItemResponse;
import vn.greenmart.inventory.dto.InventoryTransactionFilter;
import vn.greenmart.inventory.dto.InventoryTransactionResponse;
import vn.greenmart.inventory.dto.StockStatus;
import vn.greenmart.inventory.service.InventoryQueryService;
import vn.greenmart.inventory.service.InventoryTransactionQueryService;

import java.time.LocalDate;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryQueryService service;
    private final InventoryTransactionQueryService transactionService;

    @GetMapping("/transactions")
    public PageResponse<InventoryTransactionResponse> transactions(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) InventoryTransactionType type,
            @RequestParam(required = false) InventoryReferenceType refType,
            @RequestParam(required = false) Long refId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return transactionService.list(new InventoryTransactionFilter(branchId, productId, type, refType, refId, from, to), page, size);
    }

    @GetMapping
    public PageResponse<InventoryItemResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) StockStatus stockStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return service.list(branchId, productId, categoryId, q, stockStatus, page, size, request.getParameterValues("sort"));
    }
}
