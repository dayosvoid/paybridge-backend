-- One account number can now hold one balance per currency (an NGN balance and a USD balance).
-- accounts.account_number is therefore no longer unique on its own; (account_number, currency) is.
-- The two foreign keys that pointed at the old unique column are replaced by composite keys.

ALTER TABLE ledger_entries DROP CONSTRAINT fk_ledger_entries_account;
ALTER TABLE transfers      DROP CONSTRAINT fk_transfers_source_account;

ALTER TABLE accounts DROP CONSTRAINT uq_accounts_account_number;
ALTER TABLE accounts ADD CONSTRAINT uq_accounts_number_currency UNIQUE (account_number, currency);

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_entries_account
    FOREIGN KEY (account_number, currency) REFERENCES accounts (account_number, currency);

ALTER TABLE transfers
    ADD CONSTRAINT fk_transfers_source_account
    FOREIGN KEY (source_account, currency) REFERENCES accounts (account_number, currency);

-- Statements are read per balance.
CREATE INDEX idx_ledger_entries_account_currency_created
    ON ledger_entries (account_number, currency, created_at DESC, id DESC);
