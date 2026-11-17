package vn.greenmart.inventory.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.inventory.dto.StockAlertResponse;
import vn.greenmart.inventory.service.StockAlertQueryService;

import java.util.List;

@RestController
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class StockAlertController {
    private final StockAlertQueryService alerts;

    @GetMapping("/low-stock")
    public List<StockAlertResponse> list(@RequestParam(required = false) Long branchId) {
        return alerts.listOpen(branchId);
    }
}
