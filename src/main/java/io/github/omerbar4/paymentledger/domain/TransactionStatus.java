package io.github.omerbar4.paymentledger.domain;

public enum TransactionStatus {
    /** Row inserted (idempotency key claimed), not yet posted to the ledger. */
    PENDING,
    /** Balanced ledger entries were written and account balances updated. */
    POSTED,
    /** A payment that has been fully reversed by a refund transaction. */
    REFUNDED,
    /** Rejected by a business rule (e.g. insufficient funds); no ledger entries exist. */
    FAILED
}
