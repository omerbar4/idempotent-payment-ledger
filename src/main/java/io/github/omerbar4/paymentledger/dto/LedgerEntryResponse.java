package io.github.omerbar4.paymentledger.dto;

import io.github.omerbar4.paymentledger.domain.EntryDirection;
import io.github.omerbar4.paymentledger.domain.LedgerEntry;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        EntryDirection direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency) {

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(entry.getId(), entry.getAccountId(), entry.getDirection(),
                entry.getAmount(), entry.getCurrency());
    }
}
