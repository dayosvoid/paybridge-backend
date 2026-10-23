# PayBridge API Reference

> Source inspected: the uploaded `paybridge-current.zip` working-tree snapshot. This document describes that snapshot, not an independently accessible Windows directory. Base URL examples assume `http://localhost:8080`; no servlet context path is configured.

## Conventions

- Amounts are integer **minor units**: `100000` NGN means ₦1,000.00; do not send decimal money values.
- Supported `Currency`: `NGN`, `USD`. Account numbers are strings, not JSON numbers.
- Default success content type is JSON unless a route returns `204 No Content` or an empty response.
- Authenticated calls use `Authorization: Bearer <accessToken>`. Registration/login/refresh/logout and `/webhooks/**` are exceptions as noted below.
- `Idempotency-Key` is a required controller header on transfer creation, conversion execution, remittance creation and outbound remittance sending. Use a new key for a new business operation; reuse the same key when retrying the same operation.
- Most errors use `{"error":"...","message":"..."}`. Validation and bad JSON usually return 400; missing/invalid access tokens return 401; role/ownership denials return 403; missing resources return 404; insufficient funds and account restrictions return 422; many `IllegalStateException` conflicts return 409. Some provider/webhook handlers have their own response shapes.
- There is no public customer endpoint to choose an `ADMIN` role. The first admin is bootstrapped from `paybridge.auth.bootstrap-admin.email` and `.password` (environment/config binding is supported by Spring relaxed binding; use `PAYBRIDGE_AUTH_BOOTSTRAPADMIN_EMAIL` and `PAYBRIDGE_AUTH_BOOTSTRAPADMIN_PASSWORD` in a local environment). The bootstrap password must be at least 12 characters.
- No actuator dependency or `/actuator/health` route was found. `/`, `/index.html`, `/swagger-ui/index.html`, `/v3/api-docs`, and `/error` are framework/static/documentation paths, not business controller routes.

## Route inventory

The current application access-decision test (`RoleAccessTest`) enumerates **33 business HTTP routes**. The controller mapping scan found the same route set. The settlement module exposes an API/service and scheduled job but **no HTTP settlement endpoint**.

| Module | Routes |
|---|---:|
| Authentication | 6 |
| Accounts | 4 |
| Statements | 1 |
| Transfers, provider webhook, reconciliation | 5 |
| FX and conversions | 4 |
| Remittances and inbound/outbound operations | 12 |
| KYC/account resolution | 1 |
| **Total** | **33** |

## Authentication and sessions

### `POST /auth/register`

- Full URL: `http://localhost:8080/auth/register`
- Controller: `auth.web.AuthController`; calls `AuthService.register`.
- Purpose/status: create a `CUSTOMER` identity and both NGN/USD balances under one generated account number; `201 Created`.
- Access: public. Headers: `Content-Type: application/json` required for JSON body; bearer token not required.
- Body (`RegisterRequest`):

```json
{"email":"customer1@example.com","password":"ChangeMe123!","fullName":"Demo Customer"}
```

| Field | Rules / meaning |
|---|---|
| `email` | string, required, valid email, max 255; trimmed/lowercased for identity |
| `password` | string, required, 8–72 characters; bcrypt password hash stored |
| `fullName` | string, required, nonblank, max 100 |

- Response: `UserView`, e.g. `{"id":101,"email":"customer1@example.com","fullName":"Demo Customer","role":"CUSTOMER","accountNumber":"0123456789"}` (ID/account number are illustrative).
- Errors: 400 invalid fields; 409 if email already registered. Registration does not return access/refresh tokens; call login next.
- Side effects: user and account creation are wrapped so the identity/account setup is atomic; account service creates NGN and USD balances with zero starting balance.

### `POST /auth/login`

- Full URL: `http://localhost:8080/auth/login`
- Controller/service: `AuthController` → `AuthService.login` → JWT and refresh-token services.
- Access: public. Body:

```json
{"email":"customer1@example.com","password":"ChangeMe123!"}
```

Both fields are required nonblank strings. Response `200 OK`, `TokenResponse`:

```json
{"accessToken":"<jwt>","tokenType":"Bearer","expiresInSeconds":900,"refreshToken":"<opaque-refresh-token>","refreshExpiresInSeconds":604800,"user":{"id":101,"email":"customer1@example.com","fullName":"Demo Customer","role":"CUSTOMER","accountNumber":"0123456789"}}
```

The lifetime numbers above are examples; use the actual response. Invalid credentials/disabled user return `401` with `{"error":"Unauthorized","message":"Invalid email or password"}` (message comes from auth exception). Store access and refresh tokens separately. Refresh tokens are stored as hashes.

### `POST /auth/refresh`

- Full URL: `http://localhost:8080/auth/refresh`
- Access: public; bearer token not required. Body: `{"refreshToken":"<refresh-token>"}` (`refreshToken` required, nonblank).
- Response: `200 OK`, same `TokenResponse` shape as login.
- Invalid, expired, revoked, or reused token: `401`. Successful refresh rotates the refresh token: old token is retired. Reuse detection revokes the whole token family/login.

### `POST /auth/logout`

- Full URL: `http://localhost:8080/auth/logout`
- Access: public; body: `{"refreshToken":"<refresh-token>"}`. `refreshToken` required/nonblank at DTO validation.
- Response: `204 No Content`; no response body. Service is intentionally idempotent for unknown tokens. Revokes the refresh-token family for that login. Existing access JWT remains valid until expiry unless the broader user session policy changes; this endpoint specifically revokes refresh family.

### `POST /auth/logout-all`

- Full URL: `http://localhost:8080/auth/logout-all`
- Access: signed-in user. Header: bearer access token. **Request body: None.**
- Response: `204 No Content`. Revokes all refresh-token sessions for the user identified by JWT subject.

### `GET /auth/me`

- Full URL: `http://localhost:8080/auth/me`
- Access: signed-in user. Headers: `Authorization: Bearer <accessToken>`. **Request body: None.**
- Response `200 OK`: `UserView` (`id`, `email`, `fullName`, `role`, `accountNumber`); password hash is not exposed.

## Accounts and multi-currency balances

### `POST /accounts`

- Full URL: `http://localhost:8080/accounts`
- Access: **ADMIN only** (filter rule); `201 Created`; controller calls `AccountService.open`.
- Body:

```json
{"customerId":101,"accountName":"Demo Customer","bankCode":"058","bankName":"Demo Bank"}
```

`customerId` is required numeric `Long`; `accountName` optional max 100; `bankCode` optional 3–6 digits; `bankName` optional max 100. This is an operational/admin endpoint, not customer self-service. A customer identity registered through `/auth/register` already receives an account. The service prevents a customer ID from having another account. Response is an `AccountView` for the primary NGN balance, e.g. `{"accountNumber":"0123456789","currency":"NGN","balanceMinor":0,"customerId":101,"status":"ACTIVE","bankCode":"058","bankName":"Demo Bank","accountName":"Demo Customer"}` (example values illustrative). Errors include 400 validation and 409 when customer already has an account.

### `GET /accounts/{accountNumber}`

- Full URL: `http://localhost:8080/accounts/0123456789?currency=NGN`
- Access: signed-in user **and account owner** (method-level `@PreAuthorize`). Admin has role access but ownership rules still apply unless `AccountAccess` says otherwise. **Request body: None.**
- Path `accountNumber`: account-number string. Query `currency`: optional `NGN` or `USD`; absent returns primary NGN balance when available.
- Response `200`: `AccountView` fields `accountNumber`, `currency`, `balanceMinor`, `customerId`, `status` (`ACTIVE|FROZEN|CLOSED`), `bankCode`, `bankName`, `accountName`. `404` if account/currency not found; `403` ownership denial.

### `GET /accounts/{accountNumber}/balances`

- Full URL: `http://localhost:8080/accounts/0123456789/balances`
- Access: signed-in account owner. **Request body: None.**
- Response `200`: array of `AccountView`, normally one NGN and one USD entry sharing the account number. `404` unknown account; `403` non-owner.

### `PUT /accounts/{accountNumber}/status`

- Full URL: `http://localhost:8080/accounts/0123456789/status`
- Access: **ADMIN only**. Body: `{"status":"FROZEN"}`. `status` required enum `ACTIVE`, `FROZEN`, `CLOSED`.
- Response `200`: primary `AccountView`; status changes apply to both currency balances under the account number. System accounts cannot be status-changed (`409`); unknown account `404`; invalid status/body `400`; customer `403`.

## Statements and ledger history

### `GET /accounts/{accountNumber}/transactions`

- Full URL: `http://localhost:8080/accounts/0123456789/transactions?currency=NGN&type=DEBIT&from=2026-10-01T00:00:00Z&to=2026-10-31T23:59:59Z&page=0&size=20`
- Access: signed-in account owner. **Request body: None.**
- Path `accountNumber` string. Optional query: `currency=NGN|USD`; `type` string (ledger entry type is `DEBIT` or `CREDIT`; service validates/filters); `from`/`to` ISO-8601 instants; `page` default 0; `size` default 20.
- Response `200`: `StatementPage` fields `accountNumber`, `currency`, `currentBalanceMinor`, `items`, `page`, `size`, `totalItems`, `totalPages`, `hasNext`. Each item is `LedgerEntryView`: `id`, `type`, `kind`, `amountMinor`, `currency`, `reference`, `transferReference`, `createdAt`, `balanceAfterMinor` (may be null when filtering prevents a running balance). `403` ownership; `400` malformed enum/instant or invalid arguments.

## Transfers and provider integration

### `POST /transfers`

- Full URL: `http://localhost:8080/transfers`
- Controller/API: `TransferController` → `TransferApi.initiate` → transfer service, ledger and gateway where external.
- Access: signed-in user and owner of `sourceAccount`; `201 Created`.
- Headers: `Authorization` required; `Content-Type: application/json`; `Idempotency-Key` **required** (missing header is 400).
- Body:

```json
{"sourceAccount":"0123456789","destinationAccount":"0123456788","amountMinor":250000,"currency":"NGN"}
```

Fields: `sourceAccount` and `destinationAccount` nonblank strings; `amountMinor` positive integer minor units; `currency` required `NGN|USD`; optional `bankCode`. No `bankCode` selects internal transfer. A supplied `bankCode` selects external gateway transfer.
- Response `TransferView`: `reference`, `status` (`PENDING|SUCCESS|FAILED`), `sourceAccount`, `destinationAccount`, `amountMinor`, `currency`, `failureReason`, `feeMinor`, `taxMinor`. Example status/reference are illustrative. Invalid request 400; missing account 404 or invalid account 400 depending service path; insufficient funds/account restriction 422; idempotency conflict may be 409; source ownership failure 403.
- Flow: internal transfers debit/credit through ledger; external transfers reserve principal in settlement and book fee/tax according to service policy, then call the configured gateway. Unknown provider result remains pending; webhook/reconciliation finalizes it. Fake gateway is active when `paystack` profile is not active. Fake outcome: destination last digit 9 → FAILED, 8 → initially PENDING then verify SUCCESS, 7 → initially PENDING then verify FAILED, other digits → SUCCESS. Local PayBridge account at matching bank can be credited in fake end-to-end mode. Fake result is not evidence of a real bank payout.

### `GET /transfers/{reference}`

- Full URL: `http://localhost:8080/transfers/trf-example`
- Access: signed-in user; post-authorization permits only an owner of either source or destination account. **Request body: None.**
- Response `200`: `TransferView`; `404` reference not found; `403` caller owns neither endpoint.

### `GET /transfers`

- Full URL: `http://localhost:8080/transfers?account=0123456789&status=PENDING&page=0&size=20`
- Access: signed-in owner of query `account`. **Request body: None.**
- Required query `account`; optional `status` string, `page` default 0, `size` default 20. `TransferPage` response: `items`, `page`, `size`, `totalItems`, `totalPages`, `hasNext`; each item is `TransferView`. Invalid paging/status arguments can yield 400; ownership 403.

### `POST /webhooks/paystack`

- Full URL: `http://localhost:8080/webhooks/paystack`
- Controller: `PaystackWebhookController`; access: public at filter layer but requires valid Paystack HMAC signature. Headers: `x-paystack-signature` required in practice; `Content-Type: application/json` expected. **Signature:** HMAC-SHA512 over the exact raw request bytes, keyed by `paystack.secret-key`, hex encoded. Empty configured secret or missing/invalid signature → `401` empty body.
- Example body for a locally signed test only:

```json
{"event":"transfer.success","data":{"reference":"<existing-paybridge-transfer-reference>"}}
```

Handled event names: `transfer.success`, `transfer.failed`, `transfer.reversed`; unknown/unhandled events are acknowledged. Successful parsing returns `200` empty body; malformed JSON after valid signature returns 400 empty body; a valid but irrelevant event returns 200. Calls `TransferService.settleFromProvider`; use a reference already known to PayBridge. Do not send fabricated signatures to production.

### `POST /admin/reconciliation/run`

- Full URL: `http://localhost:8080/admin/reconciliation/run?minAgeSeconds=0&batchSize=50`
- Access: **ADMIN only**. Headers: bearer admin access token. **Request body: None.**
- Query `minAgeSeconds`: default 0, must be >= 0. `batchSize`: default 50, allowed 1–500. Response `200`: `ReconciliationService.Summary` (inspect actual serialized record fields in the running app; controller returns the service summary directly). Invalid parameters 400; anonymous 401; customer 403. It asks the provider about eligible stale pending transfers and applies definitive outcomes. Scheduled reconciliation is also configured separately; default scheduler enabled unless `paybridge.reconciliation.enabled=false`.

## FX and account conversion

### `GET /fx/quote`

- Full URL: `http://localhost:8080/fx/quote?source=NGN&destination=USD&amountMinor=10000000`
- Access: signed-in user (verified by `RoleAccessTest`). **Request body: None.**
- Required query: `source`, `destination` enum `NGN|USD`; `amountMinor` positive long. Supported pairs are NGN→USD and USD→NGN. Rates are configured demo rates, not live market rates; defaults in service are NGN→USD `0.00065` and USD→NGN `1500`; output labels rate type `DEMO_CONFIGURED`.
- Response `FxQuote`: `sourceCurrency`, `destinationCurrency`, `sourceAmountMinor`, `destinationAmountMinor`, `rate`, `rateType`. Unsupported pair, nonpositive amount or amount rounding below one destination minor unit returns 400.

### `POST /fx/conversions/quotes`

- Full URL: `http://localhost:8080/fx/conversions/quotes`
- Access: signed-in owner permitted to use `accountNumber`; `201 Created`. Body:

```json
{"accountNumber":"0123456789","sourceCurrency":"NGN","destinationCurrency":"USD","sourceAmountMinor":10000000}
```

Fields: account number nonblank; source/destination currencies required `NGN|USD`; amount positive. Response `ConversionQuoteView`: `quoteId`, `accountNumber`, `sourceCurrency`, `destinationCurrency`, `sourceAmountMinor`, `destinationAmountMinor`, `rate`, `rateType`, `expiresAt`. Quote is stored and expires; only supported pair and sufficient source balance rules in service apply. Quote endpoint is not a live provider comparison.

### `POST /fx/conversions`

- Full URL: `http://localhost:8080/fx/conversions`
- Access: signed-in owner of account attached to quote; `201 Created`.
- Headers: bearer token, JSON content type, `Idempotency-Key` required. Body: `{"quoteId":"<quote-id-from-previous-response>"}`; `quoteId` nonblank.
- Response `ConversionView`: `reference`, `status` (`PENDING|COMPLETED|FAILED`), `quoteId`, `accountNumber`, `sourceCurrency`, `destinationCurrency`, `sourceAmountMinor`, `destinationAmountMinor`, `rate`, `rateType`, `failureReason`, `createdAt`. Uses the quote rate and ledger conversion path. Expired/invalid quote or duplicate/idempotency conflicts surface as service errors (typically 400/409).

### `GET /fx/conversions/{reference}`

- Full URL: `http://localhost:8080/fx/conversions/cnv-example`
- Access: signed-in account owner (post-authorization). **Request body: None.** Response `200` `ConversionView`; 404 missing reference; 403 non-owner.

## Remittance

### `POST /remittances`

- Full URL: `http://localhost:8080/remittances`
- Access: signed-in owner of `sourceAccount`; `201 Created`. Headers: bearer, JSON, required `Idempotency-Key`.
- Body:

```json
{"sourceAccount":"0123456789","beneficiaryAccount":"US-BENEFICIARY-001","sourceAmountMinor":10000000,"sourceCurrency":"NGN","destinationCurrency":"USD"}
```

`sourceAccount` and `beneficiaryAccount` nonblank; amount positive; currencies required `NGN|USD`. `RemittanceService` uses configured FX calculation; this route is not the same as provider-comparison `/outbound-remittances/quotes`. Response `RemittanceView`: `reference`, `status` (`PENDING|COMPLETED|FAILED`), `sourceAccount`, `beneficiaryAccount`, `sourceCurrency`, `destinationCurrency`, `sourceAmountMinor`, `destinationAmountMinor`, `rate`, `rateType`, `failureReason`, `createdAt`. Ownership, funds, account status and idempotency affect result. Provider payout behavior must not be assumed from this record alone.

### `GET /remittances/{reference}`

- Full URL: `http://localhost:8080/remittances/rem-example`
- Access: signed-in owner of source account. **Request body: None.** Response `200` `RemittanceView`; `404` missing; `403` non-owner.

### `POST /outbound-remittances/quotes`

- Full URL: `http://localhost:8080/outbound-remittances/quotes`
- Access: signed-in owner of source account; `201 Created`. Body:

```json
{"sourceAccount":"0123456789","sourceAmountMinor":10000000,"destinationCurrency":"USD"}
```

Fields: nonblank account, positive source amount, required destination currency. Current fake providers are `quickremit` and `swiftsend`; offers are stored with expiry and sorted best-first. Example defaults: QuickRemit rate `0.00062`, fee `100000` minor NGN, ETA 15 minutes; SwiftSend rate `0.00064`, fee `250000` minor NGN, ETA 1440 minutes. These are simulated offers, not live FX/payout guarantees. Response is an array of `ProviderQuoteView`: `quoteId`, `providerId`, `providerName`, `sourceAccount`, `sourceAmountMinor`, `feeMinor`, `totalDebitMinor`, `destinationCurrency`, `destinationAmountMinor`, `rate`, `etaMinutes`, `tags`, `expiresAt`.

### `POST /outbound-remittances`

- Full URL: `http://localhost:8080/outbound-remittances`
- Access: signed-in owner of the source account attached to quote (controller explicitly checks ownership); `201 Created`.
- Headers: bearer, JSON, `Idempotency-Key` required. Body:

```json
{"quoteId":"<quote-id>","beneficiaryName":"Demo Beneficiary","beneficiaryAccount":"US-ACCOUNT-123","beneficiaryBank":"Demo Bank"}
```

`quoteId`, `beneficiaryName`, `beneficiaryAccount` required nonblank; `beneficiaryBank` optional string. Response `OutboundRemittanceView`: `reference`, `status` (`PENDING|COMPLETED|FAILED|RETURNED`), `quoteId`, `providerId`, `providerReference`, `sourceAccount`, `sourceAmountMinor`, `feeMinor`, `destinationCurrency`, `destinationAmountMinor`, `rate`, beneficiary fields, `failureReason`, `createdAt`. A pending provider result is not proof of completion. Fake remittance gateways are used in this snapshot; no real provider payout implementation was found in these gateway classes.

### `GET /outbound-remittances`

- Full URL: `http://localhost:8080/outbound-remittances?status=PENDING`
- Access: **ADMIN only** (explicit security rule and `RoleAccessTest`). **Request body: None.** Optional `status` string; known status enum `PENDING`, `COMPLETED`, `FAILED`, `RETURNED`. Response array of `OutboundRemittanceView`.

### `GET /outbound-remittances/{reference}`

- Full URL: `http://localhost:8080/outbound-remittances/out-example`
- Access: signed-in user; post-authorization checks source-account ownership. **Request body: None.** Response `200` `OutboundRemittanceView`; 404 missing; 403 non-owner.

### `POST /outbound-remittances/{reference}/reconcile`

- Full URL: `http://localhost:8080/outbound-remittances/out-example/reconcile`
- Access: **ADMIN only** by method-specific security matcher. **Request body: None.** Calls provider verification for the remittance and only finalizes on definite answer. Response `200` `OutboundRemittanceView`; 404 missing; anonymous 401/customer 403. The scheduled outbound reconciler also polls pending records if enabled (default enabled).

### `GET /inbound-payments`

- Full URL: `http://localhost:8080/inbound-payments?status=UNMATCHED`
- Access: **ADMIN only**. **Request body: None.** Optional `status` (`RECEIVED|CREDITED|UNMATCHED|FAILED|REVERSED|REVERSAL_FAILED`). Response array of `InboundPaymentView`.

### `GET /inbound-payments/{reference}`

- Full URL: `http://localhost:8080/inbound-payments/in-example`
- Access: **ADMIN only**. **Request body: None.** Response `200` `InboundPaymentView`: `reference`, `providerReference`, `status`, `accountNumber`, `currency`, `amountMinor`, `senderName`, `failureReason`, `receivedAt`, `creditedAt`; 404 missing.

### `POST /webhooks/remittance/inbound`

- Full URL: `http://localhost:8080/webhooks/remittance/inbound`
- Access: public filter path, but signed webhook required. Headers: `Content-Type: application/json`; `X-Signature` required. Signature scheme: HMAC-SHA256 hex of canonical UTF-8 string `providerReference|accountNumber|amountMinor|currency`, keyed by `paybridge.remittance.webhook-secret`. A built-in development fallback exists in source; do not rely on it outside isolated local tests, and override it before real use.
- Body:

```json
{"providerReference":"provider-in-001","accountNumber":"0123456789","amountMinor":500000,"currency":"USD","senderName":"Example Sender"}
```

`providerReference` and `accountNumber` nonblank; `amountMinor` positive; currency required; sender name optional. Valid signature returns `200` with `InboundPaymentView`, including `CREDITED` when matched/credited or `UNMATCHED` when no USD account matches. Invalid/missing signature returns `401` `{"error":"Unauthorized","message":"Invalid signature"}`. `UNMATCHED` is acknowledged with 200 to avoid pointless provider retries; operator review is required.

### `POST /webhooks/remittance/inbound/reversal`

- Full URL: `http://localhost:8080/webhooks/remittance/inbound/reversal`
- Access: public filter path, signed webhook required. Header `X-Signature`: HMAC-SHA256 hex of `reversal|providerReference`. Body:

```json
{"providerReference":"provider-in-001","reason":"Provider chargeback"}
```

`providerReference` nonblank; reason optional. Valid signature returns `200` with updated `InboundPaymentView`; invalid/missing signature returns 401 JSON above. A reversal can produce `REVERSED`; if funds cannot be debited because the customer already spent/converted them, status `REVERSAL_FAILED` flags an operator exception. Repeated provider report is intended to retry reversal handling idempotently.

### `POST /webhooks/remittance/outbound/returned`

- Full URL: `http://localhost:8080/webhooks/remittance/outbound/returned`
- Access: public filter path, signed webhook required. Header `X-Signature`: HMAC-SHA256 hex of `returned|reference` (PayBridge outbound reference). Body:

```json
{"reference":"out-example","reason":"Beneficiary account closed"}
```

`reference` nonblank; reason optional. Valid signature returns 200 with updated `OutboundRemittanceView`; invalid signature returns 401 JSON. A returned completed payout is marked `RETURNED` and the amount is refunded once according to service rules.

## KYC / bank account resolution

### `GET /kyc/account-details`

- Full URL: `http://localhost:8080/kyc/account-details?accountNumber=0123456789&bankCode=058`
- Access: signed-in user (not admin-only); no account ownership check is visible on this read/resolve route. **Request body: None.** Required query strings `accountNumber`, `bankCode`. Response `ResolvedAccountView`: `accountNumber`, `accountName`, `bankName`. `IllegalArgumentException` is converted locally to `400` with `{"error":"..."}`. Under fake gateway profile, non-local valid 10-digit accounts can return simulated `FAKE ACCOUNT HOLDER` and `FAKE BANK (...)`; with Paystack profile this calls the provider and depends on credentials/provider limits.

## Operational, static and documentation routes

- `GET /` and `GET /index.html`: public demo/static page if present in packaging.
- `GET /swagger-ui/index.html`: public Swagger UI when springdoc is available.
- `GET /v3/api-docs` (and `/v3/api-docs/**`): public OpenAPI JSON.
- `/error`: Spring Boot error dispatch endpoint.
- No `/actuator/health` endpoint was found; do not use it as a readiness check. Use successful startup logs and a safe authenticated endpoint such as `/auth/me`, or add Actuator deliberately if a health endpoint is required.

## Scheduled jobs that do not expose routes

- `SettlementScheduler`: no HTTP route. Conditional on `paybridge.settlement.enabled=true` (in this snapshot `application.yml` sets it to `false`). Defaults when enabled: initial delay 60 s, every 60 s, NGN only, eligibility delay 21,600 s (6 hours), batch size 50. Calls `SettlementApi.settleNextBatch` and posts settlement principal to provider-clearing ledger account.
- `ReconciliationScheduler`: no HTTP route. Enabled unless `paybridge.reconciliation.enabled=false`; defaults min age 300 s, batch size 50, first run after 30 s, then 60 s interval.
- `OutboundRemittanceReconciler`: no HTTP route. Enabled unless `paybridge.remittance.reconciliation.enabled=false`; defaults min age 120 s, batch size 50, first run after 60 s, then 60 s interval.

## Security and confidence notes

- `SecurityConfig` is stateless JWT resource-server security; JWT role claim is mapped to `ROLE_<role>`. `RoleAccessTest` enumerates all 33 application routes and checks public/signed-in/admin access; `OwnershipTest` tests ownership restrictions.
- ADMIN-only: `POST /accounts`, `PUT /accounts/{accountNumber}/status`, `/admin/**`, `GET /inbound-payments` and descendants, `GET /outbound-remittances`, `POST /outbound-remittances/*/reconcile`.
- Account ownership checks are method-level on account balances/details, statement, transfer creation/list/detail, conversions, remittances and outbound detail/creation/quotes. The KYC route only requires a signed-in user, as declared in route access test; consider whether account-resolution abuse needs throttling/audit.
- Webhook routes are exempt from bearer auth but validate signatures in their controllers. The inbound-remittance signature default is insecure for non-local deployment and is logged as a warning.
- `GET /fx/quote` is authenticated despite its public-looking quote purpose; confirmed by route access test.
- Settlement has service/tests but no manual route; with the shipped YAML value it is disabled unless overridden.

## Compact success-response examples (one per business route)

Values such as IDs, references, timestamps and account numbers below are illustrative. Property names and record shapes are taken from the response DTO/controller return types. These are JSON examples, not fixtures guaranteed to exist in a fresh database.

| Route | Example response body |
|---|---|
| `POST /auth/register` | `{"id":101,"email":"customer1@example.com","fullName":"Demo Customer","role":"CUSTOMER","accountNumber":"0123456789"}` |
| `POST /auth/login` | `{"accessToken":"<jwt>","tokenType":"Bearer","expiresInSeconds":900,"refreshToken":"<opaque-token>","refreshExpiresInSeconds":604800,"user":{"id":101,"email":"customer1@example.com","fullName":"Demo Customer","role":"CUSTOMER","accountNumber":"0123456789"}}` |
| `POST /auth/refresh` | Same `TokenResponse` shape as login, with newly rotated tokens. |
| `POST /auth/logout` | Empty body; HTTP 204. |
| `POST /auth/logout-all` | Empty body; HTTP 204. |
| `GET /auth/me` | `{"id":101,"email":"customer1@example.com","fullName":"Demo Customer","role":"CUSTOMER","accountNumber":"0123456789"}` |
| `POST /accounts` | `{"accountNumber":"0123456789","currency":"NGN","balanceMinor":0,"customerId":101,"status":"ACTIVE","bankCode":"058","bankName":"Demo Bank","accountName":"Demo Customer"}` |
| `GET /accounts/{accountNumber}` | `{"accountNumber":"0123456789","currency":"NGN","balanceMinor":100000,"customerId":101,"status":"ACTIVE","bankCode":"058","bankName":"Demo Bank","accountName":"Demo Customer"}` |
| `GET /accounts/{accountNumber}/balances` | `[{"accountNumber":"0123456789","currency":"NGN","balanceMinor":100000,"customerId":101,"status":"ACTIVE","bankCode":"058","bankName":"Demo Bank","accountName":"Demo Customer"},{"accountNumber":"0123456789","currency":"USD","balanceMinor":500,"customerId":101,"status":"ACTIVE","bankCode":"058","bankName":"Demo Bank","accountName":"Demo Customer"}]` |
| `PUT /accounts/{accountNumber}/status` | `{"accountNumber":"0123456789","currency":"NGN","balanceMinor":100000,"customerId":101,"status":"FROZEN","bankCode":"058","bankName":"Demo Bank","accountName":"Demo Customer"}` |
| `GET /accounts/{accountNumber}/transactions` | `{"accountNumber":"0123456789","currency":"NGN","currentBalanceMinor":100000,"items":[{"id":1,"type":"CREDIT","kind":"TRANSFER","amountMinor":100000,"currency":"NGN","reference":"trf-example","transferReference":"trf-example","createdAt":"2026-10-20T12:00:00Z","balanceAfterMinor":100000}],"page":0,"size":20,"totalItems":1,"totalPages":1,"hasNext":false}` |
| `POST /transfers` | `{"reference":"trf-example","status":"SUCCESS","sourceAccount":"0123456789","destinationAccount":"0123456788","amountMinor":25000,"currency":"NGN","failureReason":null,"feeMinor":0,"taxMinor":0}` |
| `GET /transfers/{reference}` | Same `TransferView` shape as `POST /transfers`. |
| `GET /transfers` | `{"items":[{"reference":"trf-example","status":"PENDING","sourceAccount":"0123456789","destinationAccount":"1234567898","amountMinor":25000,"currency":"NGN","failureReason":null,"feeMinor":0,"taxMinor":0}],"page":0,"size":20,"totalItems":1,"totalPages":1,"hasNext":false}` |
| `POST /webhooks/paystack` | Empty body; HTTP 200 after valid signature and successful acknowledgement. |
| `POST /admin/reconciliation/run` | `{"checked":2,"resolvedSuccess":1,"resolvedFailed":0,"stillPending":1,"errors":0}` |
| `GET /fx/quote` | `{"sourceCurrency":"NGN","destinationCurrency":"USD","sourceAmountMinor":10000000,"destinationAmountMinor":6500,"rate":0.00065,"rateType":"DEMO_CONFIGURED"}` |
| `POST /fx/conversions/quotes` | `{"quoteId":"quote-example","accountNumber":"0123456789","sourceCurrency":"NGN","destinationCurrency":"USD","sourceAmountMinor":10000000,"destinationAmountMinor":6500,"rate":0.00065,"rateType":"DEMO_CONFIGURED","expiresAt":"2026-10-20T12:05:00Z"}` |
| `POST /fx/conversions` | `{"reference":"cnv-example","status":"COMPLETED","quoteId":"quote-example","accountNumber":"0123456789","sourceCurrency":"NGN","destinationCurrency":"USD","sourceAmountMinor":10000000,"destinationAmountMinor":6500,"rate":0.00065,"rateType":"DEMO_CONFIGURED","failureReason":null,"createdAt":"2026-10-20T12:00:00Z"}` |
| `GET /fx/conversions/{reference}` | Same `ConversionView` shape as `POST /fx/conversions`. |
| `POST /remittances` | `{"reference":"rem-example","status":"PENDING","sourceAccount":"0123456789","beneficiaryAccount":"US-BENEFICIARY-001","sourceCurrency":"NGN","destinationCurrency":"USD","sourceAmountMinor":10000000,"destinationAmountMinor":6500,"rate":0.00065,"rateType":"DEMO_CONFIGURED","failureReason":null,"createdAt":"2026-10-20T12:00:00Z"}` |
| `GET /remittances/{reference}` | Same `RemittanceView` shape as `POST /remittances`. |
| `POST /outbound-remittances/quotes` | `[{"quoteId":"provider-quote-example","providerId":"swiftsend","providerName":"SwiftSend","sourceAccount":"0123456789","sourceAmountMinor":10000000,"feeMinor":250000,"totalDebitMinor":10250000,"destinationCurrency":"USD","destinationAmountMinor":6400,"rate":0.00064,"etaMinutes":1440,"tags":["BEST_RATE"],"expiresAt":"2026-10-20T12:05:00Z"}]` |
| `POST /outbound-remittances` | `{"reference":"out-example","status":"PENDING","quoteId":"provider-quote-example","providerId":"swiftsend","providerReference":"FAKE-EXAMPLE","sourceAccount":"0123456789","sourceAmountMinor":10000000,"feeMinor":250000,"destinationCurrency":"USD","destinationAmountMinor":6400,"rate":0.00064,"beneficiaryName":"Demo Beneficiary","beneficiaryAccount":"US-ACCOUNT-123","beneficiaryBank":"Demo Bank","failureReason":null,"createdAt":"2026-10-20T12:00:00Z"}` |
| `GET /outbound-remittances` | JSON array of `OutboundRemittanceView` objects (same object shape as next row). |
| `GET /outbound-remittances/{reference}` | Same `OutboundRemittanceView` shape as `POST /outbound-remittances`. |
| `POST /outbound-remittances/{reference}/reconcile` | Same `OutboundRemittanceView` shape, with status updated only if provider result is definitive. |
| `GET /inbound-payments` | `[{"reference":"in-example","providerReference":"provider-in-001","status":"CREDITED","accountNumber":"0123456789","currency":"USD","amountMinor":500000,"senderName":"Example Sender","failureReason":null,"receivedAt":"2026-10-20T12:00:00Z","creditedAt":"2026-10-20T12:00:01Z"}]` |
| `GET /inbound-payments/{reference}` | Same `InboundPaymentView` shape as previous row. |
| `POST /webhooks/remittance/inbound` | `{"reference":"in-example","providerReference":"provider-in-001","status":"CREDITED","accountNumber":"0123456789","currency":"USD","amountMinor":500000,"senderName":"Example Sender","failureReason":null,"receivedAt":"2026-10-20T12:00:00Z","creditedAt":"2026-10-20T12:00:01Z"}` |
| `POST /webhooks/remittance/inbound/reversal` | Updated `InboundPaymentView`; status commonly `REVERSED`, or `REVERSAL_FAILED` when funds cannot be recovered. |
| `POST /webhooks/remittance/outbound/returned` | Updated `OutboundRemittanceView` with status `RETURNED` when a valid return is processed. |
| `GET /kyc/account-details` | `{"accountNumber":"0123456789","accountName":"Demo Customer","bankName":"Demo Bank"}` |

Some illustrative entries use the `EntryKind` enum value `TRANSFER` for readability; use the enum constants actually emitted by the relevant ledger operation in a live response. The example amounts/rates are not a guarantee of current balances or quote validity.
