-- =============================================================================
-- V9: transactions — add requested_by (idempotency result owner)
--
-- Records which user issued the request associated with this Idempotency-Key.
-- This enables a secure by-key lookup: a caller may only retrieve the result
-- of a request THEY originated (the key itself is not secret, ownership is).
-- =============================================================================

ALTER TABLE transactions
    ADD COLUMN requested_by UUID;

CREATE INDEX idx_tx_requested_by ON transactions (requested_by);