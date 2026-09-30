package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.LedgerTransaction;
import java.sql.Timestamp;
import java.sql.Types;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Uses {@code ON CONFLICT DO NOTHING} rather than "check then insert". If two requests with the
 * same key race, PostgreSQL makes the second insert wait on the unique index until the first
 * transaction commits or rolls back, then either skips the insert (key taken) or proceeds. No
 * exception is raised, so the surrounding transaction stays usable and can load the winner's row.
 */
class IdempotentInsertRepositoryImpl implements IdempotentInsertRepository {

    private static final String SQL = """
            INSERT INTO transactions (id, idempotency_key, request_hash, type, status,
                                      source_account_id, destination_account_id, amount, currency,
                                      description, refund_of_id, created_at, updated_at)
            VALUES (:id, :idempotencyKey, :requestHash, :type, :status,
                    :sourceAccountId, :destinationAccountId, :amount, :currency,
                    :description, :refundOfId, :createdAt, :updatedAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    IdempotentInsertRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(LedgerTransaction t) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", t.getId())
                .addValue("idempotencyKey", t.getIdempotencyKey())
                .addValue("requestHash", t.getRequestHash())
                .addValue("type", t.getType().name())
                .addValue("status", t.getStatus().name())
                .addValue("sourceAccountId", t.getSourceAccountId())
                .addValue("destinationAccountId", t.getDestinationAccountId())
                .addValue("amount", t.getAmount())
                .addValue("currency", t.getCurrency())
                .addValue("description", t.getDescription(), Types.VARCHAR)
                .addValue("refundOfId", t.getRefundOfId(), Types.OTHER)
                .addValue("createdAt", Timestamp.from(t.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(t.getUpdatedAt()));
        return jdbc.update(SQL, params) == 1;
    }
}
