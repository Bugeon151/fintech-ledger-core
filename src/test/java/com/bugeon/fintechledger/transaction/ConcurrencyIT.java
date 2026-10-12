package com.bugeon.fintechledger.transaction;

import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.reconciliation.service.ReconciliationService;
import com.bugeon.fintechledger.support.IntegrationTest;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real threads against real row locks — what the old Mockito "concurrency" tests could not verify. */
class ConcurrencyIT extends IntegrationTest {

    @Autowired ReconciliationService reconciliationService;

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        UserPrincipal alice = newUser();
        UUID account = fundedAccount(alice, "100000");
        WithdrawRequest tenThousand = new WithdrawRequest(account, new BigDecimal("10000"), "KRW", null);

        List<Callable<Boolean>> attempts = IntStream.range(0, 16).<Callable<Boolean>>mapToObj(i -> () -> {
            try {
                transactionService.withdraw(key(), tenThousand, alice);
                return true;
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                return false;
            }
        }).toList();

        assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(10);
        assertThat(balance(account)).isEqualByComparingTo("0");
        assertThat(reconciliationService.reconcileAccount(account).isMatch()).isTrue();
    }

    @Test
    void opposingTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        UserPrincipal alice = newUser();
        UserPrincipal bob = newUser();
        UUID a = fundedAccount(alice, "100000");
        UUID b = fundedAccount(bob, "100000");

        List<Callable<UUID>> transfers = IntStream.range(0, 20).<Callable<UUID>>mapToObj(i -> i % 2 == 0
                ? () -> transactionService.transfer(key(), new TransferRequest(a, b, new BigDecimal("1000"), "KRW", null), alice).transactionId()
                : () -> transactionService.transfer(key(), new TransferRequest(b, a, new BigDecimal("700"), "KRW", null), bob).transactionId()
        ).toList();

        assertThat(runConcurrently(transfers)).doesNotHaveDuplicates().hasSize(20);
        assertThat(balance(a)).isEqualByComparingTo("97000");
        assertThat(balance(b)).isEqualByComparingTo("103000");
        assertThat(reconciliationService.reconcileAccount(a).isMatch()).isTrue();
        assertThat(reconciliationService.reconcileAccount(b).isMatch()).isTrue();
        assertThat(reconciliationService.unbalancedCurrencies()).isEmpty();
    }

    @Test
    void concurrentRequestsWithOneKeyMoveMoneyOnce() throws Exception {
        UserPrincipal alice = newUser();
        UUID account = newAccount(alice, "KRW");
        String key = key();
        DepositRequest request = new DepositRequest(account, new BigDecimal("5000"), "KRW", null);

        List<Callable<UUID>> duplicates = IntStream.range(0, 10)
                .<Callable<UUID>>mapToObj(i -> () -> transactionService.deposit(key, request, alice).transactionId())
                .toList();

        List<UUID> ids = runConcurrently(duplicates);
        assertThat(ids).containsOnly(ids.get(0));
        assertThat(balance(account)).isEqualByComparingTo("5000");
        assertThat(jdbc.queryForObject("select count(*) from transactions where requested_by = ? and idempotency_key = ?",
                Long.class, alice.getUserId(), key)).isOne();
    }
}
