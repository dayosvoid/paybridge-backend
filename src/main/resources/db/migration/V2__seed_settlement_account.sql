INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version)
SELECT '0000000000', 'NGN', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000000');
