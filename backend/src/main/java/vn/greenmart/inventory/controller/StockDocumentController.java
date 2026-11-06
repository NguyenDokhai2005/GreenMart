package vn.greenmart.inventory.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.inventory.dto.StockDocumentRequests;
import vn.greenmart.inventory.dto.StockDocumentResponse;
import vn.greenmart.inventory.service.StockDocumentService;

@RestController
@RequestMapping("/stock-documents")
@RequiredArgsConstructor
public class StockDocumentController {
    private final StockDocumentService service;

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
}
