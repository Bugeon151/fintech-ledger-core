package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.outbox.domain.OutboxEvent;

/** Delivers one outbox event to the outside world. Must throw if delivery is not confirmed. */
public interface OutboxPublisher {

    void publish(OutboxEvent event) throws Exception;
}
