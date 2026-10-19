-- The USD to NGN direction pays NGN out of FX_NGN_POOL, so the pool needs DEMO liquidity, like the USD
-- pool has: NGN 1,000,000,000.00 (100,000,000,000 minor units). Only applied while the pool is still empty.
-- In production the pools would be funded and rebalanced by a real treasury process.
UPDATE accounts
SET balance_minor = 100000000000
WHERE account_number = '0000000006' AND currency = 'NGN' AND balance_minor = 0;
