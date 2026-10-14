package com.bugeon.fintechledger.transaction.repository;

import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKeyAndRequestedBy(String idempotencyKey, UUID requestedBy);

    /**
     * Account history as seen by {@code viewerId}: every successful movement on the account,
     * plus the viewer's own failed attempts (a recipient never sees someone else's failure).
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE (t.sourceAccountId = :accountId OR t.targetAccountId = :accountId)
              AND (t.status = :visibleStatus OR t.requestedBy = :viewerId)
            """)
    Page<Transaction> findHistory(@Param("accountId") UUID accountId,
                                  @Param("viewerId") UUID viewerId,
                                  @Param("visibleStatus") TransactionStatus visibleStatus,
                                  Pageable pageable);
}
