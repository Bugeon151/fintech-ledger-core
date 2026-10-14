package com.bugeon.fintechledger.transaction.domain;

import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyCommandTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"KRW,1000", "KRW,1000.00", "USD,10.5", "USD,0.01", "EUR,99.99"})
    void acceptsAmountsWithinTheCurrencyMinorUnit(String currency, String amount) {
        assertThatCode(() -> MoneyCommand.deposit(a, new BigDecimal(amount), currency, null).validate())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"KRW,1000.5", "KRW,0", "USD,-1", "USD,0.001"})
    void rejectsAmountsOutsideTheCurrencyMinorUnit(String currency, String amount) {
        assertThatThrownBy(() -> MoneyCommand.deposit(a, new BigDecimal(amount), currency, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_AMOUNT));
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> MoneyCommand.deposit(a, BigDecimal.TEN, "JPY", null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void rejectsSelfTransfer() {
        assertThatThrownBy(() -> MoneyCommand.transfer(a, a, BigDecimal.TEN, "KRW", null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED));
    }

    @Test
    void normalizesCurrencyCase() {
        assertThat(MoneyCommand.withdrawal(a, BigDecimal.ONE, "usd", null).currency()).isEqualTo("USD");
    }

    @Test
    void matchesIgnoresScaleButNotSubstance() {
        MoneyCommand original = MoneyCommand.transfer(a, b, new BigDecimal("100"), "KRW", "rent");
        Transaction stored = Transaction.pending("key", UUID.randomUUID(), original);

        assertThat(MoneyCommand.transfer(a, b, new BigDecimal("100.00"), "krw", "other memo").matches(stored)).isTrue();
        assertThat(MoneyCommand.transfer(a, b, new BigDecimal("101"), "KRW", "rent").matches(stored)).isFalse();
        assertThat(MoneyCommand.transfer(b, a, new BigDecimal("100"), "KRW", "rent").matches(stored)).isFalse();
        assertThat(MoneyCommand.withdrawal(a, new BigDecimal("100"), "KRW", "rent").matches(stored)).isFalse();
    }
}
