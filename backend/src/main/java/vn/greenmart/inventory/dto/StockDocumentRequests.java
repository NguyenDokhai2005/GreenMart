package vn.greenmart.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import vn.greenmart.inventory.domain.StockDocumentType;

import java.util.List;

public final class StockDocumentRequests {
    private StockDocumentRequests() {}

    public record Item(@NotNull @Positive Long productId,
            @NotNull @Min(1) @Max(99999) Integer quantity,
            @NotNull @PositiveOrZero Long unitCost, Integer countedQuantity) {}

    public record Create(@NotNull StockDocumentType type, @NotNull @Positive Long branchId,
            Long toBranchId, @NotBlank @Size(max = 120) String supplier,
            String exportReason, @Size(max = 255) String note,
            @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Item> items) {}

    public record Update(Long toBranchId, @Size(max = 120) String supplier,
            String exportReason, @Size(max = 255) String note,
            @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Item> items) {}

    public record Cancel(@NotBlank @Size(min = 3, max = 255) String reason) {}
}
