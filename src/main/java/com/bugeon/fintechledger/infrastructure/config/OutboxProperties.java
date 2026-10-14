package com.bugeon.fintechledger.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox")
@Getter
@Setter
public class OutboxProperties {

    /** {@code kafka} in deployed environments, {@code log} for local development. */
    private String publisher = "log";
    private String topic = "ledger.transaction-events";
    private long pollIntervalMs = 1_000;
    private int batchSize = 25;
    private int maxRetries = 5;
    private long baseBackoffMs = 5_000;
    private long sendTimeoutMs = 10_000;
    /** A claim older than this is assumed dead and released. Must exceed batch-size x send-timeout-ms. */
    private long staleClaimMs = 300_000;
}
