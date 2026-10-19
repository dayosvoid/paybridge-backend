-- Holds the NGN of outbound remittances while they are in flight, and the part owed to the provider
-- once they complete. Starts empty.
INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version, account_name)
SELECT '0000000008', 'NGN', 0, -6, 0, 'PayBridge Remittance Clearing'
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000008');
