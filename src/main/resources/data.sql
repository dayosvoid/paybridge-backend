INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version)
SELECT '0000000001', 'NGN', 10000000, 1, 0
    WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000001');

INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version)
SELECT '0000000002', 'NGN', 500000, 2, 0
    WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000002');

INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version)
SELECT '0000000000', 'NGN', 0, 0, 0
    WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000000');