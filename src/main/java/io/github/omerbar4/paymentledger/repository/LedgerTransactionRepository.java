package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.LedgerTransaction;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, UUID>, IdempotentInsertRepository {

    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LedgerTransaction t where t.id = :id")
    Optional<LedgerTransaction> findByIdForUpdate(@Param("id") UUID id);
}
