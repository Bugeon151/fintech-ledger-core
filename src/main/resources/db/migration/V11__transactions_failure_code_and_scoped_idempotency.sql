-- 1. Persist the business error code of FAILED transactions so an idempotent
--    replay can return the same error the original request received.
--    Legacy FAILED rows keep NULL and replay as the generic TRANSACTION_FAILED error.
ALTER TABLE transactions ADD COLUMN failure_code VARCHAR(50);

-- 2. Idempotency keys are scoped per requester (a key chosen by user A must not
--    collide with, or reveal, a request made by user B).
--    NULLS NOT DISTINCT keeps legacy rows (requested_by IS NULL) unique by key.
ALTER TABLE transactions DROP CONSTRAINT uq_transactions_idem_key;
ALTER TABLE transactions
    ADD CONSTRAINT uq_transactions_requester_idem_key
        UNIQUE NULLS NOT DISTINCT (requested_by, idempotency_key);
