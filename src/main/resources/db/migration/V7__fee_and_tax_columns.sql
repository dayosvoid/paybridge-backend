ALTER TABLE transfers
    ADD COLUMN fee_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tax_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE transfers ADD CONSTRAINT transfers_fee_non_negative CHECK (fee_minor >= 0);
ALTER TABLE transfers ADD CONSTRAINT transfers_tax_non_negative CHECK (tax_minor >= 0);
