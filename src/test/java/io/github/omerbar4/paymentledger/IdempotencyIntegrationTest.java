package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void duplicateRequestWithSameKeyReturnsOriginalTransactionWithoutPostingTwice() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();

        ResponseEntity<JsonNode> first = pay(key, source, destination, "40.00", "USD");
        ResponseEntity<JsonNode> second = pay(key, source, destination, "40.00", "USD");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(id(second)).isEqualTo(id(first));
        assertThat(second.getBody().get("entries")).hasSize(2);

        assertThat(countTransactionsWithKey(key)).isEqualTo(1);
        assertThat(countEntries(id(first))).isEqualTo(2);
        assertThat(balance(source)).isEqualByComparingTo("60.00");
        assertThat(balance(destination)).isEqualByComparingTo("40.00");
    }

    @Test
    void numericallyEqualAmountsAreTreatedAsTheSameRequest() {
        UUID source = createFundedAccount("USD", "100");
        UUID destination = createAccount("USD", false);
        String key = newKey();

        ResponseEntity<JsonNode> first = pay(key, source, destination, "10", "USD");
        ResponseEntity<JsonNode> second = pay(key, source, destination, "10.0000", "USD");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(id(second)).isEqualTo(id(first));
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();
        pay(key, source, destination, "10.00", "USD");

        ResponseEntity<JsonNode> conflicting = pay(key, source, destination, "11.00", "USD");

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflicting.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(countTransactionsWithKey(key)).isEqualTo(1);
        assertThat(balance(destination)).isEqualByComparingTo("10.00");
    }

    @Test
    void reusingPaymentKeyForRefundIsRejected() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();
        ResponseEntity<JsonNode> payment = pay(key, source, destination, "10.00", "USD");

        ResponseEntity<JsonNode> refund = refund(key, id(payment));

        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refund.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);

        ResponseEntity<JsonNode> response = postTransaction(null, Map.of(
                "sourceAccountId", source, "destinationAccountId", destination, "amount", "1.00", "currency", "USD"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("MISSING_HEADER");
        assertThat(balance(destination)).isEqualByComparingTo("0");
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);

        ResponseEntity<JsonNode> response = pay("   ", source, destination, "1.00", "USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void failedTransactionIsAlsoReplayedForTheSameKey() {
        UUID source = createAccount("USD", false); // zero balance
        UUID destination = createAccount("USD", false);
        String key = newKey();

        ResponseEntity<JsonNode> first = pay(key, source, destination, "5.00", "USD");
        ResponseEntity<JsonNode> second = pay(key, source, destination, "5.00", "USD");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("status").asText()).isEqualTo("FAILED");
        assertThat(first.getBody().get("failureReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(id(second)).isEqualTo(id(first));
        assertThat(countEntries(id(first))).isZero();
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneTransaction() throws Exception {
        UUID source = createFundedAccount("USD", "1000.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();
        int threads = 16;

        List<ResponseEntity<JsonNode>> responses = runConcurrently(threads,
                () -> pay(key, source, destination, "25.00", "USD"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful())
                .as("status %s body %s", r.getStatusCode(), r.getBody()).isTrue());
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSize(threads - 1);
        assertThat(responses.stream().map(AbstractIntegrationTest::id).distinct()).hasSize(1);

        UUID transactionId = id(responses.getFirst());
        assertThat(countTransactionsWithKey(key)).isEqualTo(1);
        assertThat(countEntries(transactionId)).isEqualTo(2);
        assertThat(balance(source)).isEqualByComparingTo("975.00");
        assertThat(balance(destination)).isEqualByComparingTo("25.00");
    }

    @Test
    void concurrentRefundsWithSameKeyCreateExactlyOneRefund() throws Exception {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID paymentId = id(pay(newKey(), source, destination, "30.00", "USD"));
        String key = newKey();
        int threads = 12;

        List<ResponseEntity<JsonNode>> responses = runConcurrently(threads, () -> refund(key, paymentId));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful())
                .as("status %s body %s", r.getStatusCode(), r.getBody()).isTrue());
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses.stream().map(AbstractIntegrationTest::id).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where refund_of_id = ?",
                Integer.class, paymentId)).isEqualTo(1);
        assertThat(balance(source)).isEqualByComparingTo("100.00");
        assertThat(balance(destination)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Releases all tasks at the same instant to maximize contention. */
    static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
