package com.bugeon.fintechledger.account.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.domain.AccountStatus;
import com.bugeon.fintechledger.account.dto.AccountResponse;
import com.bugeon.fintechledger.account.dto.BalanceResponse;
import com.bugeon.fintechledger.account.dto.CreateAccountRequest;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@DisplayName("AccountService")
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository      accountRepository;
    @Mock AccountNumberGenerator accountNumberGenerator;
    @Mock AuditService           auditService;
    @InjectMocks AccountService  accountService;

    private UserPrincipal principal;
    private UUID          userId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        principal = UserPrincipal.of(userId, "user@example.com");
    }

    @Nested @DisplayName("createAccount")
    class CreateAccount {

        @Test @DisplayName("creates ACTIVE account with zero balance")
        void createsActive() {
            given(accountNumberGenerator.generate()).willReturn("1001-0000-000001");
            given(accountRepository.save(any())).willAnswer(i -> i.getArgument(0));

            AccountResponse r = accountService.createAccount(
                    new CreateAccountRequest("Savings", "KRW"), principal);

            assertThat(r.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(r.balance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.currency()).isEqualTo("KRW");
        }

        @Test @DisplayName("defaults currency to KRW when null")
        void defaultsToKrw() {
            given(accountNumberGenerator.generate()).willReturn("1001-0000-000002");
            given(accountRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThat(accountService.createAccount(
                    new CreateAccountRequest("S", null), principal).currency()).isEqualTo("KRW");
        }

        @Test @DisplayName("multiple accounts get distinct numbers")
        void distinctNumbers() {
            given(accountNumberGenerator.generate())
                    .willReturn("1001-0000-000001").willReturn("1001-0000-000002");
            given(accountRepository.save(any())).willAnswer(i -> i.getArgument(0));

            AccountResponse a = accountService.createAccount(new CreateAccountRequest("A","KRW"), principal);
            AccountResponse b = accountService.createAccount(new CreateAccountRequest("B","KRW"), principal);
            assertThat(a.accountNumber()).isNotEqualTo(b.accountNumber());
        }
    }

    @Nested @DisplayName("getMyAccounts")
    class GetMyAccounts {

        @Test @DisplayName("returns all accounts for user")
        void returnsAll() {
            given(accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId))
                    .willReturn(List.of(Account.create(userId,"1001-0000-000001","S","KRW")));
            assertThat(accountService.getMyAccounts(principal)).hasSize(1);
        }

        @Test @DisplayName("returns empty list when no accounts")
        void returnsEmpty() {
            given(accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).willReturn(List.of());
            assertThat(accountService.getMyAccounts(principal)).isEmpty();
        }
    }

    @Nested @DisplayName("getAccount")
    class GetAccount {

        @Test @DisplayName("returns account for owner")
        void returnsForOwner() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id))
                    .willReturn(Optional.of(Account.create(userId,"1001-0000-000001","S","KRW")));

            assertThat(accountService.getAccount(id, principal).accountNumber())
                    .isEqualTo("1001-0000-000001");
        }

        @Test @DisplayName("throws ACCOUNT_NOT_FOUND")
        void throwsNotFound() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.getAccount(id, principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        @Test @DisplayName("throws ACCOUNT_ACCESS_DENIED for non-owner")
        void throwsAccessDenied() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id))
                    .willReturn(Optional.of(Account.create(UUID.randomUUID(),"1001-0000-000099","O","KRW")));

            assertThatThrownBy(() -> accountService.getAccount(id, principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_ACCESS_DENIED);
        }
    }

    @Nested @DisplayName("getBalance")
    class GetBalance {

        @Test @DisplayName("returns zero balance for new account")
        void returnsZeroBalance() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id))
                    .willReturn(Optional.of(Account.create(userId,"1001-0000-000001","S","KRW")));

            BalanceResponse r = accountService.getBalance(id, principal);
            assertThat(r.balance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.currency()).isEqualTo("KRW");
        }
    }

    @Nested @DisplayName("freezeAccount")
    class FreezeAccount {

        @Test @DisplayName("ACTIVE → FROZEN + audit 기록")
        void freezeActive() {
            Account account = accountWithId(UUID.randomUUID());
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            AccountResponse r = accountService.freezeAccount(account.getId(), principal);

            assertThat(r.status()).isEqualTo(AccountStatus.FROZEN);
            then(auditService).should().logSuccess(
                    eq(AuditAction.ACCOUNT_FROZEN), eq(userId), any(),
                    eq("ACCOUNT"), eq(account.getId()), any());
        }

        @Test @DisplayName("이미 FROZEN → ACCOUNT_ALREADY_FROZEN")
        void freezeFrozen() {
            Account account = accountWithId(UUID.randomUUID());
            account.freeze();
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.freezeAccount(account.getId(), principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_ALREADY_FROZEN);
        }

        @Test @DisplayName("CLOSED 계좌 freeze → ACCOUNT_CLOSED")
        void freezeClosed() {
            Account account = accountWithId(UUID.randomUUID());
            account.close();
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.freezeAccount(account.getId(), principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_CLOSED);
        }

        @Test @DisplayName("타인 계좌 freeze → ACCOUNT_ACCESS_DENIED")
        void freezeOther() {
            Account account = accountWithId(UUID.randomUUID());
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            UserPrincipal other = UserPrincipal.of(UUID.randomUUID(), "other@example.com");
            assertThatThrownBy(() -> accountService.freezeAccount(account.getId(), other))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_ACCESS_DENIED);
        }
    }

    @Nested @DisplayName("unfreezeAccount")
    class UnfreezeAccount {

        @Test @DisplayName("FROZEN → ACTIVE + audit 기록")
        void unfreezeFrozen() {
            Account account = accountWithId(UUID.randomUUID());
            account.freeze();
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            AccountResponse r = accountService.unfreezeAccount(account.getId(), principal);

            assertThat(r.status()).isEqualTo(AccountStatus.ACTIVE);
            then(auditService).should().logSuccess(
                    eq(AuditAction.ACCOUNT_UNFROZEN), eq(userId), any(),
                    eq("ACCOUNT"), eq(account.getId()), any());
        }

        @Test @DisplayName("FROZEN이 아닌 계좌 → ACCOUNT_NOT_FROZEN")
        void unfreezeActive() {
            Account account = accountWithId(UUID.randomUUID());
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.unfreezeAccount(account.getId(), principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FROZEN);
        }
    }

    @Nested @DisplayName("closeAccount")
    class CloseAccount {

        @Test @DisplayName("잔액 0 → CLOSED + audit 기록")
        void closeZeroBalance() {
            Account account = accountWithId(UUID.randomUUID());
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            AccountResponse r = accountService.closeAccount(account.getId(), principal);

            assertThat(r.status()).isEqualTo(AccountStatus.CLOSED);
            then(auditService).should().logSuccess(
                    eq(AuditAction.ACCOUNT_CLOSED), eq(userId), any(),
                    eq("ACCOUNT"), eq(account.getId()), any());
        }

        @Test @DisplayName("잔액이 있으면 ACCOUNT_HAS_BALANCE")
        void closeWithBalance() {
            Account account = accountWithId(UUID.randomUUID());
            account.increaseCachedBalance(new BigDecimal("5000"));
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.closeAccount(account.getId(), principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_HAS_BALANCE);

            then(auditService).should(never()).logSuccess(any(), any(), any(), any(), any(), any());
        }

        @Test @DisplayName("이미 CLOSED → ACCOUNT_CLOSED")
        void closeClosed() {
            Account account = accountWithId(UUID.randomUUID());
            account.close();
            given(accountRepository.findByIdForUpdate(account.getId())).willReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.closeAccount(account.getId(), principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_CLOSED);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Account accountWithId(UUID id) {
        Account account = Account.create(userId, "1001-0000-000001", "Test", "KRW");
        try {
            var f = Account.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(account, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return account;
    }
}
