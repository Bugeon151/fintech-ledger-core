package com.bugeon.fintechledger.account.service;

import com.bugeon.fintechledger.account.domain.AccountType;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves (and caches) the per-currency settlement account seeded by migration V10. */
@Component
@RequiredArgsConstructor
public class SettlementAccounts {

    private final AccountRepository  accountRepository;
    private final Map<String, UUID>  idsByCurrency = new ConcurrentHashMap<>();

    public UUID idFor(String currency) {
        return idsByCurrency.computeIfAbsent(currency, c -> accountRepository
                .findIdByTypeAndCurrency(AccountType.SETTLEMENT, c)
                .orElseThrow(() -> new IllegalStateException("No settlement account for currency " + c)));
    }
}
