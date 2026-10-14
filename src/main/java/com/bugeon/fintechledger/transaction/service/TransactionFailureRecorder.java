package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.transaction.domain.MoneyCommand;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists a FAILED transaction for a business rejection.
 *
 * Called only after the business transaction has rolled back, with a brand-new entity —
 * never by merging the rolled-back one (whose row does not exist; Hibernate 6.5 would
 * re-insert it under a new id, 6.6+ throws). The returned id is therefore the id that
 * exists in the table, and the failure audit can reference it.
 */
@Component
@RequiredArgsConstructor
public class TransactionFailureRecorder {

    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction record(String idempotencyKey, UUID requestedBy, MoneyCommand command,
                              BusinessException cause) {
        return transactionRepository.saveAndFlush(Transaction.failed(
                idempotencyKey, requestedBy, command, cause.getErrorCode(), cause.getMessage()));
    }
}
