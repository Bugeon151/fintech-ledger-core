package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Publishes to Kafka and waits for the broker acknowledgement, so an event is only marked
 * SENT once it is durably stored. Keyed by aggregate id to keep per-transaction ordering;
 * the outbox event id travels as a header for consumer-side de-duplication.
 */
@Component
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "kafka")
@RequiredArgsConstructor
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties              properties;

    @Override
    public void publish(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.getTopic(), event.getAggregateId().toString(), event.getPayload());
        record.headers()
              .add("eventId", bytes(event.getId().toString()))
              .add("eventType", bytes(event.getEventType()))
              .add("aggregateType", bytes(event.getAggregateType()));
        kafkaTemplate.send(record).get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
