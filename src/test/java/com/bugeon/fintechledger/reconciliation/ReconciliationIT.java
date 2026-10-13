package com.bugeon.fintechledger.reconciliation;

import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationResult;
import com.bugeon.fintechledger.reconciliation.service.ReconciliationService;
import com.bugeon.fintechledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIT extends IntegrationTest {

    @Autowired ReconciliationService reconciliationService;

    @Test
    void detectsADriftedCachedBalanceAndPassesOnceRepaired() {
        UserPrincipal alice = newUser();
        UUID account = fundedAccount(alice, "5000");
        assertThat(reconciliationService.runFullReconciliation().hasDiscrepancies()).isFalse();

        jdbc.update("update accounts set cached_balance = cached_balance + 1 where id = ?", account);
        try {
            ReconciliationReport report = reconciliationService.runFullReconciliation();

            assertThat(report.failures()).extracting(ReconciliationResult::accountId).containsExactly(account);
            assertThat(report.unbalancedCurrencies()).isEmpty();
            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_logs where action = 'RECONCILIATION_DISCREPANCY' and target_id = ?",
                    Long.class, account)).isOne();
        } finally {
            jdbc.update("update accounts set cached_balance = cached_balance - 1 where id = ?", account);
        }
        assertThat(reconciliationService.runFullReconciliation().hasDiscrepancies()).isFalse();
    }
}
