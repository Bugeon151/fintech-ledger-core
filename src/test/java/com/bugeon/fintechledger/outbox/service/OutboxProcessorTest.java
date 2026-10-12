package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @Mock OutboxEventRepository      repository;
    @Mock OutboxPublisher            publisher;
    @Mock PlatformTransactionManager transactionManager;

    private final OutboxProperties properties = new OutboxProperties();
    private OutboxProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        processor = new OutboxProcessor(repository, publisher, properties, new TransactionTemplate(transactionManager));
    }

    @Test
    void claimsPublishesAndCompletesWithTheClaimTimestamp() throws Exception {
        OutboxEvent event = event(0);
        given(repository.lockDueEvents(eq(EventStatus.PENDING), any(), any())).willReturn(List.of(event));
        given(repository.completeClaim(any(), any(), any(), any(), any())).willReturn(1);

        assertThat(processor.poll()).isEqualTo(1);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PROCESSING);
        assertThat(event.getClaimedAt()).isNotNull();
        then(publisher).should().publish(event);
        then(repository).should().completeClaim(eq(event.getId()), eq(event.getClaimedAt()), any(),
                eq(EventStatus.PROCESSING), eq(EventStatus.SENT));
    }

    @Test
    void reschedulesWithExponentialBackoffWhenPublishingFails() throws Exception {
        OutboxEvent event = event(2);
        given(repository.lockDueEvents(any(), any(), any())).willReturn(List.of(event));
        willThrow(new IllegalStateException("broker down")).given(publisher).publish(event);
        Instant before = Instant.now();

        processor.poll();

        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        then(repository).should().releaseClaim(eq(event.getId()), eq(event.getClaimedAt()),
                eq(EventStatus.PENDING), retryAt.capture(), eq(EventStatus.PROCESSING));
        assertThat(Duration.between(before, retryAt.getValue()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(properties.getBaseBackoffMs() * 4));
        then(repository).should(never()).completeClaim(any(), any(), any(), any(), any());
    }

    @Test
    void parksTheEventAfterTheLastRetry() throws Exception {
        OutboxEvent event = event(properties.getMaxRetries() - 1);
        given(repository.lockDueEvents(any(), any(), any())).willReturn(List.of(event));
        willThrow(new IllegalStateException("broker down")).given(publisher).publish(event);

        processor.poll();

        then(repository).should().releaseClaim(eq(event.getId()), any(), eq(EventStatus.FAILED), any(),
                eq(EventStatus.PROCESSING));
    }

    @Test
    void abortsTheRestOfTheBatchOnTheFirstFailure() throws Exception {
        OutboxEvent first = event(0);
        OutboxEvent second = event(0);
        OutboxEvent third = event(0);
        given(repository.lockDueEvents(any(), any(), any())).willReturn(List.of(first, second, third));
        willThrow(new IllegalStateException("broker down")).given(publisher).publish(first);

        processor.poll();

        then(publisher).should(never()).publish(second);
        then(repository).should().unclaim(eq(List.of(second.getId(), third.getId())), eq(first.getClaimedAt()),
                eq(EventStatus.PROCESSING), eq(EventStatus.PENDING));
    }

    @Test
    void parksExhaustedStaleClaimsBeforeReleasingTheRest() {
        Instant before = Instant.now();

        processor.releaseStaleClaims();

        ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
        InOrder order = inOrder(repository);
        order.verify(repository).parkStaleClaims(staleBefore.capture(), eq(properties.getMaxRetries()),
                eq(EventStatus.PROCESSING), eq(EventStatus.FAILED));
        order.verify(repository).releaseStaleClaims(eq(staleBefore.getValue()), any(),
                eq(EventStatus.PROCESSING), eq(EventStatus.PENDING));
        assertThat(staleBefore.getValue()).isBeforeOrEqualTo(before.minusMillis(properties.getStaleClaimMs() - 1000));
    }

    @Test
    void refusesTimingsWhereABatchCanOutliveItsClaim() {
        properties.setBatchSize(100);
        properties.setSendTimeoutMs(10_000);
        properties.setStaleClaimMs(300_000);

        assertThatThrownBy(processor::validateTimings).isInstanceOf(IllegalStateException.class);
    }

    private static OutboxEvent event(int retryCount) {
        OutboxEvent event = OutboxEvent.create("TRANSACTION", UUID.randomUUID(), "MoneyDeposited", "{}");
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "retryCount", retryCount);
        return event;
    }
}
