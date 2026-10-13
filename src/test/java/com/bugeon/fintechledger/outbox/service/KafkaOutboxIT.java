package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import com.bugeon.fintechledger.support.IntegrationTest;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end outbox relay: a committed deposit ends up as a Kafka record. */
class KafkaOutboxIT extends IntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("outbox.publisher", () -> "kafka");
    }

    @Autowired OutboxProcessor       processor;
    @Autowired OutboxEventRepository repository;
    @Autowired OutboxProperties      properties;
    @Autowired ObjectMapper          objectMapper;

    @Test
    void committedDepositIsPublishedToKafka() throws Exception {
        UserPrincipal alice = newUser();
        UUID account = newAccount(alice, "KRW");
        TransactionResponse tx = transactionService.deposit(key(),
                new DepositRequest(account, new BigDecimal("7000"), "KRW", null), alice);

        while (processor.poll() > 0) {
            // relay everything that is due, including events left by other test classes
        }

        ConsumerRecord<String, String> record = consume(tx.transactionId().toString());
        assertThat(header(record, "eventType")).isEqualTo("MoneyDeposited");
        assertThat(objectMapper.readTree(record.value()).get("amount").asText()).isEqualTo("7000");
        assertThat(repository.findAllByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("TRANSACTION", tx.transactionId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(EventStatus.SENT);
                    assertThat(header(record, "eventId")).isEqualTo(e.getId().toString());
                });
    }

    private ConsumerRecord<String, String> consume(String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(properties.getTopic()));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No Kafka record with key " + key);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
