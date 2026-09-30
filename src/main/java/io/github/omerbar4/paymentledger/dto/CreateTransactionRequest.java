package io.github.omerbar4.paymentledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull
        UUID sourceAccountId,

        @NotNull
        UUID destinationAccountId,

        @Schema(type = "string", example = "25.00", description = "Positive amount, at most 4 decimal places")
        @NotNull @Positive @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @Schema(example = "USD")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency,

        @Schema(example = "Order #1234")
        @Size(max = 255)
        String description) {
}
