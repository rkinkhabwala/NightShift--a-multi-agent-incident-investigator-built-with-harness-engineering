package dev.nightshift.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ChargeRequest(@NotNull Long orderId, @NotNull Long customerId, String loyaltyTier,
                            @Min(1) long amountCents, @NotEmpty @Valid List<Item> items) {

    public record Item(@NotBlank String sku, @Min(1) int qty) {
    }
}
