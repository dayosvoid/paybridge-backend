-- FX pool accounts. A remittance moves the sender's NGN into FX_NGN_POOL and pays the beneficiary's
-- USD out of FX_USD_POOL. The USD pool starts with DEMO liquidity of USD 1,000,000.00 (100,000,000
-- minor units) so the demo can pay out; in production this would be funded by a real treasury process.
INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version, account_name)
SELECT '0000000006', 'NGN', 0, -4, 0, 'PayBridge FX NGN Pool'
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000006');

INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version, account_name)
SELECT '0000000007', 'USD', 100000000, -5, 0, 'PayBridge FX USD Pool (demo liquidity)'
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '0000000007');
