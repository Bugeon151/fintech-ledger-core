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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository      accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AuditService           auditService;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, UserPrincipal principal) {
        String accountNumber = accountNumberGenerator.generate();
        Account account = Account.create(
                principal.getUserId(), accountNumber,
                request.accountName(), request.currencyOrDefault());
        accountRepository.save(account);

        // Audit: 계좌 생성 (REQUIRES_NEW)
        auditService.logSuccess(AuditAction.ACCOUNT_CREATED,
                principal.getUserId(), principal.getEmail(),
                "ACCOUNT", account.getId(),
                String.format("{\"accountNumber\":\"%s\",\"currency\":\"%s\"}",
                        accountNumber, account.getCurrency()));

        log.info("Account created: number={}, userId={}", accountNumber, principal.getUserId());
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(UserPrincipal principal) {
        return accountRepository
                .findAllByUserIdOrderByCreatedAtAsc(principal.getUserId())
                .stream().map(AccountResponse::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId, UserPrincipal principal) {
        return AccountResponse.from(load(accountId, principal.getUserId()));
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID accountId, UserPrincipal principal) {
        return BalanceResponse.from(load(accountId, principal.getUserId()));
    }

    // ── 상태 변경 (freeze / unfreeze / close) ─────────────────────────────────

    /**
     * 계좌 동결.
     * PESSIMISTIC_WRITE 락을 잡아 동결 도중 상태를 읽는 거래와 경합을 방지한다.
     */
    @Transactional
    public AccountResponse freezeAccount(UUID accountId, UserPrincipal principal) {
        Account account = loadForUpdate(accountId, principal.getUserId());

        if (account.getStatus() == AccountStatus.FROZEN) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_FROZEN);
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }

        account.freeze();

        auditService.logSuccess(AuditAction.ACCOUNT_FROZEN,
                principal.getUserId(), principal.getEmail(),
                "ACCOUNT", account.getId(),
                String.format("{\"accountNumber\":\"%s\"}", account.getAccountNumber()));

        log.info("Account frozen: id={}", account.getId());
        return AccountResponse.from(account);
    }

    /**
     * 계좌 동결 해제.
     * FROZEN 상태일 때만 ACTIVE로 복귀한다.
     */
    @Transactional
    public AccountResponse unfreezeAccount(UUID accountId, UserPrincipal principal) {
        Account account = loadForUpdate(accountId, principal.getUserId());

        if (account.getStatus() != AccountStatus.FROZEN) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FROZEN);
        }

        account.unfreeze();

        auditService.logSuccess(AuditAction.ACCOUNT_UNFROZEN,
                principal.getUserId(), principal.getEmail(),
                "ACCOUNT", account.getId(),
                String.format("{\"accountNumber\":\"%s\"}", account.getAccountNumber()));

        log.info("Account unfrozen: id={}", account.getId());
        return AccountResponse.from(account);
    }

    /**
     * 계좌 해지 (CLOSED).
     * 잔액이 0일 때만 가능하다 — 자금 보존 원칙을 지키기 위함.
     * ACTIVE 또는 FROZEN 상태에서 해지 가능.
     */
    @Transactional
    public AccountResponse closeAccount(UUID accountId, UserPrincipal principal) {
        Account account = loadForUpdate(accountId, principal.getUserId());

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }
        if (account.getCachedBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_HAS_BALANCE);
        }

        account.close();

        auditService.logSuccess(AuditAction.ACCOUNT_CLOSED,
                principal.getUserId(), principal.getEmail(),
                "ACCOUNT", account.getId(),
                String.format("{\"accountNumber\":\"%s\"}", account.getAccountNumber()));

        log.info("Account closed: id={}", account.getId());
        return AccountResponse.from(account);
    }

    private Account load(UUID accountId, UUID userId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.validateOwner(userId);
        return account;
    }

    /** 상태 변경 전용: 소유권 검증 + PESSIMISTIC_WRITE 락. */
    private Account loadForUpdate(UUID accountId, UUID userId) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.validateOwner(userId);
        return account;
    }
}
