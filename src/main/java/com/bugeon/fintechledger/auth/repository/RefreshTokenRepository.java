package com.bugeon.fintechledger.auth.repository;

import com.bugeon.fintechledger.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Looks up a refresh token row by its exact stored BCrypt hash.
     *
     * <p>This is <b>not</b> the primary validation path: BCrypt is salted and
     * non-deterministic, so a raw token cannot be re-hashed to a matching value.
     * In production, {@code validateAndRotate()} loads the target user's active
     * tokens and runs {@code passwordEncoder.matches(raw, storedHash)} — see
     * {@link com.bugeon.fintechledger.auth.service.RefreshTokenService}.
     *
     * <p>This method is only usable when the caller already knows the exact stored
     * hash (e.g. a test or an admin tool). It relies on the UNIQUE
     * {@code uq_refresh_tokens_hash} index (see V2 migration).
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Bulk-revokes all non-revoked tokens for a given user.
     * Called on logout to invalidate all active sessions for that user.
     *
     * @return number of rows updated
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true " +
           "WHERE rt.userId = :userId AND rt.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    /**
     * Housekeeping — removes tokens that are either expired or already revoked.
     * Called by a nightly scheduled job to keep the table compact.
     *
     * @param cutoff rows with {@code expires_at < cutoff} are removed
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt " +
           "WHERE rt.expiresAt < :cutoff OR rt.revoked = true")
    int deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);
}
