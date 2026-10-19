INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version, account_name)
SELECT '0000000005', 'NGN', 0, -3, 0, 'PayBridge Provider Clearing'
    WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000005');