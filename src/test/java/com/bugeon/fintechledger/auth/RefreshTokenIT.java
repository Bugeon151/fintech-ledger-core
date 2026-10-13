package com.bugeon.fintechledger.auth;

import com.bugeon.fintechledger.auth.dto.LoginRequest;
import com.bugeon.fintechledger.auth.dto.RefreshTokenRequest;
import com.bugeon.fintechledger.auth.dto.SignupRequest;
import com.bugeon.fintechledger.auth.dto.TokenResponse;
import com.bugeon.fintechledger.auth.service.AuthService;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenIT extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired AuthService authService;

    @Test
    void rotationIsSingleUseAndReuseRevokesTheFamily() {
        TokenResponse login = signupAndLogin();

        TokenResponse rotated = authService.refresh(new RefreshTokenRequest(login.refreshToken()));
        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());

        assertRejected(() -> authService.refresh(new RefreshTokenRequest(login.refreshToken())),
                ErrorCode.REFRESH_TOKEN_REVOKED);
        // the replay above signals theft: the legitimately rotated token is now dead too
        assertRejected(() -> authService.refresh(new RefreshTokenRequest(rotated.refreshToken())),
                ErrorCode.REFRESH_TOKEN_REVOKED);
        assertRejected(() -> authService.refresh(new RefreshTokenRequest("not-a-token")),
                ErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    @Test
    void anOldDeviceCannotLogOutANewerSession() {
        String email = "rt-" + UUID.randomUUID() + "@test.io";
        authService.signup(new SignupRequest(email, PASSWORD, "Two Devices"));
        TokenResponse phone = authService.login(new LoginRequest(email, PASSWORD));
        TokenResponse laptop = authService.login(new LoginRequest(email, PASSWORD));   // ends the phone session

        assertRejected(() -> authService.refresh(new RefreshTokenRequest(phone.refreshToken())),
                ErrorCode.REFRESH_TOKEN_REVOKED);
        assertThat(authService.refresh(new RefreshTokenRequest(laptop.refreshToken())).refreshToken()).isNotBlank();
    }

    @Test
    void concurrentRefreshesWithOneTokenSucceedExactlyOnce() throws Exception {
        String token = signupAndLogin().refreshToken();

        List<Callable<Boolean>> attempts = IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> {
            try {
                authService.refresh(new RefreshTokenRequest(token));
                return true;
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED);
                return false;
            }
        }).toList();

        assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    private TokenResponse signupAndLogin() {
        String email = "rt-" + UUID.randomUUID() + "@test.io";
        authService.signup(new SignupRequest(email, PASSWORD, "Token Tester"));
        return authService.login(new LoginRequest(email, PASSWORD));
    }
}
