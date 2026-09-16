package com.bugeon.fintechledger.reconciliation.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation Service — 금융 데이터 무결성 검증.
 *
 * 설계 이유:
 *   Account.cachedBalance는 성능 최적화를 위한 캐시 값이다.
 *   권위적(Authoritative) 잔액은 LedgerEntry의 합산이다.
 *   PESSIMISTIC_WRITE 락과 동일 TX 내 업데이트로 두 값이 항상 일치해야 하지만,
 *   버그, 장애, 부분 실패 시 불일치가 발생할 수 있다.
 *   이를 자동으로 감지하여 즉시 대응 가능하게 한다.
 *
 * FIX C-9 — 단일 장기 Read TX 문제 해결:
 *   이전에는 runFullReconciliation() 전체가 하나의 @Transactional(readOnly=true)로
 *   감싸져 있어, 대사가 진행되는 동안 새 거래가 커밋되면 cachedBalance 조회와
 *   Ledger 합산 시점이 어긋나 불일치 오탐(false positive)이 발생할 수 있었다.
 *
 *   수정:
 *     runFullReconciliation()은 TX 외부에서 페이지를 순회하고,
 *     계좌별 검사는 checkAccountInTx()가 개별 read TX에서 수행한다.
 *     → 계좌 A 검사 도중 계좌 B에 거래가 들어와도 서로 영향을 주지 않는다.
 *
 * 실행 방식:
 *   - runFullReconciliation(): 모든 ACTIVE 계좌 대상 전체 검증 (계좌별 독립 TX)
 *   - reconcileAccount(): 단일 계좌 즉시 검증 (의심 계좌 즉시 조사용)
 *
 * 불일치 시:
 *   1. RECONCILIATION_DISCREPANCY Audit 기록 (REQUIRES_NEW — 영구 보존)
 *   2. ERROR 레벨 로그 출력
 *   3. ReconciliationReport에 실패 목록 포함
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private static final int PAGE_SIZE = 500;

    private final AccountRepository      accountRepository;
    private final LedgerEntryRepository  ledgerEntryRepository;
    private final AuditService           auditService;

    /**
     * 셀프 프록시.
     * 같은 빈 내부에서 @Transactional 메서드를 호출할 때 Spring AOP 프록시를
     * 거치도록 하기 위한 자체 주입. (Lazy로 순환 참조 방지)
     */
    @Autowired @Lazy
    private ReconciliationService self;

    /**
     * 모든 계좌를 대상으로 전체 정합성 검증을 실행한다.
     * 페이지 단위 처리로 OOM 없이 대용량 처리 가능.
     * 각 계좌 검사는 개별 read TX에서 수행되어 서로 간섭하지 않는다.
     *
     * @return ReconciliationReport 전체 실행 결과
     */
    public ReconciliationReport runFullReconciliation() {
        Instant startedAt = Instant.now();
        log.info("Reconciliation started at {}", startedAt);

        List<ReconciliationResult> allResults = new ArrayList<>();
        int page = 0;

        while (true) {
            Page<Account> batch = accountRepository.findAll(PageRequest.of(page, PAGE_SIZE));
            if (batch.isEmpty()) break;

            for (Account account : batch.getContent()) {
                ReconciliationResult result = self.checkAccountInTx(account.getId());
                allResults.add(result);

                if (!result.isMatch()) {
                    handleDiscrepancy(result);
                }
            }

            if (batch.isLast()) break;
            page++;
        }

        ReconciliationReport report = ReconciliationReport.of(startedAt, allResults);

        log.info("Reconciliation completed: total={} matched={} discrepant={}",
                report.totalAccounts(), report.matchedAccounts(), report.discrepantAccounts());

        if (report.hasDiscrepancies()) {
            log.error("RECONCILIATION DISCREPANCIES DETECTED: {} account(s) out of balance",
                    report.discrepantAccounts());
        }

        return report;
    }

    /**
     * 단일 계좌에 대한 즉시 정합성 검증.
     * 의심 계좌 긴급 조사 또는 거래 완료 후 확인에 사용.
     *
     * @param accountId 검증할 계좌 ID
     * @return ReconciliationResult 검증 결과
     */
    public ReconciliationResult reconcileAccount(UUID accountId) {
        ReconciliationResult result = self.checkAccountInTx(accountId);

        if (!result.isMatch()) {
            handleDiscrepancy(result);
        }

        return result;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * 단일 계좌 검사를 개별 read-only TX에서 수행한다.
     *
     * cachedBalance를 읽는 시점과 LedgerEntry 합산을 읽는 시점 사이의
     * 일관성을 보장하기 위해 같은 짧은 read TX 안에서 두 값을 모두 읽는다.
     *
     * self 프록시 경유로 호출해야 @Transactional이 적용된다.
     */
    @Transactional(readOnly = true)
    public ReconciliationResult checkAccountInTx(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        BigDecimal cachedBalance = account.getCachedBalance();
        BigDecimal ledgerBalance = ledgerEntryRepository.calculateLedgerBalance(account.getId());

        return ReconciliationResult.of(account.getId(), cachedBalance, ledgerBalance);
    }

    /**
     * 불일치 발견 시 처리.
     * REQUIRES_NEW TX에서 Audit 기록 → main TX 롤백과 무관하게 영구 보존.
     */
    private void handleDiscrepancy(ReconciliationResult result) {
        log.error(
                "BALANCE DISCREPANCY: accountId={} cached={} ledger={} diff={}",
                result.accountId(),
                result.cachedBalance(),
                result.ledgerBalance(),
                result.discrepancy());

        String context = String.format(
                "{\"accountId\":\"%s\",\"cachedBalance\":\"%s\",\"ledgerBalance\":\"%s\",\"discrepancy\":\"%s\"}",
                result.accountId(),
                result.cachedBalance(),
                result.ledgerBalance(),
                result.discrepancy());

        // REQUIRES_NEW — Audit은 항상 기록되어야 함
        auditService.logFailure(
                AuditAction.RECONCILIATION_DISCREPANCY,
                null, null,
                "ACCOUNT", result.accountId(),
                context);
    }
}
