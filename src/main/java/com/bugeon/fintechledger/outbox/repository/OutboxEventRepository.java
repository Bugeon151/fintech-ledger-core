package com.bugeon.fintechledger.outbox.repository;

import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Due events, locked with FOR UPDATE SKIP LOCKED (lock timeout -2): concurrent pollers
     * on other instances skip rows already being claimed instead of blocking on them.
     * Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.status = :status AND o.scheduledAt <= :now
            ORDER BY o.scheduledAt ASC
            """)
    List<OutboxEvent> lockDueEvents(@Param("status") EventStatus status,
                                    @Param("now") Instant now,
                                    Pageable batch);

    List<OutboxEvent> findAllByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, UUID aggregateId);

    long countByStatus(EventStatus status);

    /** Completes a claim. Matches only if this exact claim is still current (not reset by recovery). */
    @Transactional
    @Modifying
    @Query("""
            UPDATE OutboxEvent o
               SET o.status = :sent, o.processedAt = :now
             WHERE o.id = :id AND o.status = :processing AND o.claimedAt = :claimedAt
            """)
    int completeClaim(@Param("id") UUID id,
                      @Param("claimedAt") Instant claimedAt,
                      @Param("now") Instant now,
                      @Param("processing") EventStatus processing,
                      @Param("sent") EventStatus sent);

    /** Releases a failed claim: reschedules it (PENDING) or parks it (FAILED). */
    @Transactional
    @Modifying
    @Query("""
            UPDATE OutboxEvent o
               SET o.status = :next, o.scheduledAt = :retryAt, o.claimedAt = NULL,
                   o.retryCount = o.retryCount + 1
             WHERE o.id = :id AND o.status = :processing AND o.claimedAt = :claimedAt
            """)
    int releaseClaim(@Param("id") UUID id,
                     @Param("claimedAt") Instant claimedAt,
                     @Param("next") EventStatus next,
                     @Param("retryAt") Instant retryAt,
                     @Param("processing") EventStatus processing);

    /** Returns not-yet-attempted events of an aborted batch to the queue without counting a retry. */
    @Transactional
    @Modifying
    @Query("""
            UPDATE OutboxEvent o
               SET o.status = :pending, o.claimedAt = NULL
             WHERE o.id IN :ids AND o.status = :processing AND o.claimedAt = :claimedAt
            """)
    int unclaim(@Param("ids") List<UUID> ids,
                @Param("claimedAt") Instant claimedAt,
                @Param("processing") EventStatus processing,
                @Param("pending") EventStatus pending);

    /**
     * Resets claims whose holder died (crash between claim and completion) so the events are
     * re-published — at-least-once. Uses the claim time, not the schedule time.
     * Run {@link #parkStaleClaims} first so exhausted events are not retried forever.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE OutboxEvent o
               SET o.status = :pending, o.scheduledAt = :now, o.claimedAt = NULL,
                   o.retryCount = o.retryCount + 1
             WHERE o.status = :processing AND o.claimedAt < :staleBefore
            """)
    int releaseStaleClaims(@Param("staleBefore") Instant staleBefore,
                           @Param("now") Instant now,
                           @Param("processing") EventStatus processing,
                           @Param("pending") EventStatus pending);

    /** Stale claims that already used their last attempt are parked as FAILED instead of re-queued. */
    @Transactional
    @Modifying
    @Query("""
            UPDATE OutboxEvent o
               SET o.status = :failed, o.claimedAt = NULL, o.retryCount = o.retryCount + 1
             WHERE o.status = :processing AND o.claimedAt < :staleBefore
               AND o.retryCount + 1 >= :maxRetries
            """)
    int parkStaleClaims(@Param("staleBefore") Instant staleBefore,
                        @Param("maxRetries") int maxRetries,
                        @Param("processing") EventStatus processing,
                        @Param("failed") EventStatus failed);
}
