INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version, account_name)
SELECT '0000000003', 'NGN', 0, -1, 0, 'PayBridge Fee Income'
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000003');

INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version, account_name)
SELECT '0000000004', 'NGN', 0, -2, 0, 'PayBridge Tax Payable'
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000004');
