package com.bugeon.fintechledger.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReconciliationReport(
        Instant                    startedAt,
        Instant                    completedAt,
        int                        totalAccounts,
        int                        matchedAccounts,
        int                        discrepantAccounts,
        List<ReconciliationResult> failures,
        Map<String, BigDecimal>    unbalancedCurrencies
) {
    public boolean hasDiscrepancies() {
        return discrepantAccounts > 0 || !unbalancedCurrencies.isEmpty();
    }

    public static ReconciliationReport of(Instant startedAt,
                                          List<ReconciliationResult> results,
                                          Map<String, BigDecimal> unbalancedCurrencies) {
        List<ReconciliationResult> failures = results.stream().filter(r -> !r.isMatch()).toList();
        return new ReconciliationReport(startedAt, Instant.now(), results.size(),
                results.size() - failures.size(), failures.size(), failures, Map.copyOf(unbalancedCurrencies));
    }
}
