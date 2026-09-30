package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Locks the given accounts ({@code SELECT ... FOR UPDATE}) in a deterministic order (by id),
     * so two transfers touching the same pair of accounts in opposite directions cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
