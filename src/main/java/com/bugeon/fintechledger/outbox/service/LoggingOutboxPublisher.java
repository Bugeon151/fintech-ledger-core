package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local-development publisher (outbox.publisher=log): writes events to the log instead of a broker. */
@Component
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "log", matchIfMissing = true)
@Slf4j
public class LoggingOutboxPublisher implements OutboxPublisher {

    @Override
    public void publish(OutboxEvent event) {
        log.info("Outbox event (log publisher): id={} type={} aggregate={}/{} payload={}",
                event.getId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getPayload());
    }
}
