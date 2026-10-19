# PayBridge — Build Roadmap

> Overview, architecture and evidence caveats: [`plan.md`](plan.md). **Read its §9 first: no tests or app runs could be executed during the audit, so every "run" step below is still to be done by you.**
> Defect/suspicion IDs (D1…, S1…) refer to `plan.md` §6.
> Status values: Verified · Implemented — unverified · In progress · Planned · Blocked.

Order is by dependency. **Critical-for-demo path: M0 → M1 → M2 → M3 → M6.** M4, M5 are post-demo unless noted.

---

## Milestone 0 — Reliable baseline
**Objective:** App starts against local PostgreSQL; full test suite result known.
**Priority:** Critical for demo · **Status:** Blocked on config (D1) · **Dependencies:** none

**Tasks**
- [ ] `git status` / confirm branch; the handoff zip had no `.git`, so confirm the zip matches your working tree.
- [ ] Remove or ignore junk in repo root: `src.zip`, `paybridge-src.zip`, `event.json`, `*.log` (zips/logs already in `.gitignore`).
- [ ] Fix D1: either create database `paybridge` (the `application.yml` default) or set `DB_URL=jdbc:postgresql://localhost:5432/paybridge-db`; make `DB_USER`/`DB_PASSWORD` match the container's `POSTGRES_USER`/`POSTGRES_PASSWORD`.
- [ ] Run `./mvnw test`; record every failure (don't edit tests to pass).
- [ ] Start the app with the fake gateway (no `paystack` profile) and confirm Flyway applies V1–V14 and Hibernate `validate` passes.
- [ ] Write the working start command into the demo runbook (M6).

**Acceptance:** `./mvnw test` → `BUILD SUCCESS` (or a recorded failure list); log shows `Successfully applied 14 migrations` (or equivalent) and `Started PayBridgeApplication`.
**Verification:** `./mvnw test`, then `./mvnw spring-boot:run`; `curl localhost:8080/accounts/0000000001`.
**Risks:** Docker DB credentials; stale volume from an older schema (drop and recreate the DB rather than editing migrations); Flyway/`validate` mismatch only visible on PostgreSQL (tests use H2).

---

## Milestone 1 — Core payment flows on the real stack
**Objective:** Prove, on PostgreSQL via the API, what the H2 tests claim.
**Priority:** Critical for demo · **Status:** Verified (H2) / unverified (PostgreSQL, HTTP) · **Dependencies:** M0

**Already covered by tests (do not rewrite):** atomic ledger, internal transfer, idempotency + key-reuse rejection, concurrency, fee/tax, fake-gateway outcomes, reversal exactly once, reconciliation.

**Tasks**
- [ ] Internal transfer `0000000001 → 0000000002` via `POST /transfers` with `Idempotency-Key`; repeat with same key → same reference, one debit.
- [ ] External transfer with `bankCode` to a **non-local** 10-digit destination ending in `1` → `SUCCESS`; check sender debit = principal + fee + tax (see M2 for expected numbers).
- [ ] Destination ending `9` → `FAILED`, sender fully refunded (fee/tax reversed).
- [ ] Destination ending `8` → `PENDING`; `POST /admin/reconciliation/run?minAgeSeconds=0` → `SUCCESS`. Ending `7` → `PENDING` → `FAILED` with refund.
- [ ] Do **not** restart the app between creating a `8`/`7` transfer and reconciling it (the fake's verify answers are in memory → `NOT_FOUND`).
- [ ] Check `GET /accounts/{n}/transactions` shows matching entries.

**Acceptance:** Balances equal expected values for each case; each `ledger_entries.reference` family (`trf-…`, `-FEE`, `-TAX`, `-REV`, `-FEE-REV`, `-TAX-REV`) appears exactly as expected; replay creates no extra rows.
**Verification:** `./mvnw -Dtest=TransferServiceTest,FeeAndTaxTest,ReconciliationTest,ReconciliationEdgeCasesTest,ConcurrentTransferTest test`, plus manual SQL: `select account_number, balance_minor from accounts order by 1;`.
**Risks:** Seeds `0000000001/2` mutate across rehearsals — reset the DB between runs (M6).

---

## Milestone 2 — Settlement demonstration
**Objective:** A repeatable settlement batch that you can show and explain honestly.
**Priority:** Critical for demo · **Status:** Verified (H2) for the core batch; demo mechanics **Planned**; S1 **unverified suspicion** · **Dependencies:** M1

**Tasks**
- [ ] **Resolve S1 first.** Write a failing-or-passing test: external transfer from a funded account to a *local* account at its bank code (e.g. `0000000002`, `058`) → settle with zero delay. Expect either a rolled-back batch (insufficient Settlement balance) or double-debit of Settlement. If confirmed, decide: exclude fake-delivered interbank transfers from settlement, or stop crediting beneficiaries from Settlement. *Do not change settlement logic until the test shows the behaviour.* If you run out of time, **demo with non-local destinations only** and list S1 under known limitations.
- [ ] Provide demo settings (e.g. `application-demo.yml` or env vars): `paybridge.settlement.enabled=true`, `paybridge.settlement.eligibility-delay-seconds=0`, `paybridge.settlement.initial-delay-ms=5000`, `paybridge.settlement.interval-ms=10000`. Keep `enabled: false` as the default.
- [ ] Since no endpoint shows batches (D3), add the SQL for the demo (below) to the runbook; optionally add a read-only `GET /settlement/batches` *only if time remains*.
- [ ] Walk through the numbers below and confirm them on a real run.

**Expected numbers** (transfer of 1,000,000 minor to a non-local account ending `1`; amount > 500,000 and ≤ 5,000,000 ⇒ fee 2,500, tax = round(2,500×7.5%) = 188):

| Moment | Sender | Settlement `…0000` | Fee `…0003` | Tax `…0004` | Clearing `…0005` |
|---|---|---|---|---|---|
| Before | S | 0 | 0 | 0 | 0 |
| After transfer SUCCESS | S − 1,003,688 | +1,000,000 | +2,500 | +188 | 0 |
| After settlement batch | unchanged | back to 0 | unchanged | unchanged | +1,000,000 |

(Account balances start from whatever your DB holds; check *deltas*.) Sender needs ≥ 1,003,688 minor; the seed `0000000001` has 10,000,000.

```sql
select reference, provider, currency, status, total_minor, item_count from settlement_batches order by id desc;
select transfer_reference, amount_minor from settlement_items order by id desc;
select reference, status, succeeded_at, settlement_batch_reference from transfers order by id desc limit 5;
select account_number, balance_minor from accounts where account_number in ('0000000000','0000000003','0000000004','0000000005');
```

**Acceptance:** exactly one batch, `status=POSTED`, `total_minor`=sum of items; transfer carries `settlement_batch_reference`; a second run creates no batch; Fee/Tax balances unchanged by settlement; pending/failed/internal transfers excluded.
**Verification:** `./mvnw -Dtest=SettlementServiceTest,TransferSettlementEligibilityTest,TransferSettlementTest test` (owner's log shows the last class 4/4 pass on 15 Oct; the other two have not been seen passing).
**Wording for the demo:** "PayBridge has recorded that this principal is now handed over to the provider. No bank or provider confirmed it." `RECONCILED`/`EXCEPTION` are never set today — don't show them.
**Risks:** S1; settlement is NGN-only; reversal after settlement is not compensated (D7).

---

## Milestone 3 — Remittance demonstration
**Objective:** Smallest honest remittance demo, matching what the code does.
**Priority:** Critical for demo · **Status:** Verified (H2) for the ledger conversion; HTTP/PostgreSQL unverified; payout **Planned** · **Dependencies:** M0

**What exists (so don't rebuild it):** `GET /fx/quote`, `POST /remittances` (header `Idempotency-Key`), `GET /remittances/{reference}`; atomic two-leg conversion via FX pools; statuses `PENDING/COMPLETED/FAILED`; 12 tests.

**Tasks**
- [ ] Replace `remittance-request.json` with the real contract (D2):
  ```json
  {"sourceAccount":"0000000001","beneficiaryAccount":"<USD acct>","sourceAmountMinor":10000000,"sourceCurrency":"NGN","destinationCurrency":"USD"}
  ```
- [ ] Create a USD account: `POST /accounts {"customerId":<n>,"currency":"USD","accountName":"…"}` (customer 1 already has NGN; one account per currency is allowed). Note its `accountNumber`.
- [ ] `GET /fx/quote?source=NGN&destination=USD&amountMinor=10000000` → destination 6,500, rate 0.00065, `rateType=DEMO_CONFIGURED`.
- [ ] `POST /remittances` → `201`, `COMPLETED`; verify: sender −10,000,000 NGN, FX NGN pool (`…0006`) +10,000,000, FX USD pool (`…0007`) −6,500, beneficiary +6,500 USD; 4 ledger entries with `rem-…-NGN` / `rem-…-USD`.
- [ ] Failure demo: sender with too little NGN → `FAILED`, `failureReason` set, no balance change. Replay with same key → same reference, no second movement.
- [ ] State in narration: "configured rate, both accounts are PayBridge accounts, no external payout, no fee/spread."
- [ ] **Decide (don't silently choose):** do you also want a remittance response field/label saying `simulated`? Today only `rateType` signals it.
- [ ] Optional small test for the HTTP layer (`@WebMvcTest` or `MockMvc`): missing `Idempotency-Key` → 400; unknown currency → 400.

**Acceptance:** quote and remittance numbers match; idempotent retry safe; failed remittance leaves balances untouched.
**Verification:** `./mvnw -Dtest=RemittanceServiceTest,FxServiceTest test`.
**Risks:** D6 (stuck `PENDING` after crash); remittance does not check the two accounts' owners (own-currency conversion and third-party remittance are the same code path); USD pool liquidity is demo-seeded; rate is not live; USD→NGN returns "Unsupported currency pair".

---

## Milestone 4 — Currency model and provider contract (post-demo)
**Objective:** Decide the domain rules before more endpoints.
**Priority:** High · **Status:** Planned · **Dependencies:** M3

**Tasks**
- [ ] Decide: own-account conversion vs third-party remittance — separate endpoints/types?
- [ ] Decide fee/spread on conversion and where it posts (new Fee Income per currency?).
- [ ] Specify rounding (today: destination rounded down) and per-currency precision (NGN/USD both 2 dp, enforced nowhere beyond the assumption in `FxService`).
- [ ] Pick the provider for quote + payout; define one interface (quote, execute payout, status, webhook) — no separate FX-only abstraction unless the provider requires it.
- [ ] Build a `FakeRemittanceProvider` first, then the real adapter; extend `RemittanceStatus` (e.g. `PROCESSING`) and reuse the pending/reconcile pattern from transfers.
- [ ] Make settlement accounts currency/provider-aware before settling USD.

**Acceptance:** written decisions in `plan.md` §7; a test per decision. **Blocked on:** provider selection and sandbox access.

---

## Milestone 5 — Operational reliability (post-demo)
**Priority:** High · **Status:** mixed · **Dependencies:** M0–M2

**Tasks**
- [ ] Webhook tests: bad/missing signature → 401, valid `transfer.success|failed|reversed`, duplicate events idempotent (currently no test class; blank secret ⇒ always 401). Use a test `paystack.secret-key`.
- [ ] Recovery for stuck `PENDING` remittances (D6) and an internal-transfer crash window.
- [ ] Post-settlement reversal compensation (D7) — currently log-only.
- [ ] Testcontainers PostgreSQL job that runs Flyway V1–V14 and the concurrency test.
- [ ] Authentication/authorization (D4); protect `/admin/**`.
- [ ] Gate dev seeds and USD pool liquidity out of production (D5).
- [ ] Settlement `RECONCILED`/`EXCEPTION` from provider reports.
- [ ] Tidy D8 items (duplicate import and stray comment in `FakeTransferGateway`, `index.html` title, README module table).
- [ ] Rotate any Paystack key that ever lived in `application.yml` (the old technical doc says it did).

---

## Milestone 6 — Final demo acceptance
**Objective:** One rehearsed 10-minute run.
**Priority:** Critical · **Dependencies:** M0–M3

**Runbook**
1. Reset DB (drop/create, restart so Flyway re-seeds). Start with demo settings from M2; fake gateway (no `paystack` profile).
2. Internal transfer + idempotent replay.
3. External success (dest ending `1`), then failure (`9`), then pending→success (`8` + `POST /admin/reconciliation/run?minAgeSeconds=0`).
4. Wait ≤ 15 s; show the settlement SQL results (M2).
5. FX quote; create USD account; remittance; show 4 ledger entries and pool balances.
6. Show a failed remittance (insufficient funds).
7. Closing slide: simulated = provider outcomes, FX rate, USD pool liquidity; internal bookkeeping = settlement; not built = real payout, live FX, provider reconciliation, auth.

**Acceptance (all must be true):**
- [ ] `./mvnw test` green, or failures listed and judged irrelevant to the demo.
- [ ] Runbook executed twice from a reset DB with identical results.
- [ ] No claim that fake outcomes are Paystack, that settlement moved bank money, or that the rate is live.
- [ ] S1 either tested and resolved, or demo uses non-local destinations and lists it.

**Known limitations to state:** see `plan.md` §5–6.
