package com.bugeon.fintechledger.outbox.repository;

import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetches PENDING events due for processing.
     *
     * FOR UPDATE SKIP LOCKED:
     *  - Locks selected rows so no other instance processes them.
     *  - SKIP LOCKED skips rows already locked by a concurrent transaction,
     *    enabling safe parallel polling across multiple app instances.
     *
     * Must be called within an active @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PENDING'
          AND o.scheduledAt <= :now
        ORDER BY o.scheduledAt ASC
        LIMIT :batchSize
        """)
    List<OutboxEvent> findPendingForProcessing(@Param("now") Instant now,
                                               @Param("batchSize") int batchSize);

    List<OutboxEvent> findAllByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, UUID aggregateId);

    long countByStatus(EventStatus status);

    /**
     * Atomically resets stale {@code PROCESSING} events back to {@code PENDING}.
     *
     * <p>An event becomes stuck when the application crashes <em>after</em>
     * {@code markProcessing()} committed but <em>before</em> {@code markSent()}
     * ran. Without this reset those rows would stay {@code PROCESSING} forever and
     * {@link #findPendingForProcessing} (which only sees {@code PENDING}) would
     * never pick them up again — the event would be lost (At-Most-Once).
     *
     * <p>This is a single bulk UPDATE (not a select-then-mutate), so the recovery
     * is atomic and strictly better than the previous select-loop approach whose
     * entity mutations were never flushed to the DB. Each stale row is reset to
     * {@code PENDING} with {@code scheduledAt = now} (so the next poll immediately
     * re-claims it) and {@code retryCount + 1}.
     *
     * <p><b>Must be called within a transaction</b> — JPA rejects update/delete
     * queries without one (see {@code OutboxProcessor#recoverStaleProcessingEvents()},
     * which is annotated {@code @Transactional}).
     *
     * @param pending        target status — {@link EventStatus#PENDING}
     * @param processing     stuck status — {@link EventStatus#PROCESSING}
     * @param now            new {@code scheduled_at} value (immediate re-processing)
     * @param staleThreshold events with {@code scheduled_at < threshold} are stale
     * @return number of rows recovered
     */
    @Modifying
    @Query("""
        UPDATE OutboxEvent o
           SET o.status = :pending,
               o.scheduledAt = :now,
               o.retryCount = o.retryCount + 1
         WHERE o.status = :processing
           AND o.scheduledAt < :staleThreshold
        """)
    int recoverStaleProcessingEvents(@Param("pending") EventStatus pending,
                                     @Param("processing") EventStatus processing,
                                     @Param("now") Instant now,
                                     @Param("staleThreshold") Instant staleThreshold);
}
