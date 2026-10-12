package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import com.bugeon.fintechledger.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxIT extends IntegrationTest {

    @Autowired OutboxProcessor       processor;
    @Autowired OutboxEventRepository repository;
    @Autowired OutboxProperties      properties;

    @BeforeEach
    void drain() {
        while (processor.poll() > 0) {
            // publish (log publisher) everything left by other tests
        }
    }

    @Test
    void concurrentPollersClaimDisjointBatches() throws Exception {
        Set<UUID> created = new HashSet<>();
        repository.saveAll(IntStream.range(0, 50)
                .mapToObj(i -> OutboxEvent.create("TEST", UUID.randomUUID(), "TestEvent", "{}"))
                .toList()).forEach(e -> created.add(e.getId()));

        int defaultBatch = properties.getBatchSize();
        properties.setBatchSize(30);
        try {
            Callable<List<UUID>> claim = () -> processor.claimBatch().stream().map(OutboxEvent::getId).toList();
            List<List<UUID>> claims = runConcurrently(List.of(claim, claim));

            assertThat(claims).extracting(List::size).containsExactlyInAnyOrder(30, 20);
            assertThat(claims.get(0)).doesNotContainAnyElementsOf(claims.get(1));
            Set<UUID> union = new HashSet<>(claims.get(0));
            union.addAll(claims.get(1));
            assertThat(union).isEqualTo(created);
        } finally {
            properties.setBatchSize(defaultBatch);
            jdbc.update("update outbox_events set status = 'SENT' where aggregate_type = 'TEST'");
        }
    }

    @Test
    void onlyClaimsThatAreActuallyOldAreReleased() {
        OutboxEvent liveClaim = repository.save(OutboxEvent.create("TEST", UUID.randomUUID(), "TestEvent", "{}"));
        OutboxEvent deadClaim = repository.save(OutboxEvent.create("TEST", UUID.randomUUID(), "TestEvent", "{}"));
        // Both waited 10 minutes in the queue; only one was claimed 10 minutes ago.
        jdbc.update("update outbox_events set status = 'PROCESSING', scheduled_at = now() - interval '10 minutes', "
                + "claimed_at = now() where id = ?", liveClaim.getId());
        jdbc.update("update outbox_events set status = 'PROCESSING', scheduled_at = now() - interval '10 minutes', "
                + "claimed_at = now() - interval '10 minutes' where id = ?", deadClaim.getId());

        assertThat(processor.releaseStaleClaims()).isEqualTo(1);

        assertThat(repository.findById(liveClaim.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.PROCESSING);
        OutboxEvent released = repository.findById(deadClaim.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(released.getRetryCount()).isEqualTo(1);
        jdbc.update("update outbox_events set status = 'SENT' where aggregate_type = 'TEST'");
    }

    @Test
    void pollPublishesAndMarksEventsSent() {
        OutboxEvent event = repository.save(OutboxEvent.create("TEST", UUID.randomUUID(), "TestEvent", "{\"n\":1}"));

        assertThat(processor.poll()).isEqualTo(1);

        OutboxEvent sent = repository.findById(event.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(EventStatus.SENT);
        assertThat(sent.getProcessedAt()).isNotNull();
    }
}
