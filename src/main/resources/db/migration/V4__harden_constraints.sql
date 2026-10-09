ALTER TABLE accounts
    ADD CONSTRAINT accounts_balance_non_negative CHECK (balance_minor >= 0);

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_amount_positive CHECK (amount_minor > 0);

ALTER TABLE transfers
    ADD CONSTRAINT transfers_amount_positive CHECK (amount_minor > 0);

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_customer_currency UNIQUE (customer_id, currency);

ALTER TABLE ledger_entries
    ADD CONSTRAINT uq_ledger_entries_reference_type UNIQUE (reference, type);

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_entries_account
    FOREIGN KEY (account_number) REFERENCES accounts (account_number);

ALTER TABLE transfers
    ADD CONSTRAINT fk_transfers_source_account
    FOREIGN KEY (source_account) REFERENCES accounts (account_number);

CREATE INDEX idx_transfers_pending_created
    ON transfers (created_at) WHERE status = 'PENDING';
