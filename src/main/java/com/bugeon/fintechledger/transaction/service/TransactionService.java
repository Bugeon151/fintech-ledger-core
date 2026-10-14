package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.common.json.Json;
import com.bugeon.fintechledger.common.persistence.Constraints;
import com.bugeon.fintechledger.transaction.domain.MoneyCommand;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.domain.TransactionType;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Money-movement use cases and their idempotency semantics.
 *
 * Deliberately NOT transactional: the atomic work runs in {@link TransactionProcessor}.
 * Keeping the orchestration outside that transaction lets failures be recorded and
 * duplicates be resolved after the business transaction has fully rolled back.
 *
 * Idempotency (per requester and key):
 *  - first request          → executed; 201 with the result
 *  - same key, same payload → the stored result is replayed (a stored failure replays its error)
 *  - same key, other payload→ 409 IDEMPOTENCY_KEY_CONFLICT
 *  - concurrent duplicates  → exactly one executes; the others replay its outcome
 *  - legacy FAILED rows     → replayed as TRANSACTION_FAILED
 *  - validation errors      → nothing stored; the key stays usable
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    /** Rejections that happen after authorization. Recorded so retries replay them. */
    private static final Set<ErrorCode> RECORDED_FAILURES = EnumSet.of(
            ErrorCode.INSUFFICIENT_FUNDS, ErrorCode.ACCOUNT_FROZEN,
            ErrorCode.ACCOUNT_CLOSED, ErrorCode.CURRENCY_MISMATCH);

    private final TransactionProcessor       processor;
    private final TransactionFailureRecorder failureRecorder;
    private final TransactionRepository      transactionRepository;
    private final AccountRepository          accountRepository;
    private final AuditService               auditService;

    // NEVER: joining a caller's transaction would break failure recording and duplicate replay.

    @Transactional(propagation = Propagation.NEVER)
    public TransactionResponse deposit(String idempotencyKey, DepositRequest request, UserPrincipal actor) {
        return submit(idempotencyKey, request.toCommand(), actor);
    }

    @Transactional(propagation = Propagation.NEVER)
    public TransactionResponse withdraw(String idempotencyKey, WithdrawRequest request, UserPrincipal actor) {
        return submit(idempotencyKey, request.toCommand(), actor);
    }

    @Transactional(propagation = Propagation.NEVER)
    public TransactionResponse transfer(String idempotencyKey, TransferRequest request, UserPrincipal actor) {
        return submit(idempotencyKey, request.toCommand(), actor);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID accountId, UserPrincipal viewer, Pageable pageable) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.validateOwner(viewer.getUserId());
        return transactionRepository
                .findHistory(accountId, viewer.getUserId(), TransactionStatus.SUCCESS, pageable)
                .map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse findByIdempotencyKeyForUser(String idempotencyKey, UserPrincipal actor) {
        return find(idempotencyKey, actor)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
    }

    // ── Orchestration ────────────────────────────────────────────────────────

    private TransactionResponse submit(String idempotencyKey, MoneyCommand command, UserPrincipal actor) {
        command.validate();

        Optional<Transaction> previous = find(idempotencyKey, actor);
        if (previous.isPresent()) {
            return replay(previous.get(), command, actor);
        }
        try {
            preflight(command, actor);
            return TransactionResponse.from(processor.execute(idempotencyKey, command, actor));
        } catch (DataIntegrityViolationException e) {
            if (!Constraints.isViolation(e, Constraints.TRANSACTION_IDEMPOTENCY_KEY)) {
                throw e;
            }
            log.info("Concurrent duplicate resolved by replay: key={}", idempotencyKey);
            return replay(find(idempotencyKey, actor).orElseThrow(() -> e), command, actor);
        } catch (BusinessException e) {
            return recordFailure(idempotencyKey, command, actor, e);
        }
    }

    private TransactionResponse recordFailure(String idempotencyKey, MoneyCommand command,
                                              UserPrincipal actor, BusinessException cause) {
        UUID failedTransactionId = null;
        if (RECORDED_FAILURES.contains(cause.getErrorCode())) {
            try {
                failedTransactionId = failureRecorder.record(idempotencyKey, actor.getUserId(), command, cause).getId();
            } catch (DataIntegrityViolationException e) {
                if (Constraints.isViolation(e, Constraints.TRANSACTION_IDEMPOTENCY_KEY)) {
                    // A concurrent request with this key committed first; its outcome is the answer.
                    Optional<Transaction> winner = find(idempotencyKey, actor);
                    if (winner.isPresent()) {
                        return replay(winner.get(), command, actor);
                    }
                }
                log.warn("Could not record FAILED transaction: key={}", idempotencyKey, e);
            } catch (RuntimeException e) {
                // The rejection stands even if it cannot be recorded; never turn a 4xx into a 500.
                log.error("Could not record FAILED transaction: key={}", idempotencyKey, e);
            }
        }
        UUID targetId = failedTransactionId;
        bestEffortAudit(() -> auditService.logFailure(failureAction(command.type()),
                actor.getUserId(), actor.getEmail(), "TRANSACTION", targetId, Json.of(
                        "code", cause.getErrorCode().getCode(),
                        "reason", cause.getMessage(),
                        "sourceAccountId", command.sourceAccountId(),
                        "targetAccountId", command.targetAccountId(),
                        "amount", command.amount().toPlainString(),
                        "currency", command.currency())));
        throw cause;
    }

    private TransactionResponse replay(Transaction previous, MoneyCommand command, UserPrincipal actor) {
        if (!command.matches(previous)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        bestEffortAudit(() -> auditService.logSuccess(AuditAction.IDEMPOTENT_REQUEST,
                actor.getUserId(), actor.getEmail(), "TRANSACTION", previous.getId(),
                Json.of("status", previous.getStatus())));
        if (previous.getStatus() != TransactionStatus.SUCCESS) {
            ErrorCode code = previous.failureErrorCode().orElse(ErrorCode.TRANSACTION_FAILED);
            throw new BusinessException(code,
                    previous.getFailureReason() != null ? previous.getFailureReason() : code.getDefaultMessage());
        }
        return TransactionResponse.from(previous);
    }

    /** Cheap, lock-free rejection of requests that can never succeed (see {@link MovementRules}). */
    private void preflight(MoneyCommand command, UserPrincipal actor) {
        List<UUID> ids = Stream.of(command.sourceAccountId(), command.targetAccountId()).filter(Objects::nonNull).toList();
        Map<UUID, Account> accounts = accountRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        MovementRules.verify(command, accounts.get(command.sourceAccountId()),
                accounts.get(command.targetAccountId()), actor.getUserId());
    }

    private Optional<Transaction> find(String idempotencyKey, UserPrincipal actor) {
        return transactionRepository.findByIdempotencyKeyAndRequestedBy(idempotencyKey, actor.getUserId());
    }

    /** The money outcome is already decided; an audit outage must not turn it into an error. */
    private void bestEffortAudit(Runnable audit) {
        try {
            audit.run();
        } catch (RuntimeException e) {
            log.error("Audit write failed", e);
        }
    }

    private static AuditAction failureAction(TransactionType type) {
        return switch (type) {
            case DEPOSIT    -> AuditAction.DEPOSIT_FAILED;
            case WITHDRAWAL -> AuditAction.WITHDRAWAL_FAILED;
            case TRANSFER   -> AuditAction.TRANSFER_FAILED;
        };
    }
}
