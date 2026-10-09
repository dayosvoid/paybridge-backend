ALTER TABLE accounts
    ADD COLUMN status       VARCHAR(255) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN bank_code    VARCHAR(10)  NOT NULL DEFAULT '999',
    ADD COLUMN bank_name    VARCHAR(255) NOT NULL DEFAULT 'PayBridge MFB',
    ADD COLUMN account_name VARCHAR(255) NOT NULL DEFAULT 'PayBridge Customer';

ALTER TABLE accounts
    ADD CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'));

UPDATE accounts SET account_name = 'PayBridge Settlement' WHERE account_number = '0000000000';
