package io.github.omerbar4.paymentledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @Schema(example = "Alice wallet")
        @NotBlank @Size(max = 100)
        String name,

        @Schema(example = "USD", description = "ISO 4217 currency code")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency,

        @Schema(description = "Allow the balance to go below zero (e.g. an external funding/settlement account)",
                defaultValue = "false")
        Boolean allowNegativeBalance) {
}
