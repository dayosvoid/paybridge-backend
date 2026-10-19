# PayBridge — Project Plan

> **Audit date:** 17 October 2026 · **Method:** source, config, migrations and tests read directly from the handoff zip.
> **Companion:** [`spec.md`](spec.md) holds the ordered, checkable work. This file is the long-lived overview.
>
> **Important evidence caveat.** The audit environment could not reach Maven Central (HTTP 403 from the egress proxy), so **`mvn test` could not be run and nothing below is "re-verified by me"**. Where a claim is labelled *Verified* it rests on (a) a test that exists in the repo and whose assertions were read, and, where noted, (b) the owner's own saved log. See §9.

## 1. Project overview

PayBridge is a Java 21 / Spring Boot 4.1.0 modular monolith (`com.academy.paybridge`) for money movement: ledger-backed accounts, internal and external (bank) transfers with fees and tax, a provider gateway, webhook/reconciliation handling, an internal settlement bookkeeping job, a configured-rate FX quote, and an NGN→USD remittance that converts between two PayBridge accounts. It started as a teaching project (`README.md`), so the stub modules and the original "USD→NGN" wording are aspirational.

Main capabilities that exist in code: accounts (NGN/USD), double-entry-style ledger postings, internal transfers, external transfers (fee + 7.5% tax on fee, hold, provider call, reversal), Paystack webhook, stale-pending reconciliation, NGN settlement batches, FX quote, remittance.

## 2. Goals and priorities

| Horizon | Goal |
|---|---|
| **Immediate (2–3 days)** | A truthful, repeatable demo of: internal transfer → external transfer (fake gateway, incl. a non-success path) → settlement batch → FX quote → remittance. Every simulated or configured element is labelled as such. |
| **Medium** | Verify on PostgreSQL (not only H2), close the gaps in §6, define the multi-currency and provider-payout model. |
| **Long** | Real provider for quote + payout, provider-aware settlement and statement reconciliation, authentication, observability. |

## 3. Architecture (as implemented)

Modules live under `src/main/java/com/academy/paybridge/<module>/{api,domain,repository,service,web,client}`. **Rule:** other modules use only a module's `api` package; enforced by `ModuleRulesTest` (ArchUnit) over the packages `domain, repository, service, web, client`. Empty `.gitkeep` stubs: `customer`, `notification`, plus most of `compliance` (only `AccountDetailsController/Service` exist).

| Module | Actual responsibility | Key classes |
|---|---|---|
| `shared` | `Money`/`Currency` (minor units), `SystemAccounts` constants, `GlobalExceptionHandler`, `CorrelationIdFilter` | `shared/accounts/SystemAccounts.java` |
| `account` | Open/get/freeze accounts, debit/credit under pessimistic lock, `lockInOrder` | `AccountService`, `Account` (debit rejects if non-ACTIVE or insufficient; no overdraft) |
| `ledger` | `post` / `postAll`: validates, locks all touched accounts in ascending order, debits+credits, writes DEBIT and CREDIT `ledger_entries` rows; `@Transactional` so a batch is atomic | `LedgerService`, `StatementService` |
| `transfer` | Internal + external transfers, idempotency, fee/tax, gateway seam, webhook, reconciliation, settlement candidate locking | `TransferService`, `FeeCalculator`, `ReconciliationService`, `PaystackWebhookController` |
| `fx` | Fixed configured NGN→USD rate (`paybridge.fx.ngn-usd-rate`, default `0.00065`), rounds DOWN; `rateType=DEMO_CONFIGURED` | `FxService`, `GET /fx/quote` |
| `remittance` | Persisted NGN→USD conversion between two PayBridge accounts through FX pool accounts | `RemittanceService`, `POST /remittances`, `GET /remittances/{ref}` |
| `settlement` | Scheduled internal batching of successful external-transfer principal | `SettlementService`, `SettlementScheduler` (no HTTP endpoint) |

**Public endpoints found:** `POST/GET /accounts`, `PUT /accounts/{n}/status`; `POST/GET /transfers`, `GET /transfers?account=`; `GET /kyc/account-details` (name resolution, `compliance/web/AccountDetailsController`); statements at `GET /accounts/{accountNumber}/transactions` (`ledger/web/StatementController`); `GET /fx/quote`; `POST/GET /remittances`; `POST /webhooks/paystack`; `POST /admin/reconciliation/run`. **No authentication on any of them.**

**Gateway seam:** `TransferGateway {resolveAccount, send, verify}`. `FakeTransferGateway` is active unless profile `paystack` is on; `PaystackTransferGateway` is active only under profile `paystack` and fails fast without `PAYSTACK_SECRET_KEY`.

**Database:** PostgreSQL + Flyway (`classpath:db/migration` **and** `classpath:db/seed-dev`), `ddl-auto: validate`. Migrations V1–V14 (V3/V6 are demo seeds; V12 clearing account; V13 `remittances`; V14 FX pools). **Tests use H2 with Flyway disabled** and Hibernate `create-drop` plus selected seed SQL, so the Flyway/Postgres schema is *not* exercised by any automated test.

## 4. Financial and domain model

**System accounts** (customer id ≤ 0, status cannot be changed via API):

| Account | Name | Role |
|---|---|---|
| `0000000000` | Settlement (NGN) | Holds external-transfer principal in flight and after success until settled |
| `0000000003` | Fee Income | Fees |
| `0000000004` | Tax Payable | 7.5% of fee (`tax-basis-points=750`, half-up) |
| `0000000005` | Provider Clearing | Cumulative principal that settlement batches have moved out of Settlement |
| `0000000006` | FX NGN Pool | Receives remittance source NGN |
| `0000000007` | FX USD Pool | Pays remittance USD; **seeded with 100,000,000 minor (USD 1,000,000.00) demo liquidity** by V14 |

**Customer accounts.** One account per customer per currency (`existsByCustomerIdAndCurrency`), so a customer *can* hold an NGN and a USD account. Demo seed: `0000000001` (NGN 10,000,000 minor, "Demo Sender"), `0000000002` (NGN 500,000 minor, bank `058` "Demo Bank", "Demo Receiver"). No USD customer account is seeded; create one via `POST /accounts`.

**Internal vs external transfer.** External = request carries a `bankCode`. Internal: one ledger posting, no fees. External: sender is debited principal + fee + tax in one `postAll` (principal → Settlement, fee → Fee Income, tax → Tax Payable) *before* the provider is called. Provider outcomes: SUCCESS→`SUCCESS`; PENDING→`PENDING`; FAILED→mirror-reversal then `FAILED`; provider exception→`PENDING` (no reversal, unknown outcome). Fee tiers: ≤500,000 → 1,000; ≤5,000,000 → 2,500; above → 5,000 (minor units, all configurable under `paybridge.fees.*`).

**Settlement — bookkeeping, not bank funds.** `settleNextBatch(NGN, delay, size)` in one transaction: lock eligible successful *external* transfers (`succeeded_at` older than delay, no batch yet, oldest first, ≤500), create `settlement_batches` + `settlement_items`, mark transfers, and post principal Settlement → Provider Clearing. Fee/tax are not re-posted. Batch status is always `POSTED`; `RECONCILED`/`EXCEPTION` exist in the schema/enum but **nothing sets them**. A `SETTLEMENT → PROVIDER_CLEARING` posting is a PayBridge-internal entry; it is not evidence that any provider or bank moved money.

**FX and remittance.** Quote = `amountMinor × 0.00065`, rounded down; NGN and USD both 2-decimal; only NGN→USD. Remittance: validate + price, persist `PENDING`, then one atomic `postAll` of two legs (`sender → FX_NGN_POOL` in NGN; `FX_USD_POOL → beneficiary` in USD), then `COMPLETED` (or `FAILED` with nothing moved). **The beneficiary must be a PayBridge USD account; there is no external payout, no provider call, no fee/tax on remittance, no FX spread/margin.** Each currency balances on its own; no cross-currency single posting.

## 5. Implementation status

Labels: **Verified** (a relevant test exists and its assertions were read; scope stated) · **Implemented — unverified** · **In progress** · **Planned** · **Blocked**. "Verified" here means *test exists and covers it on H2*; it is not production readiness and I did not run the tests (see §9).

| Area | Status | Evidence / notes |
|---|---|---|
| Money value object | Verified (unit) | `MoneyTest` (8 tests) |
| Accounts, status rules | Verified (partly) | `AccountStatusTest` (5). No test of `AccountController`. |
| Ledger atomic posting | Verified (H2) | `LedgerServiceTest` (5), `StatementTest` (6) |
| Internal transfers, idempotency | Verified (H2) | `TransferServiceTest` (7), `ConcurrentTransferTest` (2), `TransferHistoryTest` (3) |
| Fees + tax | Verified (H2) | `FeeCalculatorTest` (4), `FeeAndTaxTest` (6) |
| External transfer, fake gateway scenarios | Verified (H2) | `TransferServiceTest`, `InterbankSimulationTest` (6) |
| Reconciliation of stale PENDING | Verified (H2) | `ReconciliationTest` (3), `ReconciliationEdgeCasesTest` (6) |
| Paystack status mapping | Verified (unit) | `PaystackStatusMappingTest` (1) |
| Settlement batches | Verified (H2); **owner's log** shows `TransferSettlementTest` 4/4 pass on 15 Oct | `SettlementServiceTest` (9), `TransferSettlementEligibilityTest` (5), `TransferSettlementTest` (4) |
| FX quote | Verified (unit) | `FxServiceTest` (7). Rate is configuration, not live. |
| Remittance (NGN→USD, internal accounts) | **Verified (H2)** — *contradicts the supplied plan.md, which called it in progress* | `RemittanceServiceTest` (12): both legs, rollback of NGN leg if USD leg fails, insufficient funds → `FAILED`, idempotent replay, key reuse with different body rejected, currency/account validation |
| Webhook signature auth | Implemented — unverified | `PaystackWebhookController` HMAC-SHA512, constant-time compare, 401 on blank secret/bad signature. **No test class for it** (the owner's `event.json` is a hand-made `transfer.reversed` payload). |
| Post-success `REVERSED` handling | Verified (H2, pre-settlement only) | `TransferSettlementTest` calls `settleFromProvider(REVERSED)` twice (reversal exactly once). After settlement the code only logs "post-settlement compensation not yet implemented" — untested/unimplemented |
| Paystack real transfer | **Blocked** | Test payouts unavailable (per owner); no HTTP-level tests; calls are coded, never confirmed |
| Paystack account resolution | Implemented — unverified | Coded; not tested against a mock; status against live API unknown |
| Real/live FX quote | **Planned** | |
| Real remittance payout / provider quote | **Planned / Blocked** on provider choice | |
| Settlement HTTP endpoint / batch read API | **Planned** (none exists; batches only visible by SQL) | |
| Settlement reconciliation (`RECONCILED`/`EXCEPTION`) | **Planned** | |
| Settlement for USD / other currencies | **Planned** | `holdingAccount`/`clearingAccount` throw for non-NGN |
| Authentication/authorization | **Planned** | no Spring Security dependency |
| `customer`, `notification`, `compliance` limits | **Planned** | stubs |
| Flyway migrations on PostgreSQL | Implemented — unverified | V1–V14 exist; no automated run (tests disable Flyway) |
| Local PostgreSQL startup | **Blocked (config)** | see §6 D1 |

## 6. Limitations, defects and risks

**Confirmed from files:**

- **D1 — Local startup failed on 15 Oct:** `startup-log.txt` (UTF-16) ends in `FATAL: database "paybridge-db" does not exist` from Flyway. `application.yml` defaults to database `paybridge`; the owner's environment pointed at `paybridge-db`. This is configuration (`DB_URL`/created DB name), not an application bug. An earlier handoff note about a password failure for `paybridge` is *not* in this log.
- **D2 — Stale demo file:** `remittance-request.json` uses `senderAccountNumber`, `beneficiaryAccountNumber`, `amountMinor` and a placeholder beneficiary; the real DTO is `sourceAccount`, `beneficiaryAccount`, `sourceAmountMinor`, `sourceCurrency`, `destinationCurrency`, with header `Idempotency-Key`. That mismatch explains the earlier HTTP 400.
- **D3 — Settlement cannot be demoed with defaults:** `paybridge.settlement.enabled` is `false` in `application.yml`; when enabled the scheduler waits 60 s, then runs every 60 s, and requires transfers to be ≥ 21,600 s (6 h) old. There is no endpoint to trigger or view a batch.
- **D4 — Nothing is authenticated:** anyone can move money from any account and call `/admin/reconciliation/run`.
- **D5 — Dev seed data runs in every environment:** `flyway.locations` includes `classpath:db/seed-dev`, and V14 seeds USD 1,000,000 of pool liquidity. Fine for a demo; unacceptable in production.
- **D6 — Remittance crash window:** the `PENDING` remittance is committed before the ledger post; if the JVM dies in between, it stays `PENDING` and no job resolves it (reconciliation covers transfers only).
- **D7 — Failed reversal after settlement** is only logged (confirmed in code comment); no compensation.
- **D8 — Hygiene:** `FakeTransferGateway.java` contains a duplicate import and a stray comment `// ... (rest of the file as written in diff)`; `static/index.html` is titled "D.Money.Transfer"; `README.md` still describes "USD → NGN" and lists modules that are empty; `PAYBRIDGE_TECHNICAL_DOCS_v2.md` (7 Oct) is **out of date** (says fx/remittance/settlement are "Not started", Flyway absent, `ddl-auto: update`) — treat as history only. Nested `src.zip`/`paybridge-src.zip` are in the repo root (ignored by `*.zip` in `.gitignore`; a scan of their contents for `sk_test_` found none).

**Suspected, not run (needs verification — build.md M2):**

- **S1 — Interbank simulation vs settlement.** `FakeTransferGateway` delivering to a *local* account at the given bank code credits the beneficiary **from Settlement** (`-IN` posting) immediately, yet that transfer is still a `SUCCESS` external transfer and therefore settlement-eligible. A later batch would debit Settlement for the same principal a second time. If Settlement's balance is lower than the batch total, `InsufficientFundsException` rolls the batch back every run (oldest-first ⇒ the failing item keeps blocking later ones); if other funds are in Settlement the system silently double-counts. No test combines `InterbankSimulationTest` behaviour with settlement. **For the demo, use non-local destinations (account ending 1–6, not a seeded account) until this is checked.**
- **S2 — `Transfer` still PENDING after crash between hold and provider call** — improved by the reconciler, but the fake gateway's verify answers are in-memory and lost on restart (documented as "like an outage"); after the grace period (default 1,800 s) such transfers are failed and refunded.
- **S3 — H2-only testing.** Locking/isolation and Flyway/schema parity on PostgreSQL unproven.

## 7. Architectural decisions and constraints (preserved)

- Integer minor units everywhere; explicit rounding (FX rounds down; tax half-up). No Lombok.
- Cross-module access only through `api`; ArchUnit enforces it.
- Ledger postings lock accounts in global ascending order to avoid deadlock; batches are atomic.
- `TransferService.initiate` and `RemittanceService.initiate` are *deliberately not* `@Transactional`, so the business record (incl. `FAILED`) survives a failed money movement.
- Idempotency: unique key per record in DB + replay check that rejects the same key with a different body.
- Hold-then-send for external transfers; unknown provider outcome ⇒ `PENDING`, never reversed blindly.
- One provider abstraction should own quote **and** payout if a provider offers both; do not add a separate FX-provider interface unless required (carried from your decisions).
- Fake gateway for demos; Paystack only for what works in the environment.

**Open design questions:** (1) Is own-account NGN↔USD conversion a distinct flow from external remittance (today both are `POST /remittances`, and nothing checks the two accounts belong to the same/different customer)? (2) Does a remittance charge a fee/spread? (3) Which provider owns the USD payout and its status/webhooks? (4) Should Settlement for fake-delivered interbank transfers differ (S1)?

## 8. Future direction

*Essential after the demo:* PostgreSQL integration tests (Testcontainers) incl. Flyway; webhook tests; fix/prove S1; remittance state machine with provider status; currency/provider-aware settlement accounts; auth.
*Depends on a real provider:* live quotes, payout execution, status webhooks, settlement-report reconciliation (`RECONCILED`/`EXCEPTION`).
*Optional:* settlement read/trigger endpoints, notification module, compliance limits, observability.

## 9. Verification guide

```
./mvnw test                                  # full suite, H2, no DB/network needed
./mvnw -Dtest=SettlementServiceTest,TransferSettlementEligibilityTest,TransferSettlementTest test
./mvnw -Dtest=RemittanceServiceTest,FxServiceTest,ModuleRulesTest test
```
Run the app: `./mvnw spring-boot:run` (needs PostgreSQL; override with `DB_URL`, `DB_USER`, `DB_PASSWORD`). Do **not** set `SPRING_PROFILES_ACTIVE=paystack` for the demo.

| Item | Status |
|---|---|
| Static read of all main sources, migrations, 18 test classes (96 `@Test`s) | Done |
| `mvn test` / `mvn spring-boot:run` in the audit environment | **Not run** — `repo.maven.apache.org` blocked (403 from egress proxy), no local `~/.m2`. JDK 21.0.12 and Maven are present. |
| Owner's saved `test-debug.log` | Shows `TransferSettlementTest` 4/4 pass, BUILD SUCCESS (15 Oct); not a full-suite result |
| Owner's saved `startup-log.txt` | Shows startup failure D1 |
| Anything against PostgreSQL, Paystack, or a running server | Not verified |
