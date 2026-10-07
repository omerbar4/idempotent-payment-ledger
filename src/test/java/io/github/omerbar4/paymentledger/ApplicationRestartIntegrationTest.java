package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Idempotency state lives in PostgreSQL, not in the application process. This test starts a
 * standalone instance of the application, creates a payment, shuts the instance down, starts a
 * brand-new instance against the same database and replays the request.
 */
class ApplicationRestartIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PostgreSQLContainer<?> postgres;

    private final TestRestTemplate client = new TestRestTemplate();

    @Test
    void idempotencyKeyAndLedgerSurviveAnApplicationRestart() {
        UUID source = createFundedAccount("USD", "100.00");
        UUID destination = createAccount("USD", false);
        String key = newKey();
        Map<String, Object> body = Map.of("sourceAccountId", source, "destinationAccountId", destination,
                "amount", "42.00", "currency", "USD");

        UUID transactionId;
        try (ConfigurableApplicationContext first = startInstance()) {
            ResponseEntity<JsonNode> created = post(baseUrl(first), key, body);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            transactionId = id(created);
        }

        try (ConfigurableApplicationContext second = startInstance()) {
            ResponseEntity<JsonNode> replay = post(baseUrl(second), key, body);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(id(replay)).isEqualTo(transactionId);

            JsonNode fetched = client.getForObject(baseUrl(second) + "/transactions/" + transactionId, JsonNode.class);
            assertThat(fetched.get("status").asText()).isEqualTo("POSTED");
            assertThat(fetched.get("entries")).hasSize(2);
        }

        assertThat(countTransactionsWithKey(key)).isEqualTo(1);
        assertThat(countEntries(transactionId)).isEqualTo(2);
        BigDecimal debits = jdbc.queryForObject("select sum(amount) from ledger_entries where transaction_id = ? "
                + "and direction = 'DEBIT'", BigDecimal.class, transactionId);
        BigDecimal credits = jdbc.queryForObject("select sum(amount) from ledger_entries where transaction_id = ? "
                + "and direction = 'CREDIT'", BigDecimal.class, transactionId);
        assertThat(debits).isEqualByComparingTo(credits).isEqualByComparingTo("42.00");
        assertThat(balance(source)).isEqualByComparingTo("58.00");
        assertThat(balance(destination)).isEqualByComparingTo("42.00");
    }

    /** A separate application instance (own context, connection pool and port) on the shared database. */
    private ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(LedgerApplication.class)
                // Keep test-only configuration (e.g. the Testcontainers bean) out of the standalone instance.
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("excludeTestConfiguration",
                        new ExcludeTestConfiguration()))
                // Command-line arguments take precedence over application.yml.
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
    }

    private static String baseUrl(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    private ResponseEntity<JsonNode> post(String baseUrl, String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return client.postForEntity(baseUrl + "/transactions", new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** Component-scan filter that skips {@code @TestConfiguration} classes on the test classpath. */
    private static final class ExcludeTestConfiguration extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
        }
    }
}
