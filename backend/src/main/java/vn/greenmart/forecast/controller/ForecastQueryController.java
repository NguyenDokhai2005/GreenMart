package vn.greenmart.forecast.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.forecast.dto.ForecastResponse;
import vn.greenmart.forecast.service.ForecastQueryService;

@RestController
@RequestMapping("/forecasts")
@RequiredArgsConstructor
public class ForecastQueryController {
    private final ForecastQueryService service;

    @GetMapping
    public ForecastResponse get(@RequestParam Long branchId, @RequestParam Long productId,
            @RequestParam(required = false) Integer horizon, @RequestParam(required = false) Long runId) {
        return service.get(branchId, productId, horizon, runId);
    }
}
