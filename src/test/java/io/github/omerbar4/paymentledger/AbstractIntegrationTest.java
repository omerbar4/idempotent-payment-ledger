package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full application on a random port against a real PostgreSQL (Testcontainers).
 * Tests never delete data; each test creates its own accounts and uses random idempotency keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcTemplate jdbc;

    protected UUID createAccount(String currency, boolean allowNegative) {
        ResponseEntity<JsonNode> response = http.postForEntity("/accounts",
                Map.of("name", "acct-" + UUID.randomUUID(), "currency", currency, "allowNegativeBalance", allowNegative),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    /** Creates an account holding {@code amount}, funded from a fresh overdraft-enabled account. */
    protected UUID createFundedAccount(String currency, String amount) {
        UUID funding = createAccount(currency, true);
        UUID account = createAccount(currency, false);
        ResponseEntity<JsonNode> funded = pay(newKey(), funding, account, amount, currency);
        assertThat(funded.getBody().get("status").asText()).isEqualTo("POSTED");
        return account;
    }

    protected ResponseEntity<JsonNode> pay(String key, UUID from, UUID to, String amount, String currency) {
        return postTransaction(key, Map.of(
                "sourceAccountId", from, "destinationAccountId", to, "amount", amount, "currency", currency));
    }

    protected ResponseEntity<JsonNode> postTransaction(String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity("/transactions", new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> refund(String key, UUID transactionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/transactions/" + transactionId + "/refund", new HttpEntity<>(null, headers),
                JsonNode.class);
    }

    protected BigDecimal balance(UUID accountId) {
        return new BigDecimal(http.getForObject("/accounts/" + accountId, JsonNode.class).get("balance").asText());
    }

    protected static UUID id(ResponseEntity<JsonNode> response) {
        return UUID.fromString(response.getBody().get("id").asText());
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected int countTransactionsWithKey(String key) {
        return jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?", Integer.class, key);
    }

    protected int countEntries(UUID transactionId) {
        return jdbc.queryForObject("select count(*) from ledger_entries where transaction_id = ?", Integer.class,
                transactionId);
    }
}
