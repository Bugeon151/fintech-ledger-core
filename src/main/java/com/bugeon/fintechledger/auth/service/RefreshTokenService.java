package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.auth.domain.RefreshToken;
import com.bugeon.fintechledger.auth.repository.RefreshTokenRepository;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.infrastructure.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh-token lifecycle: single-use rotation with reuse detection (RFC 9700 §4.14).
 *
 * - The raw token is 256 bits from {@link SecureRandom}; only its SHA-256 digest is stored.
 *   A slow, salted hash (BCrypt) protects low-entropy passwords; for a random 256-bit secret
 *   a deterministic digest is sufficient and allows an indexed O(1) lookup.
 * - Rotation consumes the token with a conditional UPDATE, so two concurrent refreshes
 *   with the same token cannot both succeed.
 * - Presenting a token that was already rotated means it leaked: every active token of that
 *   user is revoked. This must commit even though the call fails, hence {@code noRollbackFor}.
 *   A token merely revoked by login/logout is rejected without that side effect, so an old
 *   device cannot log the user out of a newer session.
 * - Rotated tokens are kept until they expire, so reuse stays detectable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final SecureRandom RANDOM      = new SecureRandom();
    private static final int          TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties          jwtProperties;

    @Transactional
    public String createRefreshToken(UUID userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(RefreshToken.create(userId, digest(rawToken),
                Instant.now().plusMillis(jwtProperties.getRefreshExpiryMs())));
        return rawToken;
    }

    /** Consumes the token and returns its owner. */
    @Transactional(noRollbackFor = BusinessException.class)
    public UUID validateAndRotate(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(digest(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        if (token.isExpired()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
        if (token.getRotatedAt() != null) {
            throw reuseDetected(token);                       // an already-rotated token came back
        }
        if (token.isRevoked()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);   // ended by login/logout
        }
        if (refreshTokenRepository.consumeForRotation(token.getId(), Instant.now()) == 0) {
            throw reuseDetected(token);                       // lost a concurrent rotation of the same token
        }
        return token.getUserId();
    }

    private BusinessException reuseDetected(RefreshToken token) {
        int revoked = refreshTokenRepository.revokeAllByUserId(token.getUserId());
        log.warn("Refresh token reuse detected for userId={}; revoked {} active token(s)", token.getUserId(), revoked);
        return new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        int revoked = refreshTokenRepository.revokeAllByUserId(userId);
        log.info("Revoked {} refresh token(s) for userId={}", revoked, userId);
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "UTC")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpired(Instant.now());
        log.info("Housekeeping: purged {} expired refresh token(s)", deleted);
    }

    static String digest(String rawToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
