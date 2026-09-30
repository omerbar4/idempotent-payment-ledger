package io.github.omerbar4.paymentledger.dto;

import io.github.omerbar4.paymentledger.domain.LedgerEntry;
import io.github.omerbar4.paymentledger.domain.LedgerTransaction;
import io.github.omerbar4.paymentledger.domain.TransactionStatus;
import io.github.omerbar4.paymentledger.domain.TransactionType;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        UUID id,
        TransactionType type,
        TransactionStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String description,
        String failureReason,
        UUID refundOfId,
        List<LedgerEntryResponse> entries,
        Instant createdAt,
        Instant updatedAt) {

    public static TransactionResponse from(LedgerTransaction t, List<LedgerEntry> entries) {
        return new TransactionResponse(t.getId(), t.getType(), t.getStatus(), t.getSourceAccountId(),
                t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getDescription(),
                t.getFailureReason(), t.getRefundOfId(),
                entries.stream().map(LedgerEntryResponse::from).toList(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
