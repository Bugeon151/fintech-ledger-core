package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.transaction.domain.MoneyCommand;
import com.bugeon.fintechledger.transaction.domain.TransactionType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rules for the customer-facing accounts of a movement. Checked twice: once with a plain read
 * before any lock is taken (so rejected requests never queue on the per-currency settlement
 * row), and again under the row locks, where the result is authoritative.
 */
final class MovementRules {

    private MovementRules() {
    }

    /**
     * @param source account money leaves (WITHDRAWAL / TRANSFER), else ignored
     * @param target account money enters (DEPOSIT / TRANSFER), else ignored
     */
    static void verify(MoneyCommand command, Account source, Account target, UUID actorId) {
        List<Account> customerSide = new ArrayList<>(2);
        if (command.type() != TransactionType.DEPOSIT) {
            customerSide.add(source);
        }
        if (command.type() != TransactionType.WITHDRAWAL) {
            customerSide.add(target);
        }
        for (Account account : customerSide) {
            if (account == null || account.isSettlement()) {
                throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
            }
        }
        // Money may only leave an account its owner controls; a deposit tops up the caller's own account.
        (command.type() == TransactionType.DEPOSIT ? target : source).validateOwner(actorId);
        for (Account account : customerSide) {
            account.validateActive();
            if (!account.getCurrency().equals(command.currency())) {
                throw new BusinessException(ErrorCode.CURRENCY_MISMATCH);
            }
        }
    }
}
