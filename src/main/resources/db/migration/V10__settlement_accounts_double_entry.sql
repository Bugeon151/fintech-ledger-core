-- Every money movement becomes a balanced pair of ledger legs.
-- External money (deposits / withdrawals) is booked against a per-currency
-- SETTLEMENT account owned by a non-loginable system user, so that
-- SUM(credits) = SUM(debits) holds for every currency.
-- Sign convention for all accounts: balance = SUM(CREDIT) - SUM(DEBIT).
-- A settlement balance is therefore <= 0 (money held for customers).

ALTER TABLE accounts
    ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_type CHECK (account_type IN ('CUSTOMER', 'SETTLEMENT'));

ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance_positive;
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_balance_non_negative
        CHECK (account_type = 'SETTLEMENT' OR cached_balance >= 0);

CREATE UNIQUE INDEX uq_accounts_settlement_currency
    ON accounts (currency) WHERE account_type = 'SETTLEMENT';

INSERT INTO users (id, email, password_hash, full_name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'system@ledger.internal',
        '!login-disabled', 'Ledger System', 'SUSPENDED');

INSERT INTO accounts (user_id, account_number, account_name, currency, account_type)
VALUES ('00000000-0000-0000-0000-000000000001', '9999-0000-000410', 'External settlement KRW', 'KRW', 'SETTLEMENT'),
       ('00000000-0000-0000-0000-000000000001', '9999-0000-000840', 'External settlement USD', 'USD', 'SETTLEMENT'),
       ('00000000-0000-0000-0000-000000000001', '9999-0000-000978', 'External settlement EUR', 'EUR', 'SETTLEMENT');

-- v2.0 stored the request currency as sent ("krw" was accepted). Normalise before
-- matching legs to settlement accounts, then make the column strict.
UPDATE transactions   SET currency = upper(currency) WHERE currency <> upper(currency);
UPDATE ledger_entries SET currency = upper(currency) WHERE currency <> upper(currency);
ALTER TABLE transactions   ADD CONSTRAINT chk_transactions_currency CHECK (currency IN ('KRW', 'USD', 'EUR'));
ALTER TABLE ledger_entries ADD CONSTRAINT chk_ledger_currency       CHECK (currency IN ('KRW', 'USD', 'EUR'));

-- Backfill the missing counter-leg of every historical single-entry deposit / withdrawal.
INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount,
                            running_balance, currency, description, created_at)
SELECT le.transaction_id,
       s.id,
       CASE le.entry_type WHEN 'CREDIT' THEN 'DEBIT' ELSE 'CREDIT' END,
       le.amount,
       SUM(CASE le.entry_type WHEN 'CREDIT' THEN -le.amount ELSE le.amount END)
           OVER (PARTITION BY s.id ORDER BY le.created_at, le.id),
       le.currency,
       'SETTLEMENT (backfill)',
       le.created_at
FROM ledger_entries le
JOIN transactions t ON t.id = le.transaction_id AND t.type IN ('DEPOSIT', 'WITHDRAWAL')
JOIN accounts s     ON s.account_type = 'SETTLEMENT' AND s.currency = le.currency;

UPDATE accounts s
SET cached_balance = COALESCE((SELECT SUM(CASE e.entry_type WHEN 'CREDIT' THEN e.amount ELSE -e.amount END)
                               FROM ledger_entries e
                               WHERE e.account_id = s.id), 0)
WHERE s.account_type = 'SETTLEMENT';
