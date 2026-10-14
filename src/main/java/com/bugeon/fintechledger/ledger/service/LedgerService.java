package com.bugeon.fintechledger.ledger.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.ledger.domain.LedgerEntry;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Double-entry ledger. Every posting writes exactly one DEBIT and one CREDIT leg of the
 * same amount, so SUM(credits) = SUM(debits) per currency at all times
 * (deposits / withdrawals use the currency's settlement account as the counter-leg).
 *
 * Must run inside the caller's transaction, after both accounts are locked and their
 * cached balances updated; the legs record those balances as running balances.
 * Entries are append-only — enforced by a database trigger (V14).
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(UUID transactionId, Account debited, Account credited,
                     BigDecimal amount, String currency, String memo) {
        if (debited.getId().equals(credited.getId())) {
            throw new IllegalArgumentException("Debit and credit legs must hit different accounts");
        }
        ledgerEntryRepository.saveAll(List.of(
                LedgerEntry.debit(transactionId, debited.getId(), amount,
                        debited.getCachedBalance(), currency, memo),
                LedgerEntry.credit(transactionId, credited.getId(), amount,
                        credited.getCachedBalance(), currency, memo)));
    }
}
