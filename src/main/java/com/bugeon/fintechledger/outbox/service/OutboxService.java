package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.common.json.Json;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes domain events to the outbox table inside the caller's transaction (MANDATORY),
 * so an event exists if and only if the money movement committed — no dual write.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    public static final String AGGREGATE_TRANSACTION = "TRANSACTION";

    private final OutboxEventRepository outboxEventRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransactionCompleted(Transaction tx) {
        String eventType = switch (tx.getType()) {
            case DEPOSIT    -> "MoneyDeposited";
            case WITHDRAWAL -> "MoneyWithdrawn";
            case TRANSFER   -> "TransferCompleted";
        };
        outboxEventRepository.save(OutboxEvent.create(AGGREGATE_TRANSACTION, tx.getId(), eventType, Json.of(
                "transactionId", tx.getId(),
                "type", tx.getType(),
                "sourceAccountId", tx.getSourceAccountId(),
                "targetAccountId", tx.getTargetAccountId(),
                "amount", tx.getAmount().toPlainString(),
                "currency", tx.getCurrency(),
                "completedAt", tx.getCompletedAt())));
    }
}
