package com.bugeon.fintechledger.reconciliation.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.common.json.Json;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Verifies the two invariants of the ledger (daily via ReconciliationScheduler, or on demand):
 *  1. per account: accounts.cached_balance = SUM(CREDIT) - SUM(DEBIT) of its ledger legs
 *  2. per currency: SUM(CREDIT) = SUM(DEBIT) over all legs (double-entry)
 *
 * Each account is checked in its own short REPEATABLE READ, read-only transaction: in
 * PostgreSQL that gives both reads one snapshot, so a transfer committing between them
 * cannot produce a false discrepancy (under READ COMMITTED each statement sees a new snapshot).
 * Accounts are walked with keyset paging on id, so accounts created during a run neither
 * shift pages nor get checked twice.
 */
@Service
@Slf4j
public class ReconciliationService {

    private static final int  PAGE_SIZE = 500;
    private static final UUID FIRST_ID  = new UUID(0L, 0L);

    private final AccountRepository     accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService          auditService;
    private final TransactionTemplate   snapshotRead;

    public ReconciliationService(AccountRepository accountRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 AuditService auditService,
                                 PlatformTransactionManager transactionManager) {
        this.accountRepository     = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService          = auditService;
        this.snapshotRead          = new TransactionTemplate(transactionManager);
        this.snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotRead.setReadOnly(true);
    }

    public ReconciliationReport runFullReconciliation() {
        Instant startedAt = Instant.now();
        List<ReconciliationResult> results = new ArrayList<>();
        UUID after = FIRST_ID;
        List<UUID> page;
        do {
            page = accountRepository.findIdsAfter(after, PageRequest.of(0, PAGE_SIZE));
            for (UUID accountId : page) {
                results.add(reconcileAccount(accountId));
            }
            after = page.isEmpty() ? after : page.get(page.size() - 1);
        } while (page.size() == PAGE_SIZE);

        Map<String, BigDecimal> unbalanced = unbalancedCurrencies();
        ReconciliationReport report = ReconciliationReport.of(startedAt, results, unbalanced);
        if (report.hasDiscrepancies()) {
            log.error("RECONCILIATION FAILED: {} account discrepancy(ies), unbalanced currencies={}",
                    report.discrepantAccounts(), unbalanced);
        } else {
            log.info("Reconciliation passed: {} accounts, ledger balanced", report.totalAccounts());
        }
        return report;
    }

    public ReconciliationResult reconcileAccount(UUID accountId) {
        ReconciliationResult result = snapshotRead.execute(status -> {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
            return ReconciliationResult.of(accountId, account.getCachedBalance(),
                    ledgerEntryRepository.calculateLedgerBalance(accountId));
        });
        if (!result.isMatch()) {
            log.error("BALANCE DISCREPANCY: account={} cached={} ledger={} diff={}",
                    accountId, result.cachedBalance(), result.ledgerBalance(), result.discrepancy());
            auditService.logFailure(AuditAction.RECONCILIATION_DISCREPANCY, null, null, "ACCOUNT", accountId,
                    Json.of("cachedBalance", result.cachedBalance().toPlainString(),
                            "ledgerBalance", result.ledgerBalance().toPlainString(),
                            "discrepancy", result.discrepancy().toPlainString()));
        }
        return result;
    }

    /** Currencies whose legs do not net to zero. Empty means the double-entry invariant holds. */
    public Map<String, BigDecimal> unbalancedCurrencies() {
        Map<String, BigDecimal> unbalanced = new TreeMap<>();
        for (LedgerEntryRepository.CurrencyNet row : ledgerEntryRepository.netByCurrency()) {
            if (row.getNet().signum() != 0) {
                unbalanced.put(row.getCurrency(), row.getNet());
            }
        }
        if (!unbalanced.isEmpty()) {
            auditService.logFailure(AuditAction.RECONCILIATION_DISCREPANCY, null, null, "LEDGER", null,
                    Json.write(unbalanced));
        }
        return unbalanced;
    }
}
