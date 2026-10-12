package dev.nightshift.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(@NotNull Long customerId, @NotEmpty @Size(max = 10) @Valid List<Item> items,
                                 String customerNote) {

    public record Item(@NotBlank String sku, @Min(1) @Max(10) int qty) {
    }
}
