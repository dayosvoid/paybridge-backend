package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sending money abroad through the two fake providers. Own in-memory database so the clearing account
 * is not disturbed by other classes.
 *
 * For NGN 100,000.00 (10,000,000 minor):
 *   SwiftSend  : recipient gets USD 64.00 (6,400),  fee NGN 2,500.00 (250,000), total debit 10,250,000, slow
 *   QuickRemit : recipient gets USD 62.00 (6,200),  fee NGN 1,000.00 (100,000), total debit 10,100,000, fast
 * The last digit of the beneficiary account picks the fake provider's behaviour (see FakeRemittanceGateway).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-outbound;LOCK_TIMEOUT=10000")
class OutboundRemittanceTest {

    private static final String CLEARING = "0000000008";
    private static final long AMOUNT = 10_000_000;
    private static final long SWIFT_TOTAL = 10_250_000;
    private static final long QUICK_TOTAL = 10_100_000;
    private static final long FUNDED = 20_000_000;

    private static final String OK = "1234567890";
    private static final String REJECTED = "1234567899";          // 9: FAILED now
    private static final String PENDING_THEN_OK = "1234567898";   // 8: PENDING, SUCCESS when verified
    private static final String PENDING_THEN_FAIL = "1234567897"; // 7: PENDING, FAILED when verified
    private static final String TIMEOUT_THEN_OK = "1234567896";   // 6: exception, SUCCESS when verified
    private static final String TIMEOUT_UNKNOWN = "1234567895";   // 5: exception, NOT_FOUND when verified

    @Autowired OutboundRemittanceApi outbound;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String createAccount(long ngnMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customer = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        for (String currency : new String[] {"NGN", "USD"}) {
            jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                            + "status, bank_code, bank_name, account_name) "
                            + "values (?, ?, ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                    number, currency, currency.equals("NGN") ? ngnMinor : 0, customer);
        }
        return number;
    }

    private long ngn(String number) {
        return accounts.findByNumber(number, NGN).orElseThrow().balanceMinor();
    }

    private List<ProviderQuoteView> quotes(String account) {
        return outbound.getQuotes(account, AMOUNT, USD);
    }

    private static ProviderQuoteView from(List<ProviderQuoteView> quotes, String providerId) {
        return quotes.stream().filter(q -> q.providerId().equals(providerId)).findFirst().orElseThrow();
    }

    private OutboundRemittanceView send(ProviderQuoteView quote, String beneficiaryAccount) {
        return outbound.send(new OutboundRemittanceRequest(quote.quoteId(), "Jane Roe", beneficiaryAccount,
                "Chase"), UUID.randomUUID().toString());
    }

    private OutboundRemittanceView sendVia(String providerId, String account, String beneficiaryAccount) {
        return send(from(quotes(account), providerId), beneficiaryAccount);
    }

    private long entries(String reference) {
        return jdbc.queryForObject("select count(*) from ledger_entries where reference = ?", Long.class, reference);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    // ---------- quotes ----------

    @Test
    void everyProviderQuotesAndTheBestOfferComesFirst() {
        String account = createAccount(FUNDED);
        long stored = count("provider_quotes");

        List<ProviderQuoteView> q = quotes(account);

        assertThat(q).hasSize(2);
        assertThat(q).extracting(ProviderQuoteView::providerId).containsExactly("swiftsend", "quickremit");
        ProviderQuoteView swift = q.get(0);
        ProviderQuoteView quick = q.get(1);
        assertThat(swift.destinationAmountMinor()).isEqualTo(6_400);
        assertThat(swift.feeMinor()).isEqualTo(250_000);
        assertThat(swift.totalDebitMinor()).isEqualTo(SWIFT_TOTAL);
        assertThat(quick.destinationAmountMinor()).isEqualTo(6_200);
        assertThat(quick.totalDebitMinor()).isEqualTo(QUICK_TOTAL);
        assertThat(swift.tags()).containsExactly("BEST_RATE");
        assertThat(quick.tags()).containsExactlyInAnyOrder("FASTEST", "LOWEST_FEE");
        assertThat(swift.providerName()).isEqualTo("SwiftSend");
        assertThat(swift.expiresAt()).isAfter(Instant.now());
        assertThat(swift.quoteId()).isNotEqualTo(quick.quoteId());
        assertThat(count("provider_quotes")).isEqualTo(stored + 2);
        assertThat(ngn(account)).isEqualTo(FUNDED);              // quoting moves no money
    }

    @Test
    void invalidQuoteRequestsAreRejectedAndStoreNothing() {
        String account = createAccount(FUNDED);
        String usdOnly = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                + "status, bank_code, bank_name, account_name) values (?, 'USD', 0, ?, 0, 'ACTIVE', '999', 'x', 'x')",
                usdOnly, ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));
        long before = count("provider_quotes");

        assertThatThrownBy(() -> outbound.getQuotes(account, 0, USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.getQuotes(account, -1, USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.getQuotes(account, AMOUNT, NGN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.getQuotes(account, AMOUNT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.getQuotes(account, 1_000, USD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too small");
        assertThatThrownBy(() -> outbound.getQuotes(" ", AMOUNT, USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.getQuotes("9999999999", AMOUNT, USD))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> outbound.getQuotes(usdOnly, AMOUNT, USD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no NGN balance");

        assertThat(count("provider_quotes")).isEqualTo(before);
    }

    // ---------- the happy path ----------

    @Test
    void sendingWithTheChosenQuoteHoldsTheNairaAndTheProviderPaysOut() {
        String account = createAccount(FUNDED);
        long clearingBefore = ngn(CLEARING);

        OutboundRemittanceView r = sendVia("swiftsend", account, OK);

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.reference()).startsWith("out-");
        assertThat(r.providerId()).isEqualTo("swiftsend");
        assertThat(r.providerReference()).startsWith("SWIFTSEND-");
        assertThat(r.destinationAmountMinor()).isEqualTo(6_400);
        assertThat(r.beneficiaryAccount()).isEqualTo(OK);
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);          // amount plus fee
        assertThat(ngn(CLEARING)).isEqualTo(clearingBefore + SWIFT_TOTAL);  // held, owed to the provider
        assertThat(entries(r.reference() + "-HOLD")).isEqualTo(2);
        assertThat(entries(r.reference() + "-REV")).isZero();
    }

    @Test
    void theCustomerCanPickTheOtherProviderInstead() {
        String account = createAccount(FUNDED);

        OutboundRemittanceView r = sendVia("quickremit", account, OK);

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.providerId()).isEqualTo("quickremit");
        assertThat(r.destinationAmountMinor()).isEqualTo(6_200);
        assertThat(ngn(account)).isEqualTo(FUNDED - QUICK_TOTAL);
    }

    // ---------- the provider says nothing was sent: refund ----------

    @Test
    void aProviderThatConfirmsNothingWasSentTriggersARefund() {
        String account = createAccount(FUNDED);
        long clearingBefore = ngn(CLEARING);

        OutboundRemittanceView r = sendVia("swiftsend", account, REJECTED);

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.failureReason()).contains("rejected");
        assertThat(ngn(account)).isEqualTo(FUNDED);                // everything came back
        assertThat(ngn(CLEARING)).isEqualTo(clearingBefore);
        assertThat(entries(r.reference() + "-HOLD")).isEqualTo(2);
        assertThat(entries(r.reference() + "-REV")).isEqualTo(2);
    }

    // ---------- the outcome is not known: no refund until the provider says so ----------

    @Test
    void aPendingPaymentKeepsTheMoneyHeldAndCompletesOnceTheProviderConfirms() {
        String account = createAccount(FUNDED);
        long clearingBefore = ngn(CLEARING);

        OutboundRemittanceView r = sendVia("swiftsend", account, PENDING_THEN_OK);

        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.providerReference()).isNotNull();
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);          // still held, not refunded
        assertThat(ngn(CLEARING)).isEqualTo(clearingBefore + SWIFT_TOTAL);

        OutboundRemittanceView done = outbound.reconcile(r.reference());

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
        assertThat(entries(r.reference() + "-REV")).isZero();
        assertThat(outbound.reconcile(r.reference()).status()).isEqualTo("COMPLETED");   // idempotent
    }

    @Test
    void aPendingPaymentThatTheProviderLaterFailsIsRefundedExactlyOnce() {
        String account = createAccount(FUNDED);

        OutboundRemittanceView r = sendVia("swiftsend", account, PENDING_THEN_FAIL);
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);

        OutboundRemittanceView failed = outbound.reconcile(r.reference());
        outbound.reconcile(r.reference());      // a second look must not refund again
        outbound.reconcile(r.reference());

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(ngn(account)).isEqualTo(FUNDED);
        assertThat(entries(r.reference() + "-REV")).isEqualTo(2);
    }

    @Test
    void aTimeoutIsAnUnknownOutcomeSoTheMoneyStaysHeldUntilVerified() {
        String account = createAccount(FUNDED);

        OutboundRemittanceView r = sendVia("swiftsend", account, TIMEOUT_THEN_OK);

        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.failureReason()).contains("outcome unknown");
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
        assertThat(entries(r.reference() + "-REV")).isZero();

        assertThat(outbound.reconcile(r.reference()).status()).isEqualTo("COMPLETED");
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
    }

    @Test
    void aProviderThatHasNoRecordIsNotProofNothingWasSoNoRefund() {
        String account = createAccount(FUNDED);

        OutboundRemittanceView r = sendVia("swiftsend", account, TIMEOUT_UNKNOWN);
        OutboundRemittanceView again = outbound.reconcile(r.reference());

        assertThat(again.status()).isEqualTo("PENDING");
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
        assertThat(entries(r.reference() + "-REV")).isZero();
    }

    @Test
    void theAutomaticReconcilerSettlesPendingPayments() {
        String account = createAccount(FUNDED);
        OutboundRemittanceView r = sendVia("swiftsend", account, PENDING_THEN_OK);

        int settled = outbound.reconcilePending(0, 100);

        assertThat(settled).isGreaterThanOrEqualTo(1);
        assertThat(outbound.getByReference(r.reference()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void aRefundThatFailsKeepsThePaymentPendingAndIsRetriedLater() {
        String account = createAccount(FUNDED);
        OutboundRemittanceView r = sendVia("swiftsend", account, PENDING_THEN_FAIL);
        long clearing = ngn(CLEARING);
        jdbc.update("update accounts set balance_minor = 0 where account_number = ?", CLEARING);
        try {
            OutboundRemittanceView stuck = outbound.reconcile(r.reference());

            assertThat(stuck.status()).isEqualTo("PENDING");
            assertThat(stuck.failureReason()).contains("refund failed");
            assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
            assertThat(entries(r.reference() + "-REV")).isZero();
        } finally {
            jdbc.update("update accounts set balance_minor = ? where account_number = ?", clearing, CLEARING);
        }

        assertThat(outbound.reconcile(r.reference()).status()).isEqualTo("FAILED");
        assertThat(ngn(account)).isEqualTo(FUNDED);
        assertThat(entries(r.reference() + "-REV")).isEqualTo(2);
    }

    @Test
    void twoReconcilersRacingRefundExactlyOnce() throws Exception {
        String account = createAccount(FUNDED);
        OutboundRemittanceView r = sendVia("swiftsend", account, PENDING_THEN_FAIL);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<String> reconcile = () -> {
                go.await();
                return outbound.reconcile(r.reference()).status();
            };
            Future<String> a = pool.submit(reconcile);
            Future<String> b = pool.submit(reconcile);
            go.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(outbound.getByReference(r.reference()).status()).isEqualTo("FAILED");
        assertThat(ngn(account)).isEqualTo(FUNDED);
        assertThat(entries(r.reference() + "-REV")).isEqualTo(2);
    }

    // ---------- the hold itself can fail: provider is never called ----------

    @Test
    void notEnoughNairaFailsAtTheHoldAndNothingMoves() {
        String account = createAccount(100_000);
        long clearingBefore = ngn(CLEARING);

        OutboundRemittanceView r = sendVia("swiftsend", account, OK);

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.failureReason()).contains("Could not hold funds");
        assertThat(r.providerReference()).isNull();            // the provider was never called
        assertThat(ngn(account)).isEqualTo(100_000);
        assertThat(ngn(CLEARING)).isEqualTo(clearingBefore);
        assertThat(entries(r.reference() + "-HOLD")).isZero();
    }

    @Test
    void aFrozenAccountCannotSend() {
        String account = createAccount(FUNDED);
        ProviderQuoteView q = from(quotes(account), "swiftsend");
        jdbc.update("update accounts set status = 'FROZEN' where account_number = ?", account);

        OutboundRemittanceView r = send(q, OK);

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(ngn(account)).isEqualTo(FUNDED);
    }

    // ---------- idempotency, single use, expiry ----------

    @Test
    void replayingTheSameKeyReturnsTheSameRemittanceAndMovesMoneyOnce() {
        String account = createAccount(FUNDED);
        ProviderQuoteView q = from(quotes(account), "swiftsend");
        String key = "key-" + UUID.randomUUID();
        OutboundRemittanceRequest request = new OutboundRemittanceRequest(q.quoteId(), "Jane Roe", OK, "Chase");

        OutboundRemittanceView first = outbound.send(request, key);
        OutboundRemittanceView second = outbound.send(request, key);

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
        assertThat(entries(first.reference() + "-HOLD")).isEqualTo(2);
    }

    @Test
    void aKeyAlreadyUsedForAnotherQuoteIsRejected() {
        String account = createAccount(FUNDED);
        List<ProviderQuoteView> q = quotes(account);
        String key = "key-" + UUID.randomUUID();
        outbound.send(new OutboundRemittanceRequest(q.get(0).quoteId(), "Jane Roe", OK, null), key);

        assertThatThrownBy(() -> outbound.send(
                new OutboundRemittanceRequest(q.get(1).quoteId(), "Jane Roe", OK, null), key))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aQuoteCanBeSentOnlyOnce() {
        String account = createAccount(FUNDED);
        ProviderQuoteView q = from(quotes(account), "swiftsend");
        send(q, OK);

        assertThatThrownBy(() -> send(q, OK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been used");
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
    }

    @Test
    void anExpiredQuoteIsRejectedAndMovesNothing() {
        String account = createAccount(FUNDED);
        ProviderQuoteView q = from(quotes(account), "swiftsend");
        jdbc.update("update provider_quotes set expires_at = ? where quote_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), q.quoteId());
        long before = count("outbound_remittances");

        assertThatThrownBy(() -> send(q, OK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");

        assertThat(count("outbound_remittances")).isEqualTo(before);
        assertThat(ngn(account)).isEqualTo(FUNDED);
    }

    @Test
    void twoRequestsRacingForOneQuoteSendItExactlyOnce() throws Exception {
        String account = createAccount(FUNDED);
        ProviderQuoteView q = from(quotes(account), "swiftsend");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<String> attempt = () -> {
                go.await();
                try {
                    return send(q, OK).status();
                } catch (IllegalStateException e) {
                    return "REJECTED";
                }
            };
            Future<String> a = pool.submit(attempt);
            Future<String> b = pool.submit(attempt);
            go.countDown();
            List<String> results = List.of(a.get(), b.get());
            assertThat(results).containsExactlyInAnyOrder("COMPLETED", "REJECTED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(ngn(account)).isEqualTo(FUNDED - SWIFT_TOTAL);
    }

    // ---------- validation ----------

    @Test
    void invalidSendRequestsAreRejectedAndStoreNothing() {
        String account = createAccount(FUNDED);
        ProviderQuoteView q = from(quotes(account), "swiftsend");
        long before = count("outbound_remittances");
        String key = "k-" + UUID.randomUUID();

        assertThatThrownBy(() -> outbound.send(new OutboundRemittanceRequest(q.quoteId(), "J", OK, null), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.send(new OutboundRemittanceRequest(q.quoteId(), "J", OK, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.send(new OutboundRemittanceRequest(" ", "J", OK, null), key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.send(new OutboundRemittanceRequest(q.quoteId(), " ", OK, null), key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.send(new OutboundRemittanceRequest(q.quoteId(), "J", " ", null), key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.send(null, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.send(new OutboundRemittanceRequest("pq-nope", "J", OK, null), key))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> outbound.getByReference("out-nope")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> outbound.reconcile("out-nope")).isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("outbound_remittances")).isEqualTo(before);
        assertThat(ngn(account)).isEqualTo(FUNDED);
    }
}
