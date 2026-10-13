package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.account.service.SettlementAccounts;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.common.json.Json;
import com.bugeon.fintechledger.ledger.service.LedgerService;
import com.bugeon.fintechledger.outbox.service.OutboxService;
import com.bugeon.fintechledger.transaction.domain.MoneyCommand;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionType;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes one money movement atomically: balances, transaction row, both ledger legs,
 * outbox event and success audit commit together — or not at all.
 *
 * Concurrency: both accounts are row-locked by one PESSIMISTIC_WRITE select ordered by id.
 * PostgreSQL sorts before it locks, so every movement acquires its locks in the same global
 * order and movements touching the same accounts serialize without deadlocking.
 * The transaction row is flushed before the ledger legs, so a concurrent duplicate of the same
 * idempotency key hits the unique constraint, rolls back, and never moves money twice.
 */
@Component
@RequiredArgsConstructor
public class TransactionProcessor {

    private static final int MEMO_MAX_LENGTH = 255;

    private final AccountRepository     accountRepository;
    private final TransactionRepository transactionRepository;
    private final SettlementAccounts    settlementAccounts;
    private final LedgerService         ledgerService;
    private final OutboxService         outboxService;
    private final AuditService          auditService;

    @Transactional
    public Transaction execute(String idempotencyKey, MoneyCommand command, UserPrincipal actor) {
        UUID debitedId  = command.type() == TransactionType.DEPOSIT
                ? settlementAccounts.idFor(command.currency()) : command.sourceAccountId();
        UUID creditedId = command.type() == TransactionType.WITHDRAWAL
                ? settlementAccounts.idFor(command.currency()) : command.targetAccountId();

        Map<UUID, Account> locked = lockInIdOrder(debitedId, creditedId);
        Account debited  = locked.get(debitedId);
        Account credited = locked.get(creditedId);
        MovementRules.verify(command, debited, credited, actor.getUserId());

        debited.decreaseCachedBalance(command.amount());
        credited.increaseCachedBalance(command.amount());

        Transaction tx = transactionRepository.saveAndFlush(
                Transaction.pending(idempotencyKey, actor.getUserId(), command));
        ledgerService.post(tx.getId(), debited, credited, command.amount(), command.currency(), memo(command));
        tx.markSuccess();

        outboxService.enqueueTransactionCompleted(tx);
        auditService.logSuccess(successAction(command.type()), actor.getUserId(), actor.getEmail(),
                "TRANSACTION", tx.getId(), Json.of(
                        "type", command.type(),
                        "sourceAccountId", command.sourceAccountId(),
                        "targetAccountId", command.targetAccountId(),
                        "amount", command.amount().toPlainString(),
                        "currency", command.currency()));
        return tx;
    }

    private Map<UUID, Account> lockInIdOrder(UUID first, UUID second) {
        Map<UUID, Account> byId = accountRepository.findAllByIdInForUpdate(List.of(first, second)).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        if (byId.size() != 2) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return byId;
    }

    private static String memo(MoneyCommand command) {
        String memo = command.description() == null || command.description().isBlank()
                ? command.type().name()
                : command.type().name() + ": " + command.description();
        return memo.length() <= MEMO_MAX_LENGTH ? memo : memo.substring(0, MEMO_MAX_LENGTH);
    }

    private static AuditAction successAction(TransactionType type) {
        return switch (type) {
            case DEPOSIT    -> AuditAction.DEPOSIT;
            case WITHDRAWAL -> AuditAction.WITHDRAWAL;
            case TRANSFER   -> AuditAction.TRANSFER_COMPLETED;
        };
    }
}
