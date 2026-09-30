package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies that correctness does not depend on application code alone: the schema enforces it too. */
class DatabaseConstraintsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Test
    void unbalancedEntriesAreRejectedAtCommit() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID transactionId = id(pay(newKey(), source, destination, "10.00", "USD"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                insert into ledger_entries (id, transaction_id, account_id, direction, amount, currency)
                values (?, ?, ?, 'DEBIT', 5.00, 'USD')
                """, UUID.randomUUID(), transactionId, source)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("Unbalanced ledger transaction");

        assertThat(countEntries(transactionId)).isEqualTo(2);
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID transactionId = id(pay(newKey(), source, destination, "10.00", "USD"));

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 1 where transaction_id = ?",
                transactionId)).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where transaction_id = ?", transactionId))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void idempotencyKeyIsUniqueAtTheDatabaseLevel() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();
        pay(key, source, destination, "10.00", "USD");

        assertThatThrownBy(() -> jdbc.update("""
                insert into transactions (id, idempotency_key, request_hash, type, status, source_account_id,
                                          destination_account_id, amount, currency)
                values (?, ?, 'x', 'PAYMENT', 'PENDING', ?, ?, 1.00, 'USD')
                """, UUID.randomUUID(), key, source, destination))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uq_transactions_idempotency_key");
    }

    @Test
    void nonOverdraftAccountBalanceCannotGoNegative() {
        UUID account = createAccount("USD", false);

        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -0.01 where id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("chk_accounts_non_negative_balance");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        UUID source = createAccount("USD", true);
        UUID destination = createAccount("USD", false);

        assertThatThrownBy(() -> jdbc.update("""
                insert into transactions (id, idempotency_key, request_hash, type, status, source_account_id,
                                          destination_account_id, amount, currency)
                values (?, ?, 'x', 'PAYMENT', 'PENDING', ?, ?, 0, 'USD')
                """, UUID.randomUUID(), newKey(), source, destination))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("chk_transactions_amount_positive");
    }

    @Test
    void openApiDocumentIsServed() {
        ResponseEntity<JsonNode> docs = http.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(docs.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(docs.getBody().get("paths").has("/transactions")).isTrue();
        assertThat(docs.getBody().get("paths").has("/transactions/{id}/refund")).isTrue();
    }
}
