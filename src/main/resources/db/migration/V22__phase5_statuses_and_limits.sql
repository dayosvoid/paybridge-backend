-- Phase 5: payments can be taken back after the fact.
--   inbound_payments:     + REVERSED (provider chargeback), REVERSAL_FAILED (customer had already spent it)
--   outbound_remittances: + RETURNED (a COMPLETED payout came back and was refunded)
ALTER TABLE inbound_payments DROP CONSTRAINT inbound_payments_status_check;
ALTER TABLE inbound_payments ADD CONSTRAINT inbound_payments_status_check
    CHECK (status IN ('RECEIVED', 'CREDITED', 'UNMATCHED', 'FAILED', 'REVERSED', 'REVERSAL_FAILED'));

ALTER TABLE outbound_remittances DROP CONSTRAINT outbound_remittances_status_check;
ALTER TABLE outbound_remittances ADD CONSTRAINT outbound_remittances_status_check
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'RETURNED'));

-- The daily limit adds up an account's recent remittances.
CREATE INDEX idx_outbound_remittances_source_created ON outbound_remittances (source_account, created_at);
