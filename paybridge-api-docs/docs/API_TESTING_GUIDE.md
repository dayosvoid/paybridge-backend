# PayBridge API Testing Guide (PowerShell + Postman)

This guide targets the current source snapshot and a local app at `http://localhost:8080`. It uses `curl.exe` explicitly so Windows PowerShell does not substitute its historical `curl` alias.

## 0. Start safely

1. Start the intended PostgreSQL instance and confirm database credentials match `DB_URL`, `DB_USER`, and `DB_PASSWORD`.
2. From the repo root run `./mvnw.cmd test` (Windows) before manual API testing. Tests tagged `postgres` are excluded by default; with Docker running, `./mvnw.cmd test -Ppostgres` runs the Postgres-tagged suite.
3. Run the app using the local profile/config you intend to test. Default datasource is `jdbc:postgresql://localhost:5432/paybridge`, user `paybridge`; password comes from `DB_PASSWORD` or the default in `application.yml`. Do not paste real secrets into docs or shell history.
4. Default transfer provider is `FakeTransferGateway` unless Spring profile `paystack` is active. `paybridge.settlement.enabled` is false in the supplied YAML. Fake results are simulated, not real payouts.
5. No Actuator health route exists in this snapshot. Check startup logs and use the public docs page or authenticated API after startup.

Set variables in PowerShell:

```powershell
$BaseUrl = 'http://localhost:8080'
$CustomerEmail = 'customer1@example.com'
$CustomerPassword = 'ChangeMe123!'
$AdminEmail = 'admin@example.com'
$AdminPassword = '<local-bootstrap-admin-password-at-least-12-chars>'
```

The admin must be created through bootstrap config (`PAYBRIDGE_AUTH_BOOTSTRAPADMIN_EMAIL` and `PAYBRIDGE_AUTH_BOOTSTRAPADMIN_PASSWORD`) before login. Public registration always creates `CUSTOMER`.

## 1. Check the app and API docs

```powershell
curl.exe -i "$BaseUrl/"
curl.exe -i "$BaseUrl/swagger-ui/index.html"
curl.exe -i "$BaseUrl/v3/api-docs"
```

A 404 on `/` only means the static demo page is absent in that packaging; Swagger/OpenAPI availability is the useful docs check. Do not expect `/actuator/health`.

## 2. Register a customer

```powershell
$RegisterBody = @{
  email = $CustomerEmail
  password = $CustomerPassword
  fullName = 'Demo Customer One'
} | ConvertTo-Json

curl.exe -i -X POST "$BaseUrl/auth/register" `
  -H 'Content-Type: application/json' `
  -H 'Accept: application/json' `
  --data-raw $RegisterBody
```

Expected: `201 Created` and a `UserView` with `role: CUSTOMER` plus `accountNumber`. Registration opens NGN and USD balances under that same account number. If email already exists, use another email or login instead; the endpoint returns a conflict.

## 3. Login and save tokens

```powershell
$LoginBody = @{ email = $CustomerEmail; password = $CustomerPassword } | ConvertTo-Json
$Login = curl.exe -sS -X POST "$BaseUrl/auth/login" `
  -H 'Content-Type: application/json' -H 'Accept: application/json' `
  --data-raw $LoginBody | ConvertFrom-Json

$AccessToken = $Login.accessToken
$RefreshToken = $Login.refreshToken
$AccountNumber = $Login.user.accountNumber
$AccessToken
$AccountNumber
```

If `$Login` is empty, inspect the response with `curl.exe -i`; login returns 401 for invalid credentials. Tokens are sensitive; don't commit them.

## 4. Current user and account balances

```powershell
curl.exe -i "$BaseUrl/auth/me" -H "Authorization: Bearer $AccessToken"
curl.exe -i "$BaseUrl/accounts/$AccountNumber/balances" -H "Authorization: Bearer $AccessToken"
curl.exe -i "$BaseUrl/accounts/$AccountNumber?currency=NGN" -H "Authorization: Bearer $AccessToken"
curl.exe -i "$BaseUrl/accounts/$AccountNumber/transactions?currency=NGN&page=0&size=20" -H "Authorization: Bearer $AccessToken"
```

Expected: current user, two currency balances, and a paginated statement. A different user's account should be denied with 403. Amounts are minor units, not decimal currency amounts.

## 5. Refresh-token rotation and logout

```powershell
$RefreshBody = @{ refreshToken = $RefreshToken } | ConvertTo-Json
$Rotated = curl.exe -sS -X POST "$BaseUrl/auth/refresh" `
  -H 'Content-Type: application/json' --data-raw $RefreshBody | ConvertFrom-Json
$AccessToken = $Rotated.accessToken
$RefreshToken = $Rotated.refreshToken

# Reuse the OLD token only as a negative test in a separate login/session.
# Reuse detection revokes that refresh-token family; do not do this to the token you intend to keep using.

$LogoutBody = @{ refreshToken = $RefreshToken } | ConvertTo-Json
curl.exe -i -X POST "$BaseUrl/auth/logout" -H 'Content-Type: application/json' --data-raw $LogoutBody
```

`POST /auth/logout` returns 204. To revoke every refresh session for the current user, login again and call:

```powershell
curl.exe -i -X POST "$BaseUrl/auth/logout-all" -H "Authorization: Bearer $AccessToken"
```

`/auth/logout-all` requires access-token auth and returns 204. Access JWTs are not immediately invalidated by refresh-token revocation; they expire naturally.

## 6. Admin-only account operations

Login with the bootstrapped admin:

```powershell
$AdminLoginBody = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json
$AdminLogin = curl.exe -sS -X POST "$BaseUrl/auth/login" -H 'Content-Type: application/json' --data-raw $AdminLoginBody | ConvertFrom-Json
$AdminToken = $AdminLogin.accessToken
```

Open an account only for a customer ID that exists and does not already have one (registration already creates accounts, so this is usually an operational example rather than the normal customer flow):

```powershell
$OpenBody = @{ customerId = 123; accountName = 'Operational Test'; bankCode = '058'; bankName = 'Demo Bank' } | ConvertTo-Json
curl.exe -i -X POST "$BaseUrl/accounts" -H "Authorization: Bearer $AdminToken" -H 'Content-Type: application/json' --data-raw $OpenBody

$StatusBody = @{ status = 'FROZEN' } | ConvertTo-Json
curl.exe -i -X PUT "$BaseUrl/accounts/$AccountNumber/status" -H "Authorization: Bearer $AdminToken" -H 'Content-Type: application/json' --data-raw $StatusBody
```

Restore `ACTIVE` after testing. A customer calling either admin operation should receive 403. Do not freeze an account needed by other test cases.

## 7. Internal transfer

Use two different registered customer accounts you control. Copy the second account number to `$DestinationAccount`. Ensure source has sufficient funds (test fixture or an admin/test-only controlled funding approach; there is no public deposit endpoint documented here).

```powershell
$DestinationAccount = '<second-customer-account-number>'
$InternalKey = [guid]::NewGuid().ToString()
$TransferBody = @{
  sourceAccount = $AccountNumber
  destinationAccount = $DestinationAccount
  amountMinor = 25000
  currency = 'NGN'
} | ConvertTo-Json

curl.exe -i -X POST "$BaseUrl/transfers" `
  -H "Authorization: Bearer $AccessToken" `
  -H 'Content-Type: application/json' `
  -H "Idempotency-Key: $InternalKey" `
  --data-raw $TransferBody
```

No `bankCode` means internal transfer. Expect 201 and a `TransferView`. Re-read both balances and statements. For a retry of the *same* request, reuse `$InternalKey`; for a new transfer, generate a new key. Test duplicate semantics with the same body/key and compare returned reference and ledger effects.

## 8. External transfer using the fake gateway

Use an account number at another bank (10 digits) and a bank code. Last digit controls fake response unless it matches a local PayBridge account at that bank code: `9` immediate failure; `8` pending then verification success; `7` pending then verification failure; other digits success.

```powershell
$ExternalBody = @{
  sourceAccount = $AccountNumber
  destinationAccount = '1234567890'
  amountMinor = 25000
  currency = 'NGN'
  bankCode = '058'
} | ConvertTo-Json
$ExternalKey = [guid]::NewGuid().ToString()
curl.exe -i -X POST "$BaseUrl/transfers" `
  -H "Authorization: Bearer $AccessToken" -H 'Content-Type: application/json' `
  -H "Idempotency-Key: $ExternalKey" --data-raw $ExternalBody
```

Do not interpret a fake `SUCCESS` as proof of an external payout. Save the returned `reference`, then call `GET /transfers/{reference}`. A last digit `8`/`7` is useful for pending/reconciliation tests.

## 9. Transfer history, webhook, and reconciliation

```powershell
curl.exe -i "$BaseUrl/transfers?account=$AccountNumber&status=PENDING&page=0&size=20" -H "Authorization: Bearer $AccessToken"
curl.exe -i "$BaseUrl/admin/reconciliation/run?minAgeSeconds=0&batchSize=50" -H "Authorization: Bearer $AdminToken" -X POST
```

The second call is admin-only; customer token should get 403, no token 401. Reconciliation asks the configured gateway to verify eligible pending references. For Paystack webhook tests, signature must be HMAC-SHA512 of the exact raw JSON bytes using the same `PAYSTACK_SECRET_KEY` configured in the running app. Never guess a signature. For a local fake-gateway demo, use reconciliation instead of inventing a provider signature unless you deliberately compute one with the local test secret.

Example unsigned payload (expected 401; this is intentionally a negative test):

```powershell
$WebhookBody = '{"event":"transfer.success","data":{"reference":"<existing-reference>"}}'
curl.exe -i -X POST "$BaseUrl/webhooks/paystack" -H 'Content-Type: application/json' --data-raw $WebhookBody
```

## 10. FX quote and conversion

```powershell
curl.exe -i "$BaseUrl/fx/quote?source=NGN&destination=USD&amountMinor=10000000" -H "Authorization: Bearer $AccessToken"

$QuoteBody = @{ accountNumber = $AccountNumber; sourceCurrency = 'NGN'; destinationCurrency = 'USD'; sourceAmountMinor = 10000000 } | ConvertTo-Json
$Quote = curl.exe -sS -X POST "$BaseUrl/fx/conversions/quotes" -H "Authorization: Bearer $AccessToken" -H 'Content-Type: application/json' --data-raw $QuoteBody | ConvertFrom-Json
$Quote.quoteId

$ConvertBody = @{ quoteId = $Quote.quoteId } | ConvertTo-Json
$ConversionKey = [guid]::NewGuid().ToString()
curl.exe -i -X POST "$BaseUrl/fx/conversions" -H "Authorization: Bearer $AccessToken" -H 'Content-Type: application/json' -H "Idempotency-Key: $ConversionKey" --data-raw $ConvertBody
```

Default fixed demo rate is 0.00065 NGN→USD, but actual config can override it. The quote is short-lived; use it promptly and do not reuse a consumed quote for a new operation. Conversion requires sufficient funds in the source-currency balance. Follow up with `GET /fx/conversions/{reference}` and inspect both balances/statements.

## 11. Outbound remittance: compare offers, then send

```powershell
$OutboundQuoteBody = @{ sourceAccount = $AccountNumber; sourceAmountMinor = 10000000; destinationCurrency = 'USD' } | ConvertTo-Json
$Offers = curl.exe -sS -X POST "$BaseUrl/outbound-remittances/quotes" -H "Authorization: Bearer $AccessToken" -H 'Content-Type: application/json' --data-raw $OutboundQuoteBody | ConvertFrom-Json
$Offers | Format-Table providerId, providerName, totalDebitMinor, destinationAmountMinor, rate, etaMinutes, quoteId
```

Choose one returned `quoteId`; do not invent one. Current offers are simulated `quickremit` and `swiftsend` examples.

```powershell
$ChosenQuoteId = $Offers[0].quoteId
$SendBody = @{ quoteId = $ChosenQuoteId; beneficiaryName = 'Demo Beneficiary'; beneficiaryAccount = 'US-ACCOUNT-123'; beneficiaryBank = 'Demo Bank' } | ConvertTo-Json
$OutboundKey = [guid]::NewGuid().ToString()
$Outbound = curl.exe -sS -X POST "$BaseUrl/outbound-remittances" -H "Authorization: Bearer $AccessToken" -H 'Content-Type: application/json' -H "Idempotency-Key: $OutboundKey" --data-raw $SendBody | ConvertFrom-Json
$Outbound.reference
```

Check status with `GET /outbound-remittances/{reference}`. The list and manual reconcile endpoints are admin-only. For a pending result, an admin can call `POST /outbound-remittances/{reference}/reconcile` without a body. Provider completion is simulated in the current fake implementation.

## 12. Inbound remittance webhook and signature tests

The inbound webhook uses HMAC-SHA256 hex over this exact canonical string (not the JSON bytes): `providerReference|accountNumber|amountMinor|currency`. Use the same locally configured value as `PAYBRIDGE_REMITTANCE_WEBHOOKSECRET` / `paybridge.remittance.webhook-secret`; do not put real secrets in this guide. Use only a local test secret.

The following PowerShell creates the correct signature for the example request:

```powershell
$WebhookSecret = '<same-local-secret-configured-in-paybridge.remittance.webhook-secret>' # set this explicitly in local app config/environment
$InboundBody = '{"providerReference":"local-in-001","accountNumber":"' + $AccountNumber + '","amountMinor":500000,"currency":"USD","senderName":"Example Sender"}'
$Canonical = "local-in-001|$AccountNumber|500000|USD"
$Hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($WebhookSecret))
try { $Signature = [Convert]::ToHexString($Hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Canonical))).ToLowerInvariant() } finally { $Hmac.Dispose() }
curl.exe -i -X POST "$BaseUrl/webhooks/remittance/inbound" -H 'Content-Type: application/json' -H "X-Signature: $Signature" --data-raw $InboundBody
```

A valid webhook returns 200 with `CREDITED` if the destination account has a USD balance that can be credited, otherwise often `UNMATCHED`/`FAILED`. Inbound payments credit USD; create/register an account first. Query operator views with admin token:

```powershell
curl.exe -i "$BaseUrl/inbound-payments?status=UNMATCHED" -H "Authorization: Bearer $AdminToken"
```

### Inbound reversal webhook

```powershell
$ProviderReference = 'local-in-001'
$Canonical = "reversal|$ProviderReference"
$Hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($WebhookSecret))
try { $Signature = [Convert]::ToHexString($Hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Canonical))).ToLowerInvariant() } finally { $Hmac.Dispose() }
$ReverseBody = @{ providerReference = $ProviderReference; reason = 'Local reversal test' } | ConvertTo-Json
curl.exe -i -X POST "$BaseUrl/webhooks/remittance/inbound/reversal" -H 'Content-Type: application/json' -H "X-Signature: $Signature" --data-raw $ReverseBody
```

If the customer has already spent/converted the inbound funds, reversal may be `REVERSAL_FAILED`; this is an exception requiring operator action, not a clean reversal.

### Outbound returned webhook

Sign `returned|<outbound-reference>` with the same HMAC-SHA256 scheme and send `{"reference":"<reference>","reason":"Local return test"}` to `/webhooks/remittance/outbound/returned`. A successful return should mark `RETURNED` and refund once. Do not test against a reference you need to preserve for another test.

## 13. Settlement

There is **no HTTP endpoint** for settlement in this source snapshot. `SettlementScheduler` is conditional on `paybridge.settlement.enabled=true`, but the supplied `application.yml` explicitly sets it to `false`. To demo locally, enable it through local-only configuration, e.g. `PAYBRIDGE_SETTLEMENT_ENABLED=true`, and be aware defaults wait six hours before eligibility, run every minute, and settle NGN only. For fast tests, the settlement tests override delay/data. Do not manually update balances in the database as a substitute for exercising the service/ledger flow.

## 14. Negative/security tests

```powershell
# No access token: expected 401 JSON
curl.exe -i "$BaseUrl/auth/me"

# Invalid token: expected 401
curl.exe -i "$BaseUrl/auth/me" -H 'Authorization: Bearer not-a-real-token'

# Customer trying admin reconciliation: expected 403
curl.exe -i -X POST "$BaseUrl/admin/reconciliation/run?minAgeSeconds=0&batchSize=50" -H "Authorization: Bearer $AccessToken"

# Missing idempotency key: expected 400 (header is required by controller)
$BadTransfer = @{ sourceAccount = $AccountNumber; destinationAccount = $DestinationAccount; amountMinor = 100; currency = 'NGN' } | ConvertTo-Json
curl.exe -i -X POST "$BaseUrl/transfers" -H "Authorization: Bearer $AccessToken" -H 'Content-Type: application/json' --data-raw $BadTransfer

# Invalid enum/body should be 400
curl.exe -i "$BaseUrl/fx/quote?source=GBP&destination=USD&amountMinor=1000" -H "Authorization: Bearer $AccessToken"
```

Expected generic error format is usually `{"error":"Bad Request","message":"..."}` or `{"error":"Forbidden","message":"You do not have permission to do this"}`. Exact provider/validation messages can vary.

## 15. Postman environment and request order

Create environment `PayBridge Local`:

| Variable | Initial value |
|---|---|
| `baseUrl` | `http://localhost:8080` |
| `customerEmail` | `customer1@example.com` |
| `customerPassword` | `ChangeMe123!` |
| `accessToken` | blank |
| `refreshToken` | blank |
| `adminToken` | blank |
| `accountNumber` | blank |
| `destinationAccount` | blank |
| `transferReference` | blank |
| `conversionQuoteId` | blank |
| `conversionReference` | blank |
| `providerQuoteId` | blank |
| `outboundReference` | blank |
| `idempotencyKey` | set per new operation |

Suggested folders: `01 Auth`, `02 Accounts & Statements`, `03 Transfers`, `04 FX & Conversion`, `05 Remittance`, `06 Webhooks`, `07 Admin & Operations`, `08 Security Negative Tests`. Use the exact JSON examples in the API Reference. In Postman Tests scripts, only store response fields after confirming the request succeeds; never hardcode real credentials.

## 16. Current test coverage references

Relevant current tests include `AuthIdentityTest`, `AuthRefreshTest`, `RoleAccessTest`, `OwnershipTest`, `DualCurrencyAccountTest`, `DualCurrencyStatementTest`, `TransferServiceTest`, `TransferSettlementTest`, `TransferSettlementEligibilityTest`, `ReconciliationTest`, `ReconciliationEdgeCasesTest`, `ConversionServiceTest`, `FxServiceTest`, `OutboundRemittanceTest`, `InboundRemittanceTest`, `InboundWebhookTest`, `ReversalWebhookTest`, `RemittanceReversalTest`, `SettlementServiceTest`, `PostgresMigrationTest`, and `PostgresConcurrencyTest`. The default Maven profile excludes tests tagged `postgres`; run `./mvnw.cmd test -Ppostgres` only when Docker/Testcontainers is available and intended.
