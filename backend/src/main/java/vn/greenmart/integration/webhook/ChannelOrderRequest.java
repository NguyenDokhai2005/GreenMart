package vn.greenmart.integration.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public record ChannelOrderRequest(
        @NotBlank @Size(max = 60) String externalOrderId,
        @NotNull OffsetDateTime orderedAt,
        @NotNull @Valid Customer customer,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items,
        @PositiveOrZero Long shippingFee,
        @NotNull PaymentMethod paymentMethod) {
    public record Customer(@NotBlank @Size(max = 120) String name,
                           @NotNull @Pattern(regexp = "^0[0-9]{9,10}$") String phone) {}
    public record Item(@NotBlank @Size(max = 60) String externalSku,
                       @NotNull @Min(1) @Max(9999) Integer quantity,
                       @NotNull @PositiveOrZero Long unitPrice) {}
    public enum PaymentMethod { ONLINE, COD }
}
