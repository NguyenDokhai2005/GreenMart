package vn.greenmart.inventory.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.inventory.dto.InventoryItemResponse;
import vn.greenmart.inventory.dto.StockStatus;
import vn.greenmart.inventory.service.InventoryQueryService;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryQueryService service;

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
