package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional Outbox Processor.
 *
 * 동작 방식:
 *   1. PENDING OutboxEvent를 배치로 조회 (FOR UPDATE SKIP LOCKED) + PROCESSING 마킹.
 *      이 두 동작이 같은 TX에서 수행된다 — "클레임"의 원자성 보장.
 *   2. 각 이벤트를 개별 REQUIRES_NEW 트랜잭션에서 처리.
 *   3. 성공 시 SENT 커밋 → 실패 시 백오프 재스케줄 커밋.
 *
 * FIX C-4 — 클레임 원자성:
 *   이전에는 fetchPending()이 SELECT만 하고 즉시 TX를 커밋해 락을 풀어버렸다.
 *   게다가 process() 같은 클래스 내부에서 호출해 @Transactional 프록시가
 *   적용되지 않아(SELF-INVOCATION) FOR UPDATE SKIP LOCKED 락이 아무런
 *   보호를 못 했다. 두 인스턴스가 폴링하면 같은 이벤트를 중복 처리할 수 있었다.
 *
 *   수정:
 *     1. self 프록시를 통해 @Transactional 메서드를 호출 (프록시가 AOP 적용).
 *     2. claimPendingEvents()가 "락 획득 + PROCESSING 전환"을 하나의 TX로 처리.
 *        클레임 후에는 status='PROCESSING'이므로 다른 인스턴스의 PENDING 조회에
 *        잡히지 않는다 → 중복 발행 불가.
 *     3. 크래시로 PROCESSING에 고착된 이벤트는 recoverStaleProcessingEvents()가
 *        벌크 UPDATE로 PENDING 리셋 (At-Least-Once 복구).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private static final int  BATCH_SIZE      = 100;
    private static final long BASE_BACKOFF_MS = 5_000L;
    private static final int  MAX_BACKOFF_EXP = 5;

    private final OutboxEventRepository    outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxProperties          outboxProperties;

    /**
     * 셀프 프록시.
     * 같은 빈 내부에서 @Transactional 메서드를 호출할 때 Spring AOP 프록시를
     * 거치도록 하기 위한 자체 주입. (Lazy로 순환 참조 방지)
     */
    @Autowired @Lazy
    private OutboxProcessor self;

    /**
     * 폴링 스케줄러 — @Transactional 없이 실행.
     * 개별 이벤트 처리는 processSingleEvent()의 REQUIRES_NEW TX에서 처리한다.
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:5000}")
    public void process() {
        List<OutboxEvent> pending = self.claimPendingEvents();
        if (pending.isEmpty()) return;

        log.debug("OutboxProcessor: processing {} events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                self.processSingleEvent(event.getId());
            } catch (Exception e) {
                // 개별 이벤트 실패는 다음 이벤트 처리에 영향 없음
                log.error("OutboxProcessor: event {} failed in outer loop", event.getId(), e);
            }
        }
    }

    /**
     * PENDING 이벤트를 "클레임"한다.
     *
     * FOR UPDATE SKIP LOCKED로 락을 잡고, 같은 TX에서 즉시 PROCESSING으로
     * 전환한다. TX 커밋 시 락이 풀리더라도 status='PROCESSING'이므로 다른
     * 인스턴스의 PENDING 조회에 노출되지 않는다.
     *
     * self 프록시 경유로 호출해야 @Transactional이 적용된다.
     */
    @Transactional
    public List<OutboxEvent> claimPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingForProcessing(Instant.now(), BATCH_SIZE);
        for (OutboxEvent event : pending) {
            event.markProcessing();
        }
        log.debug("OutboxProcessor: claimed {} events", pending.size());
        return pending;
    }

    /**
     * 단일 이벤트를 독립 트랜잭션에서 처리.
     *
     * REQUIRES_NEW: 이 메서드의 트랜잭션은 호출자와 완전히 독립.
     * 성공 → SENT 상태로 커밋.
     * 실패 → 재시도 스케줄 커밋 (PENDING 상태 유지, scheduledAt 갱신).
     *
     * 클레임 시점에 이미 PROCESSING으로 전환되었으므로 별도 markProcessing은 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElse(null);

        // 다른 인스턴스가 이미 처리했을 수 있음
        if (event == null || event.getStatus().name().equals("SENT")) return;

        try {
            eventPublisher.publishEvent(new OutboxEventPublishedEvent(
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getPayload()
            ));

            event.markSent();
            log.info("Outbox event published: type={} aggregateId={}",
                    event.getEventType(), event.getAggregateId());

        } catch (Exception e) {
            log.error("Outbox event failed: id={} type={} retry={}",
                    event.getId(), event.getEventType(), event.getRetryCount(), e);

            if (event.getRetryCount() >= outboxProperties.getMaxRetries()) {
                event.markPermanentlyFailed();
                log.error("Outbox event permanently failed: id={}", event.getId());
            } else {
                int exp = Math.min(event.getRetryCount(), MAX_BACKOFF_EXP);
                long backoffMs = BASE_BACKOFF_MS * (1L << exp);
                event.markFailedAndReschedule(Instant.now().plusMillis(backoffMs));
            }
        }
    }

    // ── Stale PROCESSING recovery ─────────────────────────────────────────────

    /**
     * 고착된 PROCESSING 이벤트를 PENDING으로 원자적으로 복구.
     *
     * claimPendingEvents()가 PROCESSING 전환을 커밋한 뒤 앱이 다운되면
     * 해당 이벤트는 PROCESSING에 영원히 남는다. findPendingForProcessing()은
     * PENDING만 조회하므로 그대로 두면 이벤트가 유실된다(At-Most-Once).
     *
     * 복구는 단일 벌크 UPDATE의 관점으로 수행된다:
     *   5분 이상 고착된 PROCESSING 이벤트를 PENDING으로 리셋하고
     *   scheduledAt을 now로 되돌려 다음 폴링에서 즉시 재클레임되게 한다.
     *   (retryCount 1 증가)
     *
     * @Transactional을 명시하지 않으면 벌크 UPDATE가
     * "TransactionRequiredException: Executing an update/delete query"를
     * 던지므로 반드시 필요하다. 스케줄러는 프록시 빈을 통해 메서드를 호출하므로
     * @Transactional이 실제로 적용된다.
     *
     * 실행 주기: 1분마다
     */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void recoverStaleProcessingEvents() {
        // 5분 이상 PROCESSING 상태인 이벤트 = 크래시로 인한 고착으로 간주
        Instant staleThreshold = Instant.now().minusSeconds(300);
        int recovered = outboxEventRepository.recoverStaleProcessingEvents(
                EventStatus.PENDING, EventStatus.PROCESSING, Instant.now(), staleThreshold);

        if (recovered > 0) {
            log.warn("Recovered {} stale PROCESSING event(s) → PENDING (retryCount+1, scheduledAt=now)", recovered);
        }
    }

    // ── Inner event class ─────────────────────────────────────────────────────

    public record OutboxEventPublishedEvent(
            String aggregateType,
            UUID   aggregateId,
            String eventType,
            String payload
    ) {}
}
