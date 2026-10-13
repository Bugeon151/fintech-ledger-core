-- Refresh tokens are now 256-bit random values stored as SHA-256 digests and
-- looked up by index. Legacy BCrypt digests cannot be matched by the new lookup,
-- so they are revoked: affected users simply log in again.
UPDATE refresh_tokens SET revoked = TRUE WHERE revoked = FALSE;

-- Distinguishes a token consumed by rotation (presenting it again = theft signal)
-- from one revoked by login/logout (presenting it again = plain 401).
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;
