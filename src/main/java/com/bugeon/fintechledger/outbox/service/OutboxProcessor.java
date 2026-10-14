package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox relay: claim → publish → complete, each in its own short step.
 *
 *  1. claim    (one DB tx)  lock due PENDING rows with SKIP LOCKED and mark them PROCESSING
 *  2. publish  (no DB tx)   send to the broker; no connection is held during network I/O
 *  3. complete (one DB tx)  conditional UPDATE to SENT — or reschedule with exponential backoff
 *
 * A crash between 1 and 3 leaves the row PROCESSING; {@link #releaseStaleClaims()} returns it
 * to PENDING after {@code outbox.stale-claim-ms} (or parks it once retries are exhausted).
 * Delivery is therefore at-least-once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private static final int MAX_BACKOFF_EXPONENT = 6;

    private final OutboxEventRepository repository;
    private final OutboxPublisher       publisher;
    private final OutboxProperties      properties;
    private final TransactionTemplate   transactionTemplate;

    /** A batch must finish (worst case: every send times out) well before its claim is considered dead. */
    @PostConstruct
    void validateTimings() {
        long worstCaseBatchMs = (long) properties.getBatchSize() * properties.getSendTimeoutMs();
        if (worstCaseBatchMs >= properties.getStaleClaimMs()) {
            throw new IllegalStateException("outbox.stale-claim-ms (" + properties.getStaleClaimMs()
                    + ") must exceed outbox.batch-size x outbox.send-timeout-ms (" + worstCaseBatchMs + ")");
        }
    }

    /**
     * One relay cycle. On the first publish failure the rest of the batch is returned to the
     * queue untouched: the broker is likely unavailable, and waiting out every send timeout
     * would only delay the next cycle (and the other scheduled jobs).
     *
     * @return number of events claimed in this cycle
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public int poll() {
        List<OutboxEvent> claimed = claimBatch();
        for (int i = 0; i < claimed.size(); i++) {
            if (!dispatch(claimed.get(i))) {
                List<UUID> untried = claimed.subList(i + 1, claimed.size()).stream().map(OutboxEvent::getId).toList();
                if (!untried.isEmpty()) {
                    repository.unclaim(untried, claimed.get(i).getClaimedAt(), EventStatus.PROCESSING, EventStatus.PENDING);
                }
                break;
            }
        }
        return claimed.size();
    }

    @Scheduled(fixedDelayString = "${outbox.stale-check-interval-ms:60000}")
    public int releaseStaleClaims() {
        Instant now = Instant.now();
        Instant staleBefore = now.minusMillis(properties.getStaleClaimMs());
        int parked = repository.parkStaleClaims(staleBefore, properties.getMaxRetries(),
                EventStatus.PROCESSING, EventStatus.FAILED);
        int released = repository.releaseStaleClaims(staleBefore, now, EventStatus.PROCESSING, EventStatus.PENDING);
        if (parked + released > 0) {
            log.warn("Stale outbox claims: {} released for re-delivery, {} parked as FAILED", released, parked);
        }
        return released + parked;
    }

    List<OutboxEvent> claimBatch() {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            List<OutboxEvent> due = repository.lockDueEvents(
                    EventStatus.PENDING, now, PageRequest.of(0, properties.getBatchSize()));
            due.forEach(event -> event.markProcessing(now));
            return due;
        });
    }

    /** @return whether the event was published */
    private boolean dispatch(OutboxEvent event) {
        try {
            publisher.publish(event);
        } catch (Exception e) {
            release(event, e);
            return false;
        }
        if (repository.completeClaim(event.getId(), event.getClaimedAt(), Instant.now(),
                EventStatus.PROCESSING, EventStatus.SENT) == 0) {
            log.warn("Outbox event {} was re-claimed before completion; it may be delivered again", event.getId());
        }
        return true;
    }

    private void release(OutboxEvent event, Exception cause) {
        boolean exhausted = event.getRetryCount() + 1 >= properties.getMaxRetries();
        long backoffMs = properties.getBaseBackoffMs()
                * (1L << Math.min(event.getRetryCount(), MAX_BACKOFF_EXPONENT));
        repository.releaseClaim(event.getId(), event.getClaimedAt(),
                exhausted ? EventStatus.FAILED : EventStatus.PENDING,
                Instant.now().plusMillis(backoffMs), EventStatus.PROCESSING);
        if (exhausted) {
            log.error("Outbox event {} ({}) failed permanently after {} attempts",
                    event.getId(), event.getEventType(), event.getRetryCount() + 1, cause);
        } else {
            log.warn("Outbox event {} publish failed (attempt {}), retry in {} ms: {}",
                    event.getId(), event.getRetryCount() + 1, backoffMs, cause.toString());
        }
    }
}
