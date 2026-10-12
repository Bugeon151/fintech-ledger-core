package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.common.persistence.Constraints;
import com.bugeon.fintechledger.transaction.domain.MoneyCommand;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/** Orchestration semantics: replay, conflict, failure recording, concurrent-duplicate resolution. */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final String KEY = "key-1";

    @Mock TransactionProcessor       processor;
    @Mock TransactionFailureRecorder failureRecorder;
    @Mock TransactionRepository      transactionRepository;
    @Mock AccountRepository          accountRepository;
    @Mock AuditService               auditService;
    @InjectMocks TransactionService  service;

    private UserPrincipal  actor;
    private UUID           accountId;
    private DepositRequest deposit;

    @BeforeEach
    void setUp() {
        actor     = UserPrincipal.of(UUID.randomUUID(), "alice@test.io");
        accountId = UUID.randomUUID();
        deposit   = new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null);
    }

    @Test
    void executesANewRequest() {
        givenOwnAccount();
        Transaction done = succeeded(deposit.toCommand());
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.empty());
        given(processor.execute(eq(KEY), any(), eq(actor))).willReturn(done);

        TransactionResponse response = service.deposit(KEY, deposit, actor);

        assertThat(response.transactionId()).isEqualTo(done.getId());
        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void replaysAStoredResultWithoutExecutingAgain() {
        Transaction done = succeeded(deposit.toCommand());
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.of(done));

        TransactionResponse response = service.deposit(KEY, deposit, actor);

        assertThat(response.transactionId()).isEqualTo(done.getId());
        then(processor).shouldHaveNoInteractions();
        then(auditService).should().logSuccess(eq(AuditAction.IDEMPOTENT_REQUEST), any(), any(), any(), eq(done.getId()), any());
    }

    @Test
    void rejectsAReusedKeyWithADifferentPayload() {
        Transaction done = succeeded(deposit.toCommand());
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.of(done));
        DepositRequest other = new DepositRequest(accountId, new BigDecimal("20000"), "KRW", null);

        assertThatThrownBy(() -> service.deposit(KEY, other, actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        then(processor).shouldHaveNoInteractions();
    }

    @Test
    void replaysAStoredFailureAsTheSameError() {
        WithdrawRequest withdraw = new WithdrawRequest(accountId, new BigDecimal("500"), "KRW", null);
        Transaction failed = withId(Transaction.failed(KEY, actor.getUserId(), withdraw.toCommand(),
                ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds"));
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.withdraw(KEY, withdraw, actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        then(processor).shouldHaveNoInteractions();
    }

    @Test
    void resolvesAConcurrentDuplicateByReplayingTheWinner() {
        givenOwnAccount();
        Transaction winner = succeeded(deposit.toCommand());
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId()))
                .willReturn(Optional.empty(), Optional.of(winner));
        given(processor.execute(eq(KEY), any(), eq(actor))).willThrow(idempotencyViolation());

        assertThat(service.deposit(KEY, deposit, actor).transactionId()).isEqualTo(winner.getId());
    }

    @Test
    void rethrowsUnrelatedIntegrityViolations() {
        givenOwnAccount();
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.empty());
        DataIntegrityViolationException other = new DataIntegrityViolationException("fk");
        given(processor.execute(eq(KEY), any(), eq(actor))).willThrow(other);

        assertThatThrownBy(() -> service.deposit(KEY, deposit, actor)).isSameAs(other);
    }

    @Test
    void recordsABusinessFailureAndAuditsTheStoredRow() {
        givenOwnAccount();
        WithdrawRequest withdraw = new WithdrawRequest(accountId, new BigDecimal("500"), "KRW", null);
        BusinessException rejection = new BusinessException(ErrorCode.INSUFFICIENT_FUNDS);
        Transaction recorded = withId(Transaction.failed(KEY, actor.getUserId(), withdraw.toCommand(),
                ErrorCode.INSUFFICIENT_FUNDS, rejection.getMessage()));
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.empty());
        given(processor.execute(eq(KEY), any(), eq(actor))).willThrow(rejection);
        given(failureRecorder.record(eq(KEY), eq(actor.getUserId()), any(MoneyCommand.class), eq(rejection))).willReturn(recorded);

        assertThatThrownBy(() -> service.withdraw(KEY, withdraw, actor)).isSameAs(rejection);
        then(auditService).should().logFailure(eq(AuditAction.WITHDRAWAL_FAILED), eq(actor.getUserId()), any(),
                eq("TRANSACTION"), eq(recorded.getId()), anyString());
    }

    @Test
    void doesNotRecordAuthorizationFailures() {
        givenOwnAccount();
        BusinessException denied = new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED);
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.empty());
        given(processor.execute(eq(KEY), any(), eq(actor))).willThrow(denied);

        assertThatThrownBy(() -> service.deposit(KEY, deposit, actor)).isSameAs(denied);
        then(failureRecorder).shouldHaveNoInteractions();
        then(auditService).should().logFailure(eq(AuditAction.DEPOSIT_FAILED), any(), any(), any(), isNull(), anyString());
    }

    @Test
    void failureThatLostTheRaceReplaysTheWinner() {
        givenOwnAccount();
        WithdrawRequest withdraw = new WithdrawRequest(accountId, new BigDecimal("500"), "KRW", null);
        Transaction winner = succeeded(withdraw.toCommand());
        BusinessException rejection = new BusinessException(ErrorCode.INSUFFICIENT_FUNDS);
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId()))
                .willReturn(Optional.empty(), Optional.of(winner));
        given(processor.execute(eq(KEY), any(), eq(actor))).willThrow(rejection);
        given(failureRecorder.record(any(), any(), any(), any())).willThrow(idempotencyViolation());

        assertThat(service.withdraw(KEY, withdraw, actor).transactionId()).isEqualTo(winner.getId());
    }

    @Test
    void legacyFailedRowsWithoutACodeReplayAsAGenericFailure() {
        Transaction legacy = withId(Transaction.failed(KEY, actor.getUserId(), deposit.toCommand(),
                ErrorCode.INSUFFICIENT_FUNDS, null));
        ReflectionTestUtils.setField(legacy, "failureCode", null);
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.deposit(KEY, deposit, actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSACTION_FAILED));
    }

    @Test
    void requestsThatCanNeverSucceedAreRejectedBeforeAnyLockIsTaken() {
        Account someoneElses = account(accountId, UUID.randomUUID(), "KRW");
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.empty());
        given(accountRepository.findAllById(any())).willReturn(List.of(someoneElses));

        assertThatThrownBy(() -> service.deposit(KEY, deposit, actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_ACCESS_DENIED));
        then(processor).shouldHaveNoInteractions();
    }

    @Test
    void aRecorderOutageDoesNotTurnTheRejectionIntoA500() {
        givenOwnAccount();
        WithdrawRequest withdraw = new WithdrawRequest(accountId, new BigDecimal("500"), "KRW", null);
        BusinessException rejection = new BusinessException(ErrorCode.INSUFFICIENT_FUNDS);
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.empty());
        given(processor.execute(eq(KEY), any(), eq(actor))).willThrow(rejection);
        given(failureRecorder.record(any(), any(), any(), any())).willThrow(new IllegalStateException("pool exhausted"));

        assertThatThrownBy(() -> service.withdraw(KEY, withdraw, actor)).isSameAs(rejection);
    }

    @Test
    void validationErrorsConsumeNothing() {
        DepositRequest fractionalWon = new DepositRequest(accountId, new BigDecimal("100.5"), "KRW", null);

        assertThatThrownBy(() -> service.deposit(KEY, fractionalWon, actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_AMOUNT));
        then(transactionRepository).shouldHaveNoInteractions();
        then(processor).shouldHaveNoInteractions();
    }

    @Test
    void auditOutageDoesNotMaskTheOutcome() {
        Transaction done = succeeded(deposit.toCommand());
        given(transactionRepository.findByIdempotencyKeyAndRequestedBy(KEY, actor.getUserId())).willReturn(Optional.of(done));
        willThrow(new IllegalStateException("audit down")).given(auditService)
                .logSuccess(any(), any(), any(), any(), any(), any());

        assertThat(service.deposit(KEY, deposit, actor).transactionId()).isEqualTo(done.getId());
        then(failureRecorder).should(never()).record(any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void givenOwnAccount() {
        given(accountRepository.findAllById(any())).willReturn(List.of(account(accountId, actor.getUserId(), "KRW")));
    }

    private static Account account(UUID id, UUID owner, String currency) {
        Account account = Account.create(owner, "1001-0000-000001", "main", currency);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private Transaction succeeded(MoneyCommand command) {
        Transaction tx = withId(Transaction.pending(KEY, actor.getUserId(), command));
        tx.markSuccess();
        return tx;
    }

    private static Transaction withId(Transaction tx) {
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        return tx;
    }

    private static DataIntegrityViolationException idempotencyViolation() {
        return new DataIntegrityViolationException("duplicate key", new ConstraintViolationException(
                "duplicate key", new SQLException("duplicate key", "23505"), Constraints.TRANSACTION_IDEMPOTENCY_KEY));
    }
}
