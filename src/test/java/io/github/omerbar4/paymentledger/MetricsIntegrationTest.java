package io.github.omerbar4.paymentledger;

import static io.github.omerbar4.paymentledger.IdempotencyIntegrationTest.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the custom payment-domain counters and the operational endpoints. Counters are shared
 * across the test JVM, so every assertion is on the delta produced by the test itself.
 * {@code @AutoConfigureObservability} re-enables metrics export (Spring Boot disables it in tests by
 * default), so the Prometheus endpoint is available exactly as in the running application.
 */
@AutoConfigureObservability(tracing = false)
class MetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Test
    void paymentOutcomesAreCountedByOutcome() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID empty = createAccount("USD", false);
        String key = newKey();
        double posted = transactions("payment", "posted");
        double replayed = transactions("payment", "replayed");
        double failed = transactions("payment", "failed");
        double keyReused = apiErrors("IDEMPOTENCY_KEY_REUSED");

        pay(key, source, destination, "10.00", "USD");             // posted
        pay(key, source, destination, "10.00", "USD");             // replayed
        pay(key, source, destination, "99.00", "USD");             // 422 key reused
        pay(newKey(), empty, destination, "1.00", "USD");          // failed: insufficient funds

        assertThat(transactions("payment", "posted") - posted).isEqualTo(1);
        assertThat(transactions("payment", "replayed") - replayed).isEqualTo(1);
        assertThat(transactions("payment", "failed") - failed).isEqualTo(1);
        assertThat(apiErrors("IDEMPOTENCY_KEY_REUSED") - keyReused).isEqualTo(1);
    }

    @Test
    void refundOutcomesAreCounted() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        UUID paymentId = id(pay(newKey(), source, destination, "20.00", "USD"));
        String refundKey = newKey();
        double posted = transactions("refund", "posted");
        double replayed = transactions("refund", "replayed");
        double alreadyRefunded = apiErrors("TRANSACTION_ALREADY_REFUNDED");

        refund(refundKey, paymentId);                              // posted
        refund(refundKey, paymentId);                              // replayed
        refund(newKey(), paymentId);                               // 409 already refunded

        assertThat(transactions("refund", "posted") - posted).isEqualTo(1);
        assertThat(transactions("refund", "replayed") - replayed).isEqualTo(1);
        assertThat(apiErrors("TRANSACTION_ALREADY_REFUNDED") - alreadyRefunded).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyBurstCountsOnePostedAndTheRestReplayed() throws Exception {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();
        int threads = 10;
        double posted = transactions("payment", "posted");
        double replayed = transactions("payment", "replayed");

        runConcurrently(threads, () -> pay(key, source, destination, "5.00", "USD"));

        assertThat(transactions("payment", "posted") - posted).isEqualTo(1);
        assertThat(transactions("payment", "replayed") - replayed).isEqualTo(threads - 1);
    }

    @Test
    void prometheusEndpointExposesHttpAndLedgerMetricsWithoutSensitiveValues() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = "metrics-sensitive-" + UUID.randomUUID();
        UUID transactionId = id(postTransaction(key, Map.of("sourceAccountId", source,
                "destinationAccountId", destination, "amount", "12.34", "currency", "USD",
                "description", "secret-description-marker")));

        ResponseEntity<String> scrape = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = scrape.getBody();
        assertThat(body)
                .contains("http_server_requests_seconds_count{")
                .contains("http_server_requests_seconds_bucket{")
                .contains("uri=\"/transactions\"")
                .contains("ledger_transactions_total{")
                .contains("outcome=\"posted\"")
                .contains("ledger_api_errors_total{")
                .contains("application=\"idempotent-payment-ledger\"");
        // No identifiers or request content in metric names or tags.
        assertThat(body)
                .doesNotContain(key)
                .doesNotContain(transactionId.toString())
                .doesNotContain(source.toString())
                .doesNotContain("secret-description-marker")
                .doesNotContain("12.34");
    }

    @Test
    void healthEndpointReportsDatabaseAndProbes() {
        JsonNode health = http.getForObject("/actuator/health", JsonNode.class);
        assertThat(health.get("status").asText()).isEqualTo("UP");
        assertThat(health.at("/components/db/status").asText()).isEqualTo("UP");

        assertThat(http.getForObject("/actuator/health/liveness", JsonNode.class).get("status").asText())
                .isEqualTo("UP");
        assertThat(http.getForObject("/actuator/health/readiness", JsonNode.class).get("status").asText())
                .isEqualTo("UP");
    }

    private double transactions(String type, String outcome) {
        Counter counter = registry.find("ledger.transactions").tags("type", type, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private double apiErrors(String code) {
        Counter counter = registry.find("ledger.api.errors").tag("code", code).counter();
        return counter == null ? 0 : counter.count();
    }
}
