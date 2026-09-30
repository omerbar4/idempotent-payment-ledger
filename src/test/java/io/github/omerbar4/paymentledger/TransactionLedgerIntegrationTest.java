package io.github.omerbar4.paymentledger;

import static io.github.omerbar4.paymentledger.IdempotencyIntegrationTest.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransactionLedgerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void paymentPostsBalancedDebitAndCreditEntries() {
        UUID source = createFundedAccount("EUR", "100.00");
        UUID destination = createAccount("EUR", false);

        ResponseEntity<JsonNode> response = pay(newKey(), source, destination, "12.3456", "EUR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("status").asText()).isEqualTo("POSTED");
        assertThat(body.get("type").asText()).isEqualTo("PAYMENT");
        assertThat(body.get("amount").asText()).isEqualTo("12.3456");
        assertThat(body.get("entries")).hasSize(2);
        JsonNode debit = findEntry(body, "DEBIT");
        JsonNode credit = findEntry(body, "CREDIT");
        assertThat(debit.get("accountId").asText()).isEqualTo(source.toString());
        assertThat(credit.get("accountId").asText()).isEqualTo(destination.toString());
        assertThat(new BigDecimal(debit.get("amount").asText()))
                .isEqualByComparingTo(new BigDecimal(credit.get("amount").asText()));

        assertThat(balance(source)).isEqualByComparingTo("87.6544");
        assertThat(balance(destination)).isEqualByComparingTo("12.3456");

        JsonNode fetched = http.getForObject("/transactions/" + id(response), JsonNode.class);
        assertThat(fetched.get("status").asText()).isEqualTo("POSTED");
        assertThat(fetched.get("entries")).hasSize(2);
    }

    @Test
    void insufficientFundsMarksTransactionFailedWithoutEntries() {
        UUID source = createFundedAccount("USD", "10.00");
        UUID destination = createAccount("USD", false);

        ResponseEntity<JsonNode> response = pay(newKey(), source, destination, "10.01", "USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status").asText()).isEqualTo("FAILED");
        assertThat(response.getBody().get("entries")).isEmpty();
        assertThat(countEntries(id(response))).isZero();
        assertThat(balance(source)).isEqualByComparingTo("10.00");
        assertThat(balance(destination)).isEqualByComparingTo("0");
    }

    @Test
    void currencyMismatchIsRejectedAndDoesNotConsumeKey() {
        UUID source = createFundedAccount("USD", "10.00");
        UUID destination = createAccount("EUR", false);
        String key = newKey();

        ResponseEntity<JsonNode> response = pay(key, source, destination, "1.00", "USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(countTransactionsWithKey(key)).isZero();
    }

    @Test
    void unknownAccountReturns404() {
        UUID source = createFundedAccount("USD", "10.00");

        ResponseEntity<JsonNode> response = pay(newKey(), source, UUID.randomUUID(), "1.00", "USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void invalidRequestBodiesAreRejectedWithFieldErrors() {
        UUID source = createFundedAccount("USD", "10.00");
        UUID destination = createAccount("USD", false);

        ResponseEntity<JsonNode> negative = pay(newKey(), source, destination, "-5.00", "USD");
        ResponseEntity<JsonNode> tooPrecise = pay(newKey(), source, destination, "1.00001", "USD");
        ResponseEntity<JsonNode> badCurrency = pay(newKey(), source, destination, "1.00", "usd");
        ResponseEntity<JsonNode> missingFields = postTransaction(newKey(), Map.of("amount", "1.00"));
        ResponseEntity<JsonNode> sameAccount = pay(newKey(), source, source, "1.00", "USD");

        for (ResponseEntity<JsonNode> r : List.of(negative, tooPrecise, badCurrency, missingFields)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(r.getBody().get("errors")).isNotEmpty();
        }
        assertThat(sameAccount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sameAccount.getBody().get("code").asText()).isEqualTo("SAME_ACCOUNT");
        assertThat(balance(source)).isEqualByComparingTo("10.00");
    }

    @Test
    void refundReversesEntriesAndMarksPaymentRefunded() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID paymentId = id(pay(newKey(), source, destination, "30.00", "USD"));

        ResponseEntity<JsonNode> refund = refund(newKey(), paymentId);

        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = refund.getBody();
        assertThat(body.get("type").asText()).isEqualTo("REFUND");
        assertThat(body.get("status").asText()).isEqualTo("POSTED");
        assertThat(body.get("refundOfId").asText()).isEqualTo(paymentId.toString());
        assertThat(findEntry(body, "DEBIT").get("accountId").asText()).isEqualTo(destination.toString());
        assertThat(findEntry(body, "CREDIT").get("accountId").asText()).isEqualTo(source.toString());

        JsonNode payment = http.getForObject("/transactions/" + paymentId, JsonNode.class);
        assertThat(payment.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(balance(source)).isEqualByComparingTo("100.00");
        assertThat(balance(destination)).isEqualByComparingTo("0");
    }

    @Test
    void secondRefundWithDifferentKeyIsRejected() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID paymentId = id(pay(newKey(), source, destination, "30.00", "USD"));
        refund(newKey(), paymentId);

        ResponseEntity<JsonNode> second = refund(newKey(), paymentId);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code").asText()).isEqualTo("TRANSACTION_ALREADY_REFUNDED");
        assertThat(balance(source)).isEqualByComparingTo("100.00");
    }

    @Test
    void failedPaymentsAndRefundsCannotBeRefunded() {
        UUID source = createAccount("USD", false);
        UUID destination = createAccount("USD", false);
        UUID failedId = id(pay(newKey(), source, destination, "5.00", "USD"));

        ResponseEntity<JsonNode> refundOfFailed = refund(newKey(), failedId);
        assertThat(refundOfFailed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refundOfFailed.getBody().get("code").asText()).isEqualTo("TRANSACTION_NOT_REFUNDABLE");

        UUID funded = createFundedAccount("USD", "10.00");
        UUID paymentId = id(pay(newKey(), funded, destination, "5.00", "USD"));
        UUID refundId = id(refund(newKey(), paymentId));
        ResponseEntity<JsonNode> refundOfRefund = refund(newKey(), refundId);
        assertThat(refundOfRefund.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(refund(newKey(), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refundFailsWhenRecipientNoLongerHoldsTheFunds() {
        UUID source = createFundedAccount("USD", "50.00");
        UUID merchant = createAccount("USD", false);
        UUID elsewhere = createAccount("USD", false);
        UUID paymentId = id(pay(newKey(), source, merchant, "50.00", "USD"));
        pay(newKey(), merchant, elsewhere, "50.00", "USD");

        ResponseEntity<JsonNode> refund = refund(newKey(), paymentId);

        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(refund.getBody().get("status").asText()).isEqualTo("FAILED");
        JsonNode payment = http.getForObject("/transactions/" + paymentId, JsonNode.class);
        assertThat(payment.get("status").asText()).isEqualTo("POSTED");
    }

    @Test
    void concurrentRefundsWithDifferentKeysRefundExactlyOnce() throws Exception {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID paymentId = id(pay(newKey(), source, destination, "40.00", "USD"));

        List<ResponseEntity<JsonNode>> responses = runConcurrently(10, () -> refund(newKey(), paymentId));

        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).hasSize(9);
        assertThat(balance(source)).isEqualByComparingTo("100.00");
        assertThat(balance(destination)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentPaymentsNeverOverdrawAnAccount() throws Exception {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);

        List<ResponseEntity<JsonNode>> responses = runConcurrently(20,
                () -> pay(newKey(), source, destination, "10.00", "USD"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses).filteredOn(r -> r.getBody().get("status").asText().equals("POSTED")).hasSize(10);
        assertThat(responses).filteredOn(r -> r.getBody().get("status").asText().equals("FAILED")).hasSize(10);
        assertThat(balance(source)).isEqualByComparingTo("0");
        assertThat(balance(destination)).isEqualByComparingTo("100.00");
    }

    @Test
    void ledgerIsGloballyBalancedAndBalancesMatchEntries() {
        UUID a = createFundedAccount("USD", "500.00");
        UUID b = createAccount("USD", false);
        UUID payment = id(pay(newKey(), a, b, "120.00", "USD"));
        pay(newKey(), b, a, "20.00", "USD");
        refund(newKey(), payment);

        BigDecimal debits = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entries where direction = 'DEBIT'", BigDecimal.class);
        BigDecimal credits = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entries where direction = 'CREDIT'", BigDecimal.class);
        assertThat(debits).isEqualByComparingTo(credits);

        Integer mismatched = jdbc.queryForObject("""
                select count(*) from accounts a
                where a.balance <> coalesce((select sum(case e.direction when 'CREDIT' then e.amount else -e.amount end)
                                             from ledger_entries e where e.account_id = a.id), 0)
                """, Integer.class);
        assertThat(mismatched).isZero();
    }

    private static JsonNode findEntry(JsonNode transaction, String direction) {
        for (JsonNode entry : transaction.get("entries")) {
            if (entry.get("direction").asText().equals(direction)) {
                return entry;
            }
        }
        throw new AssertionError("No " + direction + " entry in " + transaction);
    }
}
