package com.bugeon.fintechledger.outbox;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import com.bugeon.fintechledger.outbox.service.OutboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@DisplayName("Outbox 신뢰성 테스트")
@ExtendWith(MockitoExtension.class)
class OutboxReliabilityTest {

    @Mock OutboxEventRepository     outboxEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock OutboxProperties          outboxProperties;

    @InjectMocks OutboxProcessor outboxProcessor;

    private UUID eventId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        lenient().when(outboxProperties.getMaxRetries()).thenReturn(5);
        // 셀프 프록시를 같은 인스턴스로 연결 — process()가 claimPendingEvents()를 호출할 수 있게 함
        ReflectionTestUtils.setField(outboxProcessor, "self", outboxProcessor);
    }

    // ── 정상 처리 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 이벤트 처리")
    class NormalProcessing {

        @Test
        @DisplayName("PENDING → PROCESSING → SENT 상태 전이")
        void pendingEvent_publishSuccess_sentStatus() {
            OutboxEvent event = createEvent(eventId, "TransferCompleted", 0);
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(event));

            outboxProcessor.processSingleEvent(eventId);

            assertThat(event.getStatus()).isEqualTo(EventStatus.SENT);
            assertThat(event.getProcessedAt()).isNotNull();
            then(eventPublisher).should().publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));
        }

        @Test
        @DisplayName("SENT 이벤트 재처리 시도 — 발행하지 않음 (중복 발행 방지)")
        void sentEvent_notReprocessed() {
            OutboxEvent event = createEvent(eventId, "MoneyDeposited", 0);
            event.markSent();
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(event));

            outboxProcessor.processSingleEvent(eventId);

            then(eventPublisher).should(never()).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));
        }

        @Test
        @DisplayName("이벤트 없음 — 조용히 무시 (이미 처리됨)")
        void eventNotFound_silentlyIgnored() {
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.empty());

            assertThatNoException().isThrownBy(() ->
                    outboxProcessor.processSingleEvent(eventId));
            then(eventPublisher).should(never()).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));
        }
    }

    // ── 재시도 정책 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("지수 백오프 재시도 정책")
    class RetryPolicy {

        @Test
        @DisplayName("첫 번째 실패 — retryCount=1, PENDING 유지, 5초 후 재시도")
        void firstFailure_retryCount1_5sBackoff() {
            OutboxEvent event = createEvent(eventId, "TransferCompleted", 0);
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(event));
            willThrow(new RuntimeException("broker unavailable"))
                    .given(eventPublisher).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));

            Instant before = Instant.now();
            outboxProcessor.processSingleEvent(eventId);

            assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING);
            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getScheduledAt()).isAfter(before.plusMillis(4_000));
        }

        @Test
        @DisplayName("두 번째 실패 — 10초 백오프 (5s × 2^1)")
        void secondFailure_10sBackoff() {
            OutboxEvent event = createEvent(eventId, "TransferCompleted", 1);
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(event));
            willThrow(new RuntimeException("broker unavailable"))
                    .given(eventPublisher).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));

            Instant before = Instant.now();
            outboxProcessor.processSingleEvent(eventId);

            assertThat(event.getRetryCount()).isEqualTo(2);
            assertThat(event.getScheduledAt()).isAfter(before.plusMillis(9_000));
        }

        @Test
        @DisplayName("maxRetries 초과 — FAILED 영구 처리")
        void exceedsMaxRetries_permanentlyFailed() {
            OutboxEvent event = createEvent(eventId, "TransferCompleted", 5);
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(event));
            willThrow(new RuntimeException("permanent failure"))
                    .given(eventPublisher).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));

            outboxProcessor.processSingleEvent(eventId);

            assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);
        }
    }

    // ── 배치 독립 처리 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("배치 내 이벤트 독립 처리 (M-1 Fix)")
    class IndependentBatchProcessing {

        @Test
        @DisplayName("3개 배치 — 중간 실패해도 나머지 2개 SENT 처리")
        void batchOf3_middleFails_othersSucceed() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();

            OutboxEvent e1 = createEvent(id1, "MoneyDeposited",    0);
            OutboxEvent e2 = createEvent(id2, "TransferCompleted", 0);
            OutboxEvent e3 = createEvent(id3, "MoneyWithdrawn",    0);

            given(outboxEventRepository.findPendingForProcessing(any(), anyInt()))
                    .willReturn(List.of(e1, e2, e3));
            given(outboxEventRepository.findById(id1)).willReturn(Optional.of(e1));
            given(outboxEventRepository.findById(id2)).willReturn(Optional.of(e2));
            given(outboxEventRepository.findById(id3)).willReturn(Optional.of(e3));

            // 주의: 결코 any()만 쓰면 안 됨 — ApplicationEvent 오버로드로 컴파일되어
            // 실제 호출되는 publishEvent(Object)에 매칭되지 않는다.
            willDoNothing()
                    .willThrow(new RuntimeException("broker error"))
                    .willDoNothing()
                    .given(eventPublisher).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));

            outboxProcessor.process();

            assertThat(e1.getStatus()).isEqualTo(EventStatus.SENT);
            assertThat(e3.getStatus()).isEqualTo(EventStatus.SENT);
            assertThat(e2.getStatus()).isEqualTo(EventStatus.PENDING);  // 재시도 예약
            assertThat(e2.getRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("빈 배치 — 아무것도 처리하지 않음")
        void emptyBatch_nothingProcessed() {
            given(outboxEventRepository.findPendingForProcessing(any(), anyInt()))
                    .willReturn(List.of());

            outboxProcessor.process();

            then(eventPublisher).should(never()).publishEvent(any(OutboxProcessor.OutboxEventPublishedEvent.class));
        }
    }

    // ── 고착 이벤트 복구 (At-Least-Once 보장) ────────────────────────────────

    @Nested
    @DisplayName("고착 PROCESSING 이벤트 복구 — At-Least-Once 보장")
    class StaleProcessingRecovery {

        @Test
        @DisplayName("5분 이상 고착된 PROCESSING 이벤트를 PENDING으로 원자적 리셋 (크래시 복구)")
        void staleProcessingEvent_resetToPending() {
            long thresholdMin = Instant.now().minusSeconds(306).toEpochMilli();
            long thresholdMax = Instant.now().minusSeconds(294).toEpochMilli();
            Instant before = Instant.now();

            outboxProcessor.recoverStaleProcessingEvents();

            // 단일 벌크 UPDATE로만 복구 — 실제 DB 반영이 보장되는 유일한 경로
            then(outboxEventRepository).should().recoverStaleProcessingEvents(
                    eq(EventStatus.PENDING),
                    eq(EventStatus.PROCESSING),
                    argThat(now -> !now.isBefore(before)),
                    argThat(staleThreshold ->
                            staleThreshold.toEpochMilli() >= thresholdMin
                                    && staleThreshold.toEpochMilli() <= thresholdMax));
        }

        @Test
        @DisplayName("고착 이벤트 없음 — 복구 0건, 예외 없음")
        void noStaleEvents_noAction() {
            given(outboxEventRepository.recoverStaleProcessingEvents(any(), any(), any(), any()))
                    .willReturn(0);

            assertThatNoException().isThrownBy(() ->
                    outboxProcessor.recoverStaleProcessingEvents());
        }

        @Test
        @DisplayName("복구 시 scheduledAt=now로 리셋되어 다음 폴링에서 재클레임됨")
        void recoveredEvent_reprocessedInNextCycle() {
            ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
            given(outboxEventRepository.recoverStaleProcessingEvents(
                    eq(EventStatus.PENDING), eq(EventStatus.PROCESSING),
                    nowCaptor.capture(), any(Instant.class)))
                    .willReturn(1);

            Instant before = Instant.now();
            outboxProcessor.recoverStaleProcessingEvents();

            // scheduledAt = now(직후) → 다음 폴링 사이클에서 PENDING으로 다시 클레임됨
            then(outboxEventRepository).should().recoverStaleProcessingEvents(
                    eq(EventStatus.PENDING), eq(EventStatus.PROCESSING),
                    any(Instant.class), any(Instant.class));
            assertThat(nowCaptor.getValue())
                    .isAfterOrEqualTo(before)
                    .isBefore(Instant.now().plusSeconds(10));
        }
    }

    // ── 발행 내용 정확성 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("발행 이벤트 내용 정확성")
    class PublishedEventContent {

        @Test
        @DisplayName("발행 이벤트에 aggregateType, aggregateId, eventType, payload 포함")
        void publishedEvent_containsAllFields() {
            UUID txId = UUID.randomUUID();
            OutboxEvent event = createEventWithDetails(eventId, "TRANSACTION",
                    txId, "TransferCompleted", "{\"amount\":\"30000\"}");
            given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(event));

            ArgumentCaptor<OutboxProcessor.OutboxEventPublishedEvent> captor =
                    ArgumentCaptor.forClass(OutboxProcessor.OutboxEventPublishedEvent.class);

            outboxProcessor.processSingleEvent(eventId);

            then(eventPublisher).should().publishEvent(captor.capture());
            var published = captor.getValue();

            assertThat(published.aggregateType()).isEqualTo("TRANSACTION");
            assertThat(published.aggregateId()).isEqualTo(txId);
            assertThat(published.eventType()).isEqualTo("TransferCompleted");
            assertThat(published.payload()).contains("30000");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OutboxEvent createEvent(UUID id, String eventType, int initialRetries) {
        return createEventWithDetails(id, "TRANSACTION", UUID.randomUUID(),
                eventType, "{}", initialRetries);
    }

    private OutboxEvent createEventWithDetails(UUID id, String aggregateType,
                                               UUID aggregateId, String eventType,
                                               String payload) {
        return createEventWithDetails(id, aggregateType, aggregateId, eventType, payload, 0);
    }

    private OutboxEvent createEventWithDetails(UUID id, String aggregateType,
                                               UUID aggregateId, String eventType,
                                               String payload, int initialRetries) {
        OutboxEvent event = OutboxEvent.create(aggregateType, aggregateId, eventType, payload);
        for (int i = 0; i < initialRetries; i++) {
            event.markFailedAndReschedule(Instant.now());
        }
        try {
            var f = OutboxEvent.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(event, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return event;
    }
}
