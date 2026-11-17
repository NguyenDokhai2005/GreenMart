package vn.greenmart.inventory.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.inventory.dto.ReplenishmentResponse;
import vn.greenmart.inventory.service.InventoryReplenishmentService;

@RestController
@RequestMapping("/inventory/{branchId}/{productId}/replenishment")
@RequiredArgsConstructor
public class ReplenishmentController {
    private final InventoryReplenishmentService service;

    @GetMapping
    public ReplenishmentResponse get(@PathVariable Long branchId, @PathVariable Long productId) {
        return service.get(branchId, productId);
    }

    @PutMapping
    public ReplenishmentResponse configure(@PathVariable Long branchId, @PathVariable Long productId,
            @Valid @RequestBody Settings settings) {
        return service.configure(branchId, productId, settings.manualReorderPoint(), settings.packSize());
    }

    public record Settings(@PositiveOrZero Integer manualReorderPoint, @NotNull @Positive Integer packSize) {}
}
