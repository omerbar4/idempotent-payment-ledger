package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionIdOrderByDirectionDesc(UUID transactionId);
}
