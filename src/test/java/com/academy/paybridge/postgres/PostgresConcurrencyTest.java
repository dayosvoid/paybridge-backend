package com.academy.paybridge.postgres;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.fx.api.ConversionApi;
import com.academy.paybridge.fx.api.ConversionQuoteView;
import com.academy.paybridge.fx.api.ConversionView;
import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.api.InboundPaymentView;
import com.academy.paybridge.remittance.api.InboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same races the H2 tests cover, but on a real PostgreSQL: real row locks (SELECT ... FOR UPDATE),
 * real unique-constraint races and real deadlock detection. Every race releases all its threads at the
 * same instant. After each one the money must still add up and the ledger must agree with the balances.
 *
 * The application is started like production: Flyway migrations, Hibernate in validate mode.
 */
@PostgresSpringTest
class PostgresConcurrencyTest {

    private static final String NGN_POOL = "0000000006";
    private static final String USD_POOL = "0000000007";
    private static final String CLEARING = "0000000008";

    private static final long AMOUNT = 10_000_000;           // NGN 100,000.00
    private static final long SWIFT_FEE = 250_000;
    private static final long FUNDED = 20_000_000;
    private static final String OK = "1234567890";
    private static final String PENDING_THEN_FAIL = "1234567897";

    @Autowired TransferApi transfers;
    @Autowired ConversionApi conversions;
    @Autowired OutboundRemittanceApi outbound;
    @Autowired InboundRemittanceApi inbound;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;
    @Value("${spring.jpa.hibernate.ddl-auto}") String ddlAuto;

    // ---------- helpers ----------

    private record Outcome<T>(T value, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    /** Runs every job on its own thread, all released at the same instant. Never throws for a job's failure. */
    private <T> List<Outcome<T>> race(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch ready = new CountDownLatch(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        try {
            for (Callable<T> job : jobs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<>(job.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<>(null, t);
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                outcomes.add(f.get(120, TimeUnit.SECONDS));     // a deadlock shows up here as a timeout
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> List<Callable<T>> times(int n, Callable<T> job) {
        List<Callable<T>> jobs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            jobs.add(job);
        }
        return jobs;
    }

    private String createAccount(long ngnMinor, long usdMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customer = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        for (String currency : new String[] {"NGN", "USD"}) {
            jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                            + "status, bank_code, bank_name, account_name) "
                            + "values (?, ?, ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                    number, currency, currency.equals("NGN") ? ngnMinor : usdMinor, customer);
        }
        return number;
    }

    private long balance(String number, Currency currency) {
        return accounts.findByNumber(number, currency).orElseThrow().balanceMinor();
    }

    /** What the ledger says this balance has moved by since the account was created. */
    private long ledgerNet(String number, Currency currency) {
        return jdbc.queryForObject("select coalesce(sum(case when type = 'CREDIT' then amount_minor "
                + "else -amount_minor end), 0) from ledger_entries where account_number = ? and currency = ?",
                Long.class, number, currency.name());
    }

    private void assertLedgerAgrees(String number, Currency currency, long opening) {
        assertThat(balance(number, currency))
                .as("%s %s: balance must equal opening balance plus the ledger's movements", number, currency)
                .isEqualTo(opening + ledgerNet(number, currency));
    }

    private long entries(String reference) {
        return jdbc.queryForObject("select count(*) from ledger_entries where reference = ?", Long.class, reference);
    }

    private static boolean completed(Outcome<ConversionView> o) {
        return o.ok() && "COMPLETED".equals(o.value().status());
    }

    private static boolean completedSend(Outcome<OutboundRemittanceView> o) {
        return o.ok() && "COMPLETED".equals(o.value().status());
    }

    private ProviderQuoteView swiftQuote(String account, long amountMinor) {
        return outbound.getQuotes(account, amountMinor, USD).stream()
                .filter(q -> q.providerId().equals("swiftsend")).findFirst().orElseThrow();
    }

    private OutboundRemittanceView send(ProviderQuoteView quote, String beneficiary, String key) {
        return outbound.send(new OutboundRemittanceRequest(quote.quoteId(), "Jane Roe", beneficiary, "Chase"), key);
    }

    private TransferView transfer(String from, String to, long amountMinor) {
        return transfers.initiate(new TransferRequest(from, to, amountMinor, NGN), UUID.randomUUID().toString());
    }

    // ---------- the test really runs the production setup ----------

    @Test
    void theApplicationRunsOnFlywayMigrationsWithHibernateOnlyValidating() {
        assertThat(ddlAuto).isEqualTo("validate");
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success",
                Integer.class)).isEqualTo(22);
    }

    // ---------- transfers ----------

    @Test
    void twentyTransfersFromOneAccountNeverOverdraw() throws Exception {
        String source = createAccount(100_000, 0);
        String dest = createAccount(0, 0);

        List<Outcome<TransferView>> results = race(times(20, () -> transfer(source, dest, 10_000)));

        assertThat(results).allMatch(Outcome::ok);
        assertThat(results.stream().filter(r -> r.value().status().equals("SUCCESS")).count()).isEqualTo(10);
        assertThat(balance(source, NGN)).isZero();
        assertThat(balance(dest, NGN)).isEqualTo(100_000);
        assertLedgerAgrees(source, NGN, 100_000);
        assertLedgerAgrees(dest, NGN, 0);
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        String a = createAccount(100_000, 0);
        String b = createAccount(100_000, 0);
        List<Callable<TransferView>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> transfer(a, b, 1_000));
            jobs.add(() -> transfer(b, a, 1_000));
        }

        List<Outcome<TransferView>> results = race(jobs);

        assertThat(results).allMatch(r -> r.ok() && r.value().status().equals("SUCCESS"));
        assertThat(balance(a, NGN) + balance(b, NGN)).isEqualTo(200_000);
        assertLedgerAgrees(a, NGN, 100_000);
        assertLedgerAgrees(b, NGN, 100_000);
    }

    @Test
    void manyAccountsPayingEachOtherInACircleKeepTheLedgerConsistent() throws Exception {
        List<String> ring = List.of(createAccount(1_000_000, 0), createAccount(1_000_000, 0),
                createAccount(1_000_000, 0), createAccount(1_000_000, 0));
        List<Callable<TransferView>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String from = ring.get(i % 4);
            String to = ring.get((i % 2 == 0) ? (i + 1) % 4 : (i + 2) % 4);
            jobs.add(() -> transfer(from, to, 1_000));
        }

        List<Outcome<TransferView>> results = race(jobs);

        assertThat(results).allMatch(r -> r.ok() && r.value().status().equals("SUCCESS"));
        assertThat(ring.stream().mapToLong(n -> balance(n, NGN)).sum()).isEqualTo(4_000_000);
        ring.forEach(n -> assertLedgerAgrees(n, NGN, 1_000_000));
    }

    // ---------- conversions ----------

    @Test
    void customersConvertingBothWaysAtOnceMoveTheSharedPoolsExactly() throws Exception {
        List<String> ngnSide = new ArrayList<>();
        List<String> usdSide = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ngnSide.add(createAccount(10_000_000, 0));
            usdSide.add(createAccount(0, 10_000));
        }
        List<ConversionQuoteView> quotes = new ArrayList<>();
        ngnSide.forEach(a -> quotes.add(conversions.createQuote(a, NGN, USD, 5_000_000)));
        usdSide.forEach(a -> quotes.add(conversions.createQuote(a, USD, NGN, 10_000)));
        long ngnPoolBefore = balance(NGN_POOL, NGN);
        long usdPoolBefore = balance(USD_POOL, USD);

        List<Callable<ConversionView>> jobs = new ArrayList<>();
        quotes.forEach(q -> jobs.add(() -> conversions.convert(q.quoteId(), UUID.randomUUID().toString())));
        List<Outcome<ConversionView>> results = race(jobs);

        assertThat(results).allMatch(r -> completed(r));
        long ngnIntoPool = 0, usdOutOfPool = 0, usdIntoPool = 0, ngnOutOfPool = 0;
        for (Outcome<ConversionView> r : results) {
            ConversionView v = r.value();
            if (v.sourceCurrency() == NGN) {
                ngnIntoPool += v.sourceAmountMinor();
                usdOutOfPool += v.destinationAmountMinor();
                assertThat(balance(v.accountNumber(), NGN)).isEqualTo(10_000_000 - v.sourceAmountMinor());
                assertThat(balance(v.accountNumber(), USD)).isEqualTo(v.destinationAmountMinor());
                assertLedgerAgrees(v.accountNumber(), NGN, 10_000_000);
                assertLedgerAgrees(v.accountNumber(), USD, 0);
            } else {
                usdIntoPool += v.sourceAmountMinor();
                ngnOutOfPool += v.destinationAmountMinor();
                assertThat(balance(v.accountNumber(), USD)).isZero();
                assertThat(balance(v.accountNumber(), NGN)).isEqualTo(v.destinationAmountMinor());
                assertLedgerAgrees(v.accountNumber(), USD, 10_000);
                assertLedgerAgrees(v.accountNumber(), NGN, 0);
            }
        }
        assertThat(balance(NGN_POOL, NGN)).isEqualTo(ngnPoolBefore + ngnIntoPool - ngnOutOfPool);
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore + usdIntoPool - usdOutOfPool);
    }

    @Test
    void eightRequestsOnOneQuoteConvertExactlyOnce() throws Exception {
        String account = createAccount(10_000_000, 0);
        ConversionQuoteView q = conversions.createQuote(account, NGN, USD, 5_000_000);

        List<Outcome<ConversionView>> results = race(
                times(8, () -> conversions.convert(q.quoteId(), UUID.randomUUID().toString())));

        assertThat(results.stream().filter(PostgresConcurrencyTest::completed).count()).isEqualTo(1);
        assertThat(balance(account, NGN)).isEqualTo(5_000_000);
        assertThat(balance(account, USD)).isEqualTo(q.destinationAmountMinor());
        assertLedgerAgrees(account, NGN, 10_000_000);
        assertLedgerAgrees(account, USD, 0);
    }

    @Test
    void eightRequestsWithOneIdempotencyKeyConvertOnceAndShareTheResult() throws Exception {
        String account = createAccount(10_000_000, 0);
        ConversionQuoteView q = conversions.createQuote(account, NGN, USD, 5_000_000);
        String key = "key-" + UUID.randomUUID();

        List<Outcome<ConversionView>> results = race(times(8, () -> conversions.convert(q.quoteId(), key)));

        assertThat(results).allMatch(Outcome::ok);
        assertThat(results.stream().map(r -> r.value().reference()).distinct().count()).isEqualTo(1);
        assertThat(balance(account, NGN)).isEqualTo(5_000_000);
        assertThat(balance(account, USD)).isEqualTo(q.destinationAmountMinor());
        assertLedgerAgrees(account, NGN, 10_000_000);
    }

    // ---------- sending ----------

    @Test
    void sixRequestsOnOneQuoteSendExactlyOnce() throws Exception {
        String account = createAccount(FUNDED, 0);
        ProviderQuoteView q = swiftQuote(account, AMOUNT);

        List<Outcome<OutboundRemittanceView>> results = race(
                times(6, () -> send(q, OK, UUID.randomUUID().toString())));

        assertThat(results.stream().filter(PostgresConcurrencyTest::completedSend).count()).isEqualTo(1);
        assertThat(balance(account, NGN)).isEqualTo(FUNDED - AMOUNT - SWIFT_FEE);
        assertLedgerAgrees(account, NGN, FUNDED);
        String reference = results.stream().filter(PostgresConcurrencyTest::completedSend)
                .findFirst().orElseThrow().value().reference();
        assertThat(entries(reference + "-HOLD")).isEqualTo(2);
    }

    @Test
    void sixRequestsWithOneIdempotencyKeyShareOneRemittanceAndHoldOnce() throws Exception {
        String account = createAccount(FUNDED, 0);
        ProviderQuoteView q = swiftQuote(account, AMOUNT);
        String key = "key-" + UUID.randomUUID();

        List<Outcome<OutboundRemittanceView>> results = race(times(6, () -> send(q, OK, key)));

        assertThat(results).allMatch(Outcome::ok);
        assertThat(results.stream().map(r -> r.value().reference()).distinct().count()).isEqualTo(1);
        assertThat(balance(account, NGN)).isEqualTo(FUNDED - AMOUNT - SWIFT_FEE);
        assertLedgerAgrees(account, NGN, FUNDED);
        assertThat(entries(results.get(0).value().reference() + "-HOLD")).isEqualTo(2);
    }

    /**
     * Daily limit (default NGN 10,000,000 = 1,000,000,000 minor) with four sends of 400,000,000 minor at once.
     * The limit can never be exceeded, so at most two can be sent. A send may also be turned away even though
     * it would have fitted, because a send that is still being checked counts towards the total: the check
     * errs on the safe side. At least one always gets through.
     */
    @Test
    void simultaneousSendsNeverExceedTheDailyLimit() throws Exception {
        String account = createAccount(2_000_000_000L, 0);
        long each = 400_000_000L;
        List<ProviderQuoteView> quotes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            quotes.add(swiftQuote(account, each));
        }
        List<Callable<OutboundRemittanceView>> jobs = new ArrayList<>();
        quotes.forEach(q -> jobs.add(() -> send(q, OK, UUID.randomUUID().toString())));

        List<Outcome<OutboundRemittanceView>> results = race(jobs);

        assertThat(results).allMatch(Outcome::ok);
        long sent = results.stream().filter(PostgresConcurrencyTest::completedSend).count();
        long rejected = results.stream().filter(r -> "FAILED".equals(r.value().status())).count();
        assertThat(sent).isBetween(1L, 2L);
        assertThat(sent + rejected).isEqualTo(4);
        assertThat(balance(account, NGN)).isEqualTo(2_000_000_000L - sent * (each + SWIFT_FEE));
        assertLedgerAgrees(account, NGN, 2_000_000_000L);
        results.stream().filter(r -> "FAILED".equals(r.value().status())).forEach(r -> {
            assertThat(r.value().failureReason()).contains("Daily limit");
            assertThat(entries(r.value().reference() + "-HOLD")).isZero();
        });
    }

    @Test
    void fourReconcilersRefundAFailedPayoutExactlyOnce() throws Exception {
        String account = createAccount(FUNDED, 0);
        OutboundRemittanceView r = send(swiftQuote(account, AMOUNT), PENDING_THEN_FAIL, UUID.randomUUID().toString());
        assertThat(r.status()).isEqualTo("PENDING");

        List<Outcome<OutboundRemittanceView>> results = race(times(4, () -> outbound.reconcile(r.reference())));

        assertThat(results).allMatch(Outcome::ok);
        assertThat(outbound.getByReference(r.reference()).status()).isEqualTo("FAILED");
        assertThat(balance(account, NGN)).isEqualTo(FUNDED);
        assertThat(entries(r.reference() + "-REV")).isEqualTo(2);
        assertLedgerAgrees(account, NGN, FUNDED);
    }

    @Test
    void fourReturnReportsRefundACompletedPayoutExactlyOnce() throws Exception {
        String account = createAccount(FUNDED, 0);
        OutboundRemittanceView r = send(swiftQuote(account, AMOUNT), OK, UUID.randomUUID().toString());
        assertThat(r.status()).isEqualTo("COMPLETED");

        List<Outcome<OutboundRemittanceView>> results = race(
                times(4, () -> outbound.markReturned(r.reference(), "returned by the beneficiary bank")));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(results.stream().filter(o -> !o.ok()).map(Outcome::error))
                .allMatch(e -> e instanceof IllegalStateException);
        assertThat(outbound.getByReference(r.reference()).status()).isEqualTo("RETURNED");
        assertThat(balance(account, NGN)).isEqualTo(FUNDED - SWIFT_FEE);     // amount back, fee kept
        assertThat(entries(r.reference() + "-REV")).isEqualTo(2);
        assertLedgerAgrees(account, NGN, FUNDED);
    }

    // ---------- receiving ----------

    @Test
    void eightDeliveriesOfOneInboundPaymentCreditOnce() throws Exception {
        String account = createAccount(0, 0);
        long usdPoolBefore = balance(USD_POOL, USD);
        InboundPaymentCommand cmd = new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, 12_345, USD, "Sender");

        List<Outcome<InboundPaymentView>> results = race(times(8, () -> inbound.receive(cmd)));

        assertThat(results).allMatch(Outcome::ok);
        String reference = results.get(0).value().reference();
        assertThat(results.stream().map(r -> r.value().reference()).distinct().count()).isEqualTo(1);
        assertThat(inbound.getByReference(reference).status()).isEqualTo("CREDITED");
        assertThat(balance(account, USD)).isEqualTo(12_345);
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore - 12_345);
        assertThat(entries(reference + "-IN")).isEqualTo(2);
        assertLedgerAgrees(account, USD, 0);
    }

    @Test
    void fourChargebackReportsTakeTheMoneyBackExactlyOnce() throws Exception {
        String account = createAccount(0, 0);
        long usdPoolBefore = balance(USD_POOL, USD);
        InboundPaymentView paid = inbound.receive(
                new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, 10_000, USD, "Sender"));
        assertThat(paid.status()).isEqualTo("CREDITED");

        List<Outcome<InboundPaymentView>> results = race(
                times(4, () -> inbound.reverse(paid.providerReference(), "chargeback")));

        assertThat(results).allMatch(Outcome::ok);
        assertThat(inbound.getByReference(paid.reference()).status()).isEqualTo("REVERSED");
        assertThat(balance(account, USD)).isZero();
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore);          // paid out, then taken back
        assertThat(entries(paid.reference() + "-REV")).isEqualTo(2);
        assertLedgerAgrees(account, USD, 0);
    }

    /**
     * The customer converts the very USD the provider is charging back, at the same moment. Whoever gets
     * the balance row first wins, but the money can be spent only once: either the chargeback succeeds and the
     * conversion fails, or the conversion succeeds and the chargeback is left as REVERSAL_FAILED.
     */
    @Test
    void aChargebackRacingAConversionOfTheSameMoneyNeverSpendsItTwice() throws Exception {
        long usdPoolBefore = balance(USD_POOL, USD);
        int reversed = 0;
        int converted = 0;

        for (int round = 0; round < 10; round++) {
            String account = createAccount(0, 0);
            InboundPaymentView paid = inbound.receive(
                    new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, 10_000, USD, "Sender"));
            assertThat(paid.status()).isEqualTo("CREDITED");
            ConversionQuoteView quote = conversions.createQuote(account, USD, NGN, 10_000);

            List<Callable<Object>> jobs = List.of(
                    () -> inbound.reverse(paid.providerReference(), "chargeback"),
                    () -> conversions.convert(quote.quoteId(), UUID.randomUUID().toString()));
            List<Outcome<Object>> results = race(jobs);

            assertThat(results.get(0).ok()).as("the chargeback report is always answered").isTrue();
            boolean conversionWon = results.get(1).ok()
                    && "COMPLETED".equals(((ConversionView) results.get(1).value()).status());
            String paymentStatus = inbound.getByReference(paid.reference()).status();

            assertThat(balance(account, USD)).as("USD is fully spent or taken back, never negative").isZero();
            if (conversionWon) {
                assertThat(paymentStatus).isEqualTo("REVERSAL_FAILED");
                assertThat(balance(account, NGN)).isEqualTo(quote.destinationAmountMinor());
                converted++;
            } else {
                assertThat(paymentStatus).isEqualTo("REVERSED");
                assertThat(balance(account, NGN)).isZero();
                reversed++;
            }
            assertLedgerAgrees(account, USD, 0);
            assertLedgerAgrees(account, NGN, 0);
        }

        // Every round ends with the 10,000 USD back in the USD pool, one way or the other.
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore);
        assertThat(reversed + converted).isEqualTo(10);
    }
}
