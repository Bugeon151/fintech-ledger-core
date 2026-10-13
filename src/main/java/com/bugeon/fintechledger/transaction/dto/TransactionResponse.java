package com.bugeon.fintechledger.transaction.dto;

import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID              transactionId,
        TransactionType   type,
        TransactionStatus status,
        UUID              fromAccountId,
        UUID              toAccountId,
        BigDecimal        amount,
        String            currency,
        String            description,
        String            failureCode,
        String            failureReason,
        Instant           createdAt,
        Instant           completedAt
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getSourceAccountId(),
                tx.getTargetAccountId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getDescription(),
                tx.getFailureCode(),
                tx.getFailureReason(),
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
