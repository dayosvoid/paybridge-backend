# Settlement Administration

All three endpoints require an ADMIN Bearer access token. Customers receive 403;
missing or invalid tokens receive 401. These endpoints record internal ledger
settlement, not proof of external interbank settlement.

| Endpoint | Success response |
| --- | --- |
| `POST /admin/settlements/run` | 200 `SettlementBatchView`, or 204 with no body when none qualify |
| `GET /admin/settlements/batches?page=0&size=20` | 200 `SettlementBatchPage` |
| `GET /admin/settlements/batches/{reference}` | 200 `SettlementBatchDetails`, or 404 |

The trigger has no request DTO or configurable HTTP inputs. It uses NGN,
`paybridge.settlement.eligibility-delay-seconds` (default 21600) and
`paybridge.settlement.batch-size` (default 50, engine range 1–500).
Query parameters and request bodies cannot override these settings.
Only successful external transfers with no batch reference and a `succeededAt`
at or before the eligibility cutoff qualify. SUCCESS does not mean settled.
Only principal moves; fee and tax remain in their existing accounts.

The existing transaction and transfer row locks are shared with scheduled runs.
Batch, items, transfer marks and ledger postings commit together or roll back.
A run error does not return a successful batch. Restricted accounts or insufficient
funds return 422 through existing exception handling; other failures use existing
400/409/500 handling as applicable. Operators may retry after correcting the cause.
An empty result during a race means that invocation found no remaining eligible
candidates; it does not guarantee another invocation was idle.

History sorts by `createdAt DESC, id DESC`, with page >= 0 and size 1–100.
Details sort items by id ascending. DTOs are:

```java
record SettlementBatchView(String reference, String provider, Currency currency,
        BatchStatus status, long totalMinor, int itemCount, Instant createdAt) {}
record SettlementBatchPage(List<SettlementBatchView> items, int page, int size,
        long totalItems, int totalPages, boolean hasNext) {}
record SettlementBatchDetails(SettlementBatchView batch, List<SettlementItemView> items) {}
record SettlementItemView(String transferReference, long amountMinor, Currency currency) {}
```

`totalMinor` and item `amountMinor` are principal in minor units. Status comes
from the stored batch (`POSTED`, `RECONCILED`, `EXCEPTION`). Only `createdAt`
is available as a public batch timestamp; there is no separate settled timestamp.

## Local Swagger demonstration

1. Start the normal PostgreSQL database and configure `DB_URL`, `DB_USER`, and
   `DB_PASSWORD` as usual. Keep normal migrations and JWT authentication enabled.
2. Configure `paybridge.auth.jwt-secret` with a private secret of at least 32
   bytes, `paybridge.auth.bootstrap-admin.email` with a local admin email, and
   `paybridge.auth.bootstrap-admin.password` with a password of at least 12 characters.
   These are application properties; supply them through your local configuration
   or command-line arguments. Bootstrap only creates an admin when that email does
   not already exist.
3. Keep `paybridge.settlement.enabled=false` to stop automatic runs during the demo.
   Manual runs remain enabled and protected. The default eligibility delay is six
   hours. For a local demo, configure a shorter delay at startup and wait for it to
   pass; the HTTP trigger cannot bypass it. Keep batch size in 1–500.
4. With the offline fake provider (without the `paystack` profile), send a funded
   customer's external NGN transfer using a valid bank code and destination ending
   in 0. Verify its status is SUCCESS, then wait until it is eligible. Internal,
   PENDING and FAILED transfers do not qualify.
5. Open `/swagger-ui/index.html`, execute `/auth/login` with the admin credentials,
   copy `accessToken`, then select **Authorize** and paste the token in `bearerAuth`.
6. Under **Settlement Administration**, execute the run endpoint. A 200 response
   contains the batch reference. Use history and details to inspect its principal
   and transfer references. Run again: with no other eligible transfers, expect 204.
7. Authorize with a customer token to demonstrate 403; clear authorization to
   demonstrate 401.

`paybridge.settlement.interval-ms` and `paybridge.settlement.initial-delay-ms`
control scheduled timing only. `paybridge.settlement.provider` defaults to PAYSTACK.
The provider label does not certify that an external bank settlement occurred.
