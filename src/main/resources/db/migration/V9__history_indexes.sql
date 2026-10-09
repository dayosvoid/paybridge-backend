CREATE INDEX idx_ledger_entries_account_created
    ON ledger_entries (account_number, created_at DESC, id DESC);

CREATE INDEX idx_transfers_source_created
    ON transfers (source_account, created_at DESC, id DESC);

CREATE INDEX idx_transfers_destination_created
    ON transfers (destination_account, created_at DESC, id DESC);
