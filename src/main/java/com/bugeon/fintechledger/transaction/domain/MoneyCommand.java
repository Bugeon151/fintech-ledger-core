package com.bugeon.fintechledger.transaction.domain;

import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A requested money movement, independent of the HTTP DTO it came from.
 * DEPOSIT: target only · WITHDRAWAL: source only · TRANSFER: both.
 */
public record MoneyCommand(TransactionType type,
                           UUID sourceAccountId,
                           UUID targetAccountId,
                           BigDecimal amount,
                           String currency,
                           String description) {

    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("KRW", "USD", "EUR");

    public MoneyCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        currency = currency.toUpperCase(Locale.ROOT);
    }

    public static MoneyCommand deposit(UUID accountId, BigDecimal amount, String currency, String description) {
        return new MoneyCommand(TransactionType.DEPOSIT, null, accountId, amount, currency, description);
    }

    public static MoneyCommand withdrawal(UUID accountId, BigDecimal amount, String currency, String description) {
        return new MoneyCommand(TransactionType.WITHDRAWAL, accountId, null, amount, currency, description);
    }

    public static MoneyCommand transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                                       String currency, String description) {
        return new MoneyCommand(TransactionType.TRANSFER, fromAccountId, toAccountId, amount, currency, description);
    }

    /**
     * Checks that need no database access. Runs before the idempotency key is consumed,
     * so a malformed request can be corrected and retried with the same key.
     */
    public void validate() {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "Unsupported currency: " + currency);
        }
        int minorUnits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > minorUnits) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT,
                    "Amount must be positive with at most " + minorUnits + " decimal place(s) for " + currency);
        }
        if (type == TransactionType.TRANSFER && sourceAccountId.equals(targetAccountId)) {
            throw new BusinessException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }
    }

    /** Same key must mean same request: type, accounts, amount and currency must all match. */
    public boolean matches(Transaction tx) {
        return tx.getType() == type
                && Objects.equals(tx.getSourceAccountId(), sourceAccountId)
                && Objects.equals(tx.getTargetAccountId(), targetAccountId)
                && tx.getAmount().compareTo(amount) == 0
                && tx.getCurrency().equals(currency);
    }
}
