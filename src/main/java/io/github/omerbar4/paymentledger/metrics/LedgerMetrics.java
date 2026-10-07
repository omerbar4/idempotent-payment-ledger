package io.github.omerbar4.paymentledger.metrics;

import io.github.omerbar4.paymentledger.domain.TransactionStatus;
import io.github.omerbar4.paymentledger.domain.TransactionType;
import io.github.omerbar4.paymentledger.service.TransactionResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Payment-domain counters. Tags are limited to small, fixed value sets (transaction type,
 * outcome, error code); identifiers, amounts and idempotency keys are never used as tags.
 *
 * <ul>
 *   <li>{@code ledger.transactions{type, outcome}}: create/refund requests that completed
 *       successfully at the HTTP level. {@code outcome} is {@code posted}, {@code failed}
 *       (business rejection such as insufficient funds, recorded as a FAILED transaction) or
 *       {@code replayed} (the Idempotency-Key was already used for the same request).</li>
 *   <li>{@code ledger.api.errors{code}}: problem+json error responses by stable error code.</li>
 * </ul>
 */
@Component
public class LedgerMetrics {

    static final String TRANSACTIONS = "ledger.transactions";
    static final String API_ERRORS = "ledger.api.errors";

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Pre-register the fixed series so they are exported as 0 before the first request.
        for (TransactionType type : TransactionType.values()) {
            for (String outcome : new String[] {"posted", "failed", "replayed"}) {
                transactionCounter(type, outcome);
            }
        }
    }

    /**
     * Must be called only after the service's database transaction has committed (i.e. from the
     * controller), so a counted transaction is always a durable one.
     */
    public void recordTransaction(TransactionResult result) {
        transactionCounter(result.transaction().getType(), outcome(result)).increment();
    }

    public void recordApiError(String code) {
        Counter.builder(API_ERRORS)
                .description("Error responses by problem+json error code")
                .tag("code", code)
                .register(registry)
                .increment();
    }

    private Counter transactionCounter(TransactionType type, String outcome) {
        return Counter.builder(TRANSACTIONS)
                .description("Completed transaction create/refund requests by type and outcome")
                .tag("type", type.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome)
                .register(registry);
    }

    private static String outcome(TransactionResult result) {
        if (result.replayed()) {
            return "replayed";
        }
        return result.transaction().getStatus() == TransactionStatus.FAILED ? "failed" : "posted";
    }
}
