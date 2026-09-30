package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.domain.LedgerEntry;
import io.github.omerbar4.paymentledger.domain.LedgerTransaction;
import java.util.List;

/**
 * @param replayed {@code true} when the Idempotency-Key had already been used for this exact
 *     request and the existing transaction is returned instead of creating a new one
 */
public record TransactionResult(LedgerTransaction transaction, List<LedgerEntry> entries, boolean replayed) {
}
