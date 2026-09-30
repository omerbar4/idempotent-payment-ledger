package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.LedgerTransaction;

public interface IdempotentInsertRepository {

    /**
     * Atomically claims the transaction's idempotency key.
     *
     * @return {@code true} if the row was inserted, {@code false} if a row with the same
     *     idempotency key already exists (possibly committed by a concurrent request)
     */
    boolean insertIfAbsent(LedgerTransaction transaction);
}
