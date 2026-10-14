package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.auth.domain.RefreshToken;
import com.bugeon.fintechledger.auth.repository.RefreshTokenRepository;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.infrastructure.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository repository;

    private RefreshTokenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setRefreshExpiryMs(60_000);
        service = new RefreshTokenService(repository, props);
    }

    @Test
    void storesOnlyTheSha256DigestOfA256BitToken() {
        String raw = service.createRefreshToken(userId);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        then(repository).should().save(saved.capture());
        assertThat(raw).hasSize(43);                                     // 32 bytes, base64url, no padding
        assertThat(saved.getValue().getTokenHash()).hasSize(64).isEqualTo(RefreshTokenService.digest(raw));
        assertThat(saved.getValue().getTokenHash()).doesNotContain(raw);
    }

    @Test
    void rotatesAnActiveToken() {
        RefreshToken token = token(false, 60);
        given(repository.findByTokenHash(RefreshTokenService.digest("raw"))).willReturn(Optional.of(token));
        given(repository.consumeForRotation(eq(token.getId()), any())).willReturn(1);

        assertThat(service.validateAndRotate("raw")).isEqualTo(userId);
        then(repository).should(never()).revokeAllByUserId(any());
    }

    @Test
    void unknownTokenIsRejected() {
        given(repository.findByTokenHash(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndRotate("nope")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    void expiredTokenIsRejected() {
        given(repository.findByTokenHash(any())).willReturn(Optional.of(token(false, -1)));

        assertThatThrownBy(() -> service.validateAndRotate("raw")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_EXPIRED));
    }

    @Test
    void reuseOfARotatedTokenRevokesTheWholeFamily() {
        RefreshToken rotated = token(true, 60);
        ReflectionTestUtils.setField(rotated, "rotatedAt", Instant.now().minusSeconds(5));
        given(repository.findByTokenHash(any())).willReturn(Optional.of(rotated));

        assertThatThrownBy(() -> service.validateAndRotate("raw")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED));
        then(repository).should().revokeAllByUserId(userId);
    }

    @Test
    void aTokenEndedByLoginOrLogoutIsRejectedWithoutSideEffects() {
        given(repository.findByTokenHash(any())).willReturn(Optional.of(token(true, 60)));

        assertThatThrownBy(() -> service.validateAndRotate("raw")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED));
        then(repository).should(never()).revokeAllByUserId(any());
    }

    @Test
    void losingAConcurrentRotationIsTreatedAsReuse() {
        RefreshToken token = token(false, 60);
        given(repository.findByTokenHash(any())).willReturn(Optional.of(token));
        given(repository.consumeForRotation(eq(token.getId()), any())).willReturn(0);

        assertThatThrownBy(() -> service.validateAndRotate("raw")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED));
        then(repository).should().revokeAllByUserId(userId);
    }

    private RefreshToken token(boolean revoked, long secondsToExpiry) {
        RefreshToken token = RefreshToken.create(userId, "hash", Instant.now().plusSeconds(secondsToExpiry));
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        if (revoked) {
            token.revoke();
        }
        return token;
    }
}
