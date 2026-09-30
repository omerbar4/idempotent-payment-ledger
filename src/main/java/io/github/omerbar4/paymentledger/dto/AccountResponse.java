package io.github.omerbar4.paymentledger.dto;

import io.github.omerbar4.paymentledger.domain.Account;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String currency,
        boolean allowNegativeBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getName(), account.getCurrency(),
                account.isAllowNegativeBalance(), account.getBalance(), account.getCreatedAt());
    }
}
