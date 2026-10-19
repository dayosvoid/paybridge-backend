package com.academy.paybridge.fx;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.fx.api.ConversionApi;
import com.academy.paybridge.fx.api.ConversionQuoteView;
import com.academy.paybridge.fx.api.ConversionView;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * Own in-memory database so other test classes cannot disturb the FX pool balances.
 * Rates: NGN to USD 0.00065, USD to NGN 1500.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-conversion;LOCK_TIMEOUT=10000")
class ConversionServiceTest {

    private static final String NGN_POOL = "0000000006";
    private static final String USD_POOL = "0000000007";

    @Autowired ConversionApi conversions;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    /** One account number holding an NGN balance and a USD balance. */
    private String createAccount(long ngnMinor, long usdMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customer = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        insert(number, "NGN", ngnMinor, customer);
        insert(number, "USD", usdMinor, customer);
        return number;
    }

    private void insert(String number, String currency, long balanceMinor, long customer) {
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) "
                        + "values (?, ?, ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                number, currency, balanceMinor, customer);
    }

    private long balance(String number, Currency currency) {
        return accounts.findByNumber(number, currency).orElseThrow().balanceMinor();
    }

    private ConversionQuoteView quote(String account, Currency from, Currency to, long amount) {
        return conversions.createQuote(account, from, to, amount);
    }

    private ConversionView convert(ConversionQuoteView q) {
        return conversions.convert(q.quoteId(), UUID.randomUUID().toString());
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    // ---------- quotes ----------

    @Test
    void aQuoteIsStoredWithAnExpiryAndMovesNoMoney() {
        String account = createAccount(0, 10_000);
        long before = count("conversion_quotes");

        ConversionQuoteView q = quote(account, USD, NGN, 10_000);

        assertThat(q.quoteId()).startsWith("cq-");
        assertThat(q.accountNumber()).isEqualTo(account);
        assertThat(q.sourceAmountMinor()).isEqualTo(10_000);
        assertThat(q.destinationAmountMinor()).isEqualTo(15_000_000);
        assertThat(q.rate()).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(q.rateType()).isEqualTo("DEMO_CONFIGURED");
        assertThat(q.expiresAt()).isAfter(Instant.now());
        assertThat(count("conversion_quotes")).isEqualTo(before + 1);
        assertThat(balance(account, USD)).isEqualTo(10_000);
        assertThat(balance(account, NGN)).isZero();
    }

    @Test
    void invalidQuoteRequestsAreRejectedAndStoreNothing() {
        String account = createAccount(1_000_000, 10_000);
        String ngnOnly = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        insert(ngnOnly, "NGN", 0, ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));
        long before = count("conversion_quotes");

        assertThatThrownBy(() -> quote(account, NGN, NGN, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote(account, USD, USD, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote(account, USD, NGN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote(account, USD, NGN, -5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote(account, NGN, USD, 1_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too small");
        assertThatThrownBy(() -> quote(" ", USD, NGN, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote("9999999999", USD, NGN, 10_000)).isInstanceOf(ResourceNotFoundException.class);
        // an account number without a USD balance cannot convert
        assertThatThrownBy(() -> quote(ngnOnly, NGN, USD, 10_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no USD balance");
        // the pools are single-currency system accounts, not convertible customer accounts
        assertThatThrownBy(() -> quote(USD_POOL, USD, NGN, 10_000)).isInstanceOf(IllegalArgumentException.class);

        assertThat(count("conversion_quotes")).isEqualTo(before);
    }

    // ---------- converting ----------

    @Test
    void usdToNgnMovesBothCurrenciesThroughThePools() {
        String account = createAccount(0, 10_000);
        long ngnPoolBefore = balance(NGN_POOL, NGN);
        long usdPoolBefore = balance(USD_POOL, USD);

        ConversionView c = convert(quote(account, USD, NGN, 10_000));

        assertThat(c.status()).isEqualTo("COMPLETED");
        assertThat(c.reference()).startsWith("cnv-");
        assertThat(c.sourceAmountMinor()).isEqualTo(10_000);
        assertThat(c.destinationAmountMinor()).isEqualTo(15_000_000);
        // customer: USD down, NGN up, same account number
        assertThat(balance(account, USD)).isZero();
        assertThat(balance(account, NGN)).isEqualTo(15_000_000);
        // pools: the USD pool received the USD, the NGN pool paid the NGN
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore + 10_000);
        assertThat(balance(NGN_POOL, NGN)).isEqualTo(ngnPoolBefore - 15_000_000);
    }

    @Test
    void ngnToUsdWorksThroughTheSameTool() {
        String account = createAccount(10_000_000, 0);
        long ngnPoolBefore = balance(NGN_POOL, NGN);
        long usdPoolBefore = balance(USD_POOL, USD);

        ConversionView c = convert(quote(account, NGN, USD, 10_000_000));

        assertThat(c.status()).isEqualTo("COMPLETED");
        assertThat(c.destinationAmountMinor()).isEqualTo(6_500);
        assertThat(balance(account, NGN)).isZero();
        assertThat(balance(account, USD)).isEqualTo(6_500);
        assertThat(balance(NGN_POOL, NGN)).isEqualTo(ngnPoolBefore + 10_000_000);
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore - 6_500);
    }

    @Test
    void eachCurrencyIsPostedAsItsOwnBalancedPairOfEntries() {
        String account = createAccount(0, 10_000);

        ConversionView c = convert(quote(account, USD, NGN, 10_000));

        List<String> rows = jdbc.queryForList(
                "select currency || ':' || type from ledger_entries where reference like ? order by id",
                String.class, c.reference() + "-%");
        assertThat(rows).containsExactlyInAnyOrder("USD:DEBIT", "USD:CREDIT", "NGN:DEBIT", "NGN:CREDIT");
        // each currency nets to zero across all accounts
        for (String currency : List.of("USD", "NGN")) {
            Long net = jdbc.queryForObject("select coalesce(sum(case when type = 'CREDIT' then amount_minor "
                            + "else -amount_minor end), 0) from ledger_entries where reference like ? and currency = ?",
                    Long.class, c.reference() + "-%", currency);
            assertThat(net).isZero();
        }
    }

    @Test
    void theConversionRecordKeepsEverythingNeededToExplainIt() {
        String account = createAccount(0, 10_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);

        ConversionView c = conversions.convert(q.quoteId(), "key-" + UUID.randomUUID());

        assertThat(conversions.getByReference(c.reference()))
                .extracting(ConversionView::quoteId, ConversionView::accountNumber, ConversionView::status,
                        ConversionView::rateType)
                .containsExactly(q.quoteId(), account, "COMPLETED", "DEMO_CONFIGURED");
        assertThat(jdbc.queryForObject("select completed_at from conversions where reference = ?",
                Object.class, c.reference())).isNotNull();
    }

    // ---------- idempotency, single use, expiry ----------

    @Test
    void replayingTheSameKeyReturnsTheSameConversionAndMovesMoneyOnce() {
        String account = createAccount(0, 20_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);
        String key = "key-" + UUID.randomUUID();

        ConversionView first = conversions.convert(q.quoteId(), key);
        ConversionView second = conversions.convert(q.quoteId(), key);

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(balance(account, USD)).isEqualTo(10_000);
        assertThat(balance(account, NGN)).isEqualTo(15_000_000);
        assertThat(jdbc.queryForObject("select count(*) from conversions where quote_id = ?",
                Long.class, q.quoteId())).isEqualTo(1);
    }

    @Test
    void aKeyAlreadyUsedForAnotherQuoteIsRejected() {
        String account = createAccount(0, 20_000);
        String key = "key-" + UUID.randomUUID();
        conversions.convert(quote(account, USD, NGN, 10_000).quoteId(), key);
        ConversionQuoteView other = quote(account, USD, NGN, 5_000);

        assertThatThrownBy(() -> conversions.convert(other.quoteId(), key))
                .isInstanceOf(IllegalStateException.class);
        assertThat(balance(account, USD)).isEqualTo(10_000);
    }

    @Test
    void aQuoteCanBeUsedOnlyOnce() {
        String account = createAccount(0, 20_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);
        convert(q);

        assertThatThrownBy(() -> convert(q))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been used");
        assertThat(balance(account, USD)).isEqualTo(10_000);
        assertThat(balance(account, NGN)).isEqualTo(15_000_000);
    }

    @Test
    void anExpiredQuoteIsRejectedAndMovesNothing() {
        String account = createAccount(0, 10_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);
        jdbc.update("update conversion_quotes set expires_at = ? where quote_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), q.quoteId());
        long before = count("conversions");

        assertThatThrownBy(() -> convert(q))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");

        assertThat(count("conversions")).isEqualTo(before);
        assertThat(balance(account, USD)).isEqualTo(10_000);
        assertThat(balance(account, NGN)).isZero();
    }

    @Test
    void twoRequestsRacingForOneQuoteConvertItExactlyOnce() throws Exception {
        String account = createAccount(0, 20_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<String>> tasks = List.of(race(q, go), race(q, go));
            List<Future<String>> futures = tasks.stream().map(pool::submit).toList();
            go.countDown();
            long completed = 0;
            for (Future<String> f : futures) {
                if (f.get().equals("COMPLETED")) completed++;
            }
            assertThat(completed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(account, USD)).isEqualTo(10_000);
        assertThat(balance(account, NGN)).isEqualTo(15_000_000);
    }

    private Callable<String> race(ConversionQuoteView q, CountDownLatch go) {
        return () -> {
            go.await();
            try {
                return conversions.convert(q.quoteId(), UUID.randomUUID().toString()).status();
            } catch (IllegalStateException e) {
                return "REJECTED";
            }
        };
    }

    @Test
    void anIdempotencyKeyAndAKnownQuoteAreRequired() {
        assertThatThrownBy(() -> conversions.convert("cq-x", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversions.convert("cq-x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversions.convert(" ", "k-" + UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversions.convert("cq-does-not-exist", "k-" + UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> conversions.getByReference("cnv-does-not-exist"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- failures: nothing moves ----------

    @Test
    void notEnoughMoneyIsRecordedAsFailedAndNothingMoves() {
        String account = createAccount(0, 1_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);   // quoting does not check funds
        long usdPoolBefore = balance(USD_POOL, USD);
        long ngnPoolBefore = balance(NGN_POOL, NGN);

        ConversionView c = convert(q);

        assertThat(c.status()).isEqualTo("FAILED");
        assertThat(c.failureReason()).containsIgnoringCase("insufficient");
        assertThat(balance(account, USD)).isEqualTo(1_000);
        assertThat(balance(account, NGN)).isZero();
        assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore);
        assertThat(balance(NGN_POOL, NGN)).isEqualTo(ngnPoolBefore);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where reference like ?",
                Long.class, c.reference() + "-%")).isZero();
        // the quote is spent: a new one is needed
        assertThatThrownBy(() -> convert(q)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFrozenAccountCannotConvertItsMoney() {
        String account = createAccount(0, 10_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);
        jdbc.update("update accounts set status = 'FROZEN' where account_number = ?", account);

        ConversionView c = convert(q);

        assertThat(c.status()).isEqualTo("FAILED");
        assertThat(balance(account, USD)).isEqualTo(10_000);
        assertThat(balance(account, NGN)).isZero();
    }

    @Test
    void aPoolWithoutLiquidityFailsTheWholeConversionAndTheCustomerKeepsTheirUsd() {
        String account = createAccount(0, 10_000);
        ConversionQuoteView q = quote(account, USD, NGN, 10_000);
        long ngnPoolBefore = balance(NGN_POOL, NGN);
        long usdPoolBefore = balance(USD_POOL, USD);
        jdbc.update("update accounts set balance_minor = 0 where account_number = ?", NGN_POOL);
        try {
            ConversionView c = convert(q);

            assertThat(c.status()).isEqualTo("FAILED");
            // atomic: the USD leg was undone too
            assertThat(balance(account, USD)).isEqualTo(10_000);
            assertThat(balance(account, NGN)).isZero();
            assertThat(balance(USD_POOL, USD)).isEqualTo(usdPoolBefore);
        } finally {
            jdbc.update("update accounts set balance_minor = ? where account_number = ?", ngnPoolBefore, NGN_POOL);
        }
    }
}
