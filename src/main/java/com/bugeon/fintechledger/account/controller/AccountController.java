package com.bugeon.fintechledger.account.controller;

import com.bugeon.fintechledger.account.dto.AccountResponse;
import com.bugeon.fintechledger.account.dto.BalanceResponse;
import com.bugeon.fintechledger.account.dto.CreateAccountRequest;
import com.bugeon.fintechledger.account.service.AccountService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Virtual account management")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Create a new account")
    public ResponseEntity<ApiResponse<AccountResponse>> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(accountService.createAccount(request, principal)));
    }

    @GetMapping
    @Operation(summary = "List my accounts")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getMyAccounts(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getMyAccounts(principal)));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getAccount(accountId, principal)));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get account balance")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getBalance(accountId, principal)));
    }

    @PostMapping("/{accountId}/freeze")
    @Operation(summary = "Freeze an account",
            description = "계좌를 동결합니다. 동결된 계좌는 입출금/송금이 차단됩니다.")
    public ResponseEntity<ApiResponse<AccountResponse>> freezeAccount(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.freezeAccount(accountId, principal)));
    }

    @PostMapping("/{accountId}/unfreeze")
    @Operation(summary = "Unfreeze an account", description = "동결된 계좌를 다시 ACTIVE로 복귀시킵니다.")
    public ResponseEntity<ApiResponse<AccountResponse>> unfreezeAccount(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.unfreezeAccount(accountId, principal)));
    }

    @PostMapping("/{accountId}/close")
    @Operation(summary = "Close an account",
            description = "잔액이 0일 때만 계좌를 해지합니다.")
    public ResponseEntity<ApiResponse<AccountResponse>> closeAccount(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.closeAccount(accountId, principal)));
    }
}
