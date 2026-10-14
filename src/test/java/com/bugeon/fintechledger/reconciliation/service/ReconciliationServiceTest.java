package com.bugeon.fintechledger.reconciliation.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock AccountRepository          accountRepository;
    @Mock LedgerEntryRepository      ledgerEntryRepository;
    @Mock AuditService               auditService;
    @Mock PlatformTransactionManager transactionManager;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ReconciliationService(accountRepository, ledgerEntryRepository, auditService, transactionManager);
    }

    @Test
    void readsBothBalancesInOneRepeatableReadSnapshot() {
        Account account = account("100.00");
        given(accountRepository.findById(account.getId())).willReturn(Optional.of(account));
        given(ledgerEntryRepository.calculateLedgerBalance(account.getId())).willReturn(new BigDecimal("100"));

        assertThat(service.reconcileAccount(account.getId()).isMatch()).isTrue();
        then(transactionManager).should().getTransaction(argThat(def ->
                def.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ && def.isReadOnly()));
    }

    @Test
    void reportsAndAuditsDiscrepanciesAndUnbalancedCurrencies() {
        Account ok = account("50");
        Account drifted = account("70");
        given(accountRepository.findIdsAfter(eq(new UUID(0L, 0L)), any(Pageable.class)))
                .willReturn(List.of(ok.getId(), drifted.getId()));
        given(accountRepository.findById(ok.getId())).willReturn(Optional.of(ok));
        given(accountRepository.findById(drifted.getId())).willReturn(Optional.of(drifted));
        given(ledgerEntryRepository.calculateLedgerBalance(ok.getId())).willReturn(new BigDecimal("50"));
        given(ledgerEntryRepository.calculateLedgerBalance(drifted.getId())).willReturn(new BigDecimal("60"));
        given(ledgerEntryRepository.netByCurrency()).willReturn(List.of(net("KRW", "0"), net("USD", "1.50")));

        ReconciliationReport report = service.runFullReconciliation();

        assertThat(report.totalAccounts()).isEqualTo(2);
        assertThat(report.failures()).singleElement()
                .satisfies(r -> assertThat(r.discrepancy()).isEqualByComparingTo("10"));
        assertThat(report.unbalancedCurrencies()).containsOnlyKeys("USD");
        assertThat(report.hasDiscrepancies()).isTrue();
        then(auditService).should().logFailure(eq(AuditAction.RECONCILIATION_DISCREPANCY), any(), any(),
                eq("ACCOUNT"), eq(drifted.getId()), any());
        then(auditService).should().logFailure(eq(AuditAction.RECONCILIATION_DISCREPANCY), any(), any(),
                eq("LEDGER"), any(), any());
    }

    private static Account account(String cachedBalance) {
        Account account = Account.create(UUID.randomUUID(), "1001-0000-000001", "main", "KRW");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(account, "cachedBalance", new BigDecimal(cachedBalance));
        return account;
    }

    private static LedgerEntryRepository.CurrencyNet net(String currency, String net) {
        return new LedgerEntryRepository.CurrencyNet() {
            @Override public String getCurrency() { return currency; }
            @Override public BigDecimal getNet() { return new BigDecimal(net); }
        };
    }
}
