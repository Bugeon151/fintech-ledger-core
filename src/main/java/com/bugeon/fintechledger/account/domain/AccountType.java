package com.bugeon.fintechledger.account.domain;

/**
 * CUSTOMER   — user-owned account; balance can never go negative.
 * SETTLEMENT — per-currency system account that books the external side of
 *              deposits and withdrawals so every transaction is double-entry.
 */
public enum AccountType {
    CUSTOMER,
    SETTLEMENT
}
