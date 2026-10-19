-- Gives every existing customer account a USD balance under the same account number.
-- Customers that already have a USD account of their own (under another number) are left alone.
-- System accounts (customer_id <= 0) are not touched.
INSERT INTO accounts (account_number, currency, balance_minor, customer_id, version,
                      status, bank_code, bank_name, account_name)
SELECT a.account_number, 'USD', 0, a.customer_id, 0,
       a.status, a.bank_code, a.bank_name, a.account_name
FROM accounts a
WHERE a.customer_id > 0
  AND a.currency = 'NGN'
  AND NOT EXISTS (SELECT 1 FROM accounts u
                  WHERE u.customer_id = a.customer_id AND u.currency = 'USD');
