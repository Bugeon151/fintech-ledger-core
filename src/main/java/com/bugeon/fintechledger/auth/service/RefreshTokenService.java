package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.auth.domain.RefreshToken;
import com.bugeon.fintechledger.auth.repository.RefreshTokenRepository;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.infrastructure.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Manages the full lifecycle of refresh tokens.
 *
 * <h3>How lookup actually works</h3>
 * <ul>
 *   <li>The <b>raw token</b> is {@code UUID.randomUUID().toString()} (122 bits of
 *       entropy). Only its BCrypt hash is stored in the DB.</li>
 *   <li>BCrypt is salted, so {@code BCrypt.hash(raw)} differs on every call and a
 *       raw token can never be re-hashed into the stored value — a SQL lookup by
 *       hash is therefore impossible for the incoming raw token.</li>
 *   <li>Validation therefore loads the user's active tokens and runs
 *       {@code passwordEncoder.matches(raw, storedHash)} against each one.
 *       Because every login/refresh revokes the previous token first
 *       (see {@link #revokeAllForUser}), there is at most one active token per
 *       user in normal usage, so only a handful of BCrypt comparisons run.</li>
 *   <li>{@link RefreshTokenRepository#findByTokenHash} exists only for callers
 *       that already know the exact stored hash (tests / admin tooling) — it is
 *       not part of the production validation path.</li>
 *   <li><b>Single-use rotation</b> — each {@code /auth/refresh} revokes the
 *       consumed token before issuing a new one. Reusing a consumed token is a
 *       theft signal and must return 401.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties          jwtProperties;
    private final PasswordEncoder        passwordEncoder;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Generates, persists, and returns a new opaque refresh token for {@code userId}.
     *
     * The raw token (UUID string) is returned to the caller for delivery to the client.
     * Only its BCrypt hash is stored in the database.
     *
     * @param userId owner of the token
     * @return raw (unhashed) token string — must be returned to the client exactly once
     */
    @Transactional
    public String createRefreshToken(UUID userId) {
        String  rawToken   = UUID.randomUUID().toString();
        String  tokenHash  = passwordEncoder.encode(rawToken);
        Instant expiresAt  = Instant.now().plusMillis(jwtProperties.getRefreshExpiryMs());

        RefreshToken entity = RefreshToken.create(userId, tokenHash, expiresAt);
        refreshTokenRepository.save(entity);

        log.debug("Refresh token created for userId={}", userId);
        return rawToken;
    }

    // ── Validate & Rotate ─────────────────────────────────────────────────────

    /**
     * Validates an incoming raw refresh token, revokes it, and returns the owner's userId
     * so the caller can issue a new token pair.
     *
     * <p>Implementation note: we load all non-revoked, non-expired tokens and run
     * BCrypt.matches() against each one.  In normal usage there is exactly ONE active
     * token per user (previous tokens are revoked on every login / refresh), so this is
     * constant-time in practice.
     *
     * @param rawToken the plaintext refresh token received from the client
     * @return userId of the token owner
     * @throws BusinessException {@link ErrorCode#REFRESH_TOKEN_NOT_FOUND} — no matching token
     * @throws BusinessException {@link ErrorCode#REFRESH_TOKEN_REVOKED}   — token already used
     * @throws BusinessException {@link ErrorCode#TOKEN_EXPIRED}            — token past TTL
     */
    @Transactional
    public UUID validateAndRotate(String rawToken) {
        RefreshToken matched = refreshTokenRepository
                .findAll()
                .stream()
                .filter(rt -> !rt.isRevoked() && !rt.isExpired())
                .filter(rt -> passwordEncoder.matches(rawToken, rt.getTokenHash()))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("Refresh token not found or already consumed");
                    return new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
                });

        // Double-check status after match (defensive; already filtered above)
        if (matched.isRevoked()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        if (matched.isExpired()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        // Single-use rotation — revoke before returning
        matched.revoke();
        refreshTokenRepository.save(matched);

        log.debug("Refresh token rotated for userId={}", matched.getUserId());
        return matched.getUserId();
    }

    // ── Revoke all ────────────────────────────────────────────────────────────

    /**
     * Revokes every active refresh token belonging to {@code userId}.
     * Called on logout to end all sessions for the user.
     */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        int n = refreshTokenRepository.revokeAllByUserId(userId);
        log.info("Revoked {} refresh token(s) for userId={}", n, userId);
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /**
     * Nightly cleanup — deletes rows that are expired or revoked.
     * Prevents unbounded growth of the {@code refresh_tokens} table.
     *
     * Runs at 03:00 KST = 18:00 UTC daily.
     */
    @Scheduled(cron = "0 0 18 * * *", zone = "UTC")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(Instant.now());
        log.info("Housekeeping: purged {} expired/revoked refresh token(s)", deleted);
    }
}
