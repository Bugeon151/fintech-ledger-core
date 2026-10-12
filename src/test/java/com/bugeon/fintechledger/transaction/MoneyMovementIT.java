package com.bugeon.fintechledger.transaction;

import com.bugeon.fintechledger.account.service.SettlementAccounts;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.ledger.domain.EntryType;
import com.bugeon.fintechledger.ledger.domain.LedgerEntry;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import com.bugeon.fintechledger.reconciliation.service.ReconciliationService;
import com.bugeon.fintechledger.support.IntegrationTest;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MoneyMovementIT extends IntegrationTest {

    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired SettlementAccounts    settlementAccounts;
    @Autowired ReconciliationService reconciliationService;
    @Autowired ObjectMapper          objectMapper;

    @Test
    void depositIsBookedAgainstTheSettlementAccount() throws Exception {
        UserPrincipal alice = newUser();
        UUID account = newAccount(alice, "KRW");

        TransactionResponse tx = transactionService.deposit(key(),
                new DepositRequest(account, new BigDecimal("50000"), "KRW", "salary"), alice);

        assertThat(tx.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(balance(account)).isEqualByComparingTo("50000");
        assertThat(ledgerEntryRepository.findAllByTransactionIdOrderByEntryTypeAsc(tx.transactionId()))
                .extracting(LedgerEntry::getEntryType, LedgerEntry::getAccountId)
                .containsExactly(tuple(EntryType.CREDIT, account), tuple(EntryType.DEBIT, settlementAccounts.idFor("KRW")));
        assertThat(reconciliationService.reconcileAccount(account).isMatch()).isTrue();
        assertThat(reconciliationService.reconcileAccount(settlementAccounts.idFor("KRW")).isMatch()).isTrue();
        assertThat(reconciliationService.unbalancedCurrencies()).isEmpty();

        OutboxEvent event = outboxEventRepository
                .findAllByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("TRANSACTION", tx.transactionId()).get(0);
        assertThat(event.getEventType()).isEqualTo("MoneyDeposited");
        assertThat(objectMapper.readTree(event.getPayload()).get("amount").asText()).isEqualTo("50000");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'DEPOSIT' and result = 'SUCCESS' and target_id = ?",
                Long.class, tx.transactionId())).isOne();
    }

    @Test
    void transferMovesMoneyBetweenCustomers() {
        UserPrincipal alice = newUser();
        UserPrincipal bob = newUser();
        UUID from = fundedAccount(alice, "30000");
        UUID to = newAccount(bob, "KRW");

        TransactionResponse tx = transactionService.transfer(key(),
                new TransferRequest(from, to, new BigDecimal("12000"), "KRW", "rent"), alice);

        assertThat(balance(from)).isEqualByComparingTo("18000");
        assertThat(balance(to)).isEqualByComparingTo("12000");
        assertThat(ledgerEntryRepository.findAllByTransactionIdOrderByEntryTypeAsc(tx.transactionId()))
                .extracting(LedgerEntry::getEntryType, LedgerEntry::getAccountId, LedgerEntry::getRunningBalance)
                .containsExactly(tuple(EntryType.CREDIT, to, new BigDecimal("12000.00")),
                                 tuple(EntryType.DEBIT, from, new BigDecimal("18000.00")));
    }

    @Test
    void rejectedWithdrawalIsRecordedAndReplayedWithTheSameError() {
        UserPrincipal alice = newUser();
        UUID account = fundedAccount(alice, "1000");
        String key = key();
        WithdrawRequest tooMuch = new WithdrawRequest(account, new BigDecimal("5000"), "KRW", null);

        assertRejected(() -> transactionService.withdraw(key, tooMuch, alice), ErrorCode.INSUFFICIENT_FUNDS);

        TransactionResponse stored = transactionService.findByIdempotencyKeyForUser(key, alice);
        assertThat(stored.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(stored.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject(
                "select target_id from audit_logs where action = 'WITHDRAWAL_FAILED' and actor_id = ?",
                UUID.class, alice.getUserId())).isEqualTo(stored.transactionId());

        assertRejected(() -> transactionService.withdraw(key, tooMuch, alice), ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where requested_by = ? and idempotency_key = ?",
                Long.class, alice.getUserId(), key)).isOne();
        assertThat(ledgerEntryRepository.findAllByTransactionIdOrderByEntryTypeAsc(stored.transactionId())).isEmpty();
        assertThat(balance(account)).isEqualByComparingTo("1000");
    }

    @Test
    void retryWithTheSameKeyReplaysInsteadOfMovingMoneyAgain() {
        UserPrincipal alice = newUser();
        UUID account = newAccount(alice, "KRW");
        String key = key();
        DepositRequest request = new DepositRequest(account, new BigDecimal("10000"), "KRW", null);

        TransactionResponse first = transactionService.deposit(key, request, alice);
        TransactionResponse second = transactionService.deposit(key, request, alice);

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(balance(account)).isEqualByComparingTo("10000");
        assertRejected(() -> transactionService.deposit(key,
                new DepositRequest(account, new BigDecimal("99999"), "KRW", null), alice), ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void idempotencyKeysAreScopedPerUser() {
        UserPrincipal alice = newUser();
        UserPrincipal bob = newUser();
        UUID aliceAccount = newAccount(alice, "KRW");
        UUID bobAccount = newAccount(bob, "KRW");
        String shared = key();

        TransactionResponse a = transactionService.deposit(shared, new DepositRequest(aliceAccount, new BigDecimal("100"), "KRW", null), alice);
        TransactionResponse b = transactionService.deposit(shared, new DepositRequest(bobAccount, new BigDecimal("200"), "KRW", null), bob);

        assertThat(a.transactionId()).isNotEqualTo(b.transactionId());
        assertThat(balance(bobAccount)).isEqualByComparingTo("200");
    }

    @Test
    void invalidAmountConsumesNothingAndTheKeyStaysUsable() {
        UserPrincipal alice = newUser();
        UUID account = newAccount(alice, "KRW");
        String key = key();

        assertRejected(() -> transactionService.deposit(key,
                new DepositRequest(account, new BigDecimal("100.50"), "KRW", null), alice), ErrorCode.INVALID_AMOUNT);
        TransactionResponse ok = transactionService.deposit(key,
                new DepositRequest(account, new BigDecimal("100"), "KRW", null), alice);

        assertThat(ok.status()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void authorizationRules() {
        UserPrincipal alice = newUser();
        UserPrincipal mallory = newUser();
        UUID aliceAccount = fundedAccount(alice, "5000");
        UUID malloryAccount = fundedAccount(mallory, "5000");
        UUID malloryUsd = newAccount(mallory, "USD");

        assertRejected(() -> transactionService.deposit(key(),
                new DepositRequest(aliceAccount, BigDecimal.TEN, "KRW", null), mallory), ErrorCode.ACCOUNT_ACCESS_DENIED);
        assertRejected(() -> transactionService.withdraw(key(),
                new WithdrawRequest(aliceAccount, BigDecimal.TEN, "KRW", null), mallory), ErrorCode.ACCOUNT_ACCESS_DENIED);
        assertRejected(() -> transactionService.transfer(key(),
                new TransferRequest(malloryAccount, settlementAccounts.idFor("KRW"), BigDecimal.TEN, "KRW", null), mallory),
                ErrorCode.ACCOUNT_NOT_FOUND);
        assertRejected(() -> transactionService.transfer(key(),
                new TransferRequest(malloryAccount, malloryUsd, BigDecimal.TEN, "KRW", null), mallory),
                ErrorCode.CURRENCY_MISMATCH);
        assertThat(balance(aliceAccount)).isEqualByComparingTo("5000");
        assertThat(balance(malloryAccount)).isEqualByComparingTo("5000");
    }

    @Test
    void recipientsNeverSeeSomeoneElsesFailedAttempts() {
        UserPrincipal alice = newUser();
        UserPrincipal bob = newUser();
        UUID from = fundedAccount(alice, "100");
        UUID to = newAccount(bob, "KRW");
        assertRejected(() -> transactionService.transfer(key(),
                new TransferRequest(from, to, new BigDecimal("500"), "KRW", null), alice), ErrorCode.INSUFFICIENT_FUNDS);
        transactionService.transfer(key(), new TransferRequest(from, to, new BigDecimal("40"), "KRW", null), alice);

        assertThat(transactionService.getTransactions(to, bob, PageRequest.of(0, 20)).getContent())
                .extracting(TransactionResponse::status).containsExactly(TransactionStatus.SUCCESS);
        assertThat(transactionService.getTransactions(from, alice, PageRequest.of(0, 20)).getContent())
                .extracting(TransactionResponse::status)
                .containsExactlyInAnyOrder(TransactionStatus.SUCCESS, TransactionStatus.SUCCESS, TransactionStatus.FAILED);
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        UserPrincipal alice = newUser();
        UUID account = fundedAccount(alice, "700");

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 1 where account_id = ?", account))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where account_id = ?", account))
                .hasMessageContaining("append-only");
    }
}
