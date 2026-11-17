package vn.greenmart.inventory.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.inventory.domain.StockDocumentStatus;
import vn.greenmart.inventory.domain.StockDocumentType;
import vn.greenmart.inventory.dto.StockDocumentRequests;
import vn.greenmart.inventory.dto.StockDocumentResponse;
import vn.greenmart.inventory.dto.StockDocumentSummary;
import vn.greenmart.inventory.service.StockDocumentQueryService;
import vn.greenmart.inventory.service.StockDocumentService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/stock-documents")
@RequiredArgsConstructor
public class StockDocumentController {
    private final StockDocumentService service;
    private final StockDocumentQueryService queryService;

    @GetMapping
    public PageResponse<StockDocumentSummary> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) StockDocumentType type,
            @RequestParam(required = false) List<StockDocumentStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return queryService.list(branchId, type, status, from, to, q, page, size, request.getParameterValues("sort"));
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public StockDocumentResponse create(@Valid @RequestBody StockDocumentRequests.Create request) { return service.create(request); }

    @GetMapping("/{id}")
    public StockDocumentResponse get(@PathVariable Long id) { return service.get(id); }

    @PutMapping("/{id}")
    public StockDocumentResponse update(@PathVariable Long id, @Valid @RequestBody StockDocumentRequests.Update request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/cancel")
    public StockDocumentResponse cancel(@PathVariable Long id, @Valid @RequestBody StockDocumentRequests.Cancel request) {
        return service.cancel(id, request);
    }

    @PostMapping("/{id}/approve")
    public StockDocumentResponse approve(@PathVariable Long id) { return service.approve(id); }

    @PostMapping("/{id}/receive")
    public StockDocumentResponse receive(@PathVariable Long id, @Valid @RequestBody StockDocumentRequests.Receive request) {
        return service.receive(id, request);
    }
}
