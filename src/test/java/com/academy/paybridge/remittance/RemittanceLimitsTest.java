package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.shared.exception.LimitExceededException;
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
 * Limits for sending money abroad, with small limits so they are easy to hit:
 * per transaction NGN 200,000.00 (20,000,000 minor), per day NGN 300,000.00 (30,000,000 minor).
 * Every send below is NGN 100,000.00 (10,000,000 minor), so three fit in a day and a fourth does not.
 * The fee is not counted towards the limits.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paybridge-limits;LOCK_TIMEOUT=10000",
        "paybridge.remittance.limits.per-transaction-minor=20000000",
        "paybridge.remittance.limits.daily-minor=30000000"})
class RemittanceLimitsTest {

    private static final long AMOUNT = 10_000_000;
    private static final long FUNDED = 100_000_000;
    private static final String OK = "1234567890";
    private static final String REJECTED = "1234567899";

    @Autowired OutboundRemittanceApi outbound;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

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

    private OutboundRemittanceView send(String account, long amount, String beneficiary) {
        ProviderQuoteView q = outbound.getQuotes(account, amount, USD).stream()
                .filter(x -> x.providerId().equals("swiftsend")).findFirst().orElseThrow();
        return outbound.send(new OutboundRemittanceRequest(q.quoteId(), "Jane Roe", beneficiary, null),
                UUID.randomUUID().toString());
    }

    @Test
    void anAmountAboveThePerTransactionLimitIsRejectedAtQuoteTimeAndStoresNothing() {
        String account = createAccount(FUNDED);
        long before = jdbc.queryForObject("select count(*) from provider_quotes", Long.class);

        assertThatThrownBy(() -> outbound.getQuotes(account, 20_000_001, USD))
                .isInstanceOf(LimitExceededException.class).hasMessageContaining("per-transaction limit");

        assertThat(jdbc.queryForObject("select count(*) from provider_quotes", Long.class)).isEqualTo(before);
        // exactly the limit is allowed
        assertThat(send(account, 20_000_000, OK).status()).isEqualTo("COMPLETED");
    }

    @Test
    void theDailyLimitStopsTheSendThatWouldCrossIt() {
        String account = createAccount(FUNDED);

        for (int i = 0; i < 3; i++) {
            assertThat(send(account, AMOUNT, OK).status()).isEqualTo("COMPLETED");
        }
        long afterThree = ngn(account);
        OutboundRemittanceView fourth = send(account, AMOUNT, OK);

        assertThat(fourth.status()).isEqualTo("FAILED");
        assertThat(fourth.failureReason()).contains("Daily limit");
        assertThat(fourth.providerReference()).isNull();            // the provider was never called
        assertThat(ngn(account)).isEqualTo(afterThree);             // nothing was held
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where reference = ?",
                Long.class, fourth.reference() + "-HOLD")).isZero();
    }

    @Test
    void limitsAreCountedPerAccount() {
        String first = createAccount(FUNDED);
        String second = createAccount(FUNDED);
        for (int i = 0; i < 3; i++) {
            send(first, AMOUNT, OK);
        }

        assertThat(send(second, AMOUNT, OK).status()).isEqualTo("COMPLETED");
    }

    @Test
    void paymentsThatFailedOrCameBackDoNotUseUpTheLimit() {
        String account = createAccount(FUNDED);
        for (int i = 0; i < 3; i++) {
            assertThat(send(account, AMOUNT, REJECTED).status()).isEqualTo("FAILED");   // refunded, not counted
        }

        for (int i = 0; i < 3; i++) {
            assertThat(send(account, AMOUNT, OK).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    void paymentsOlderThanADayDoNotCount() {
        String account = createAccount(FUNDED);
        for (int i = 0; i < 3; i++) {
            send(account, AMOUNT, OK);
        }
        jdbc.update("update outbound_remittances set created_at = ? where source_account = ?",
                Timestamp.from(Instant.now().minusSeconds(25 * 3600)), account);

        assertThat(send(account, AMOUNT, OK).status()).isEqualTo("COMPLETED");
    }

    @Test
    void twoSimultaneousSendsCannotBothSlipUnderTheLimit() throws Exception {
        String account = createAccount(FUNDED);
        send(account, AMOUNT, OK);
        send(account, AMOUNT, OK);                                  // 20M of the 30M used; room for one more
        List<ProviderQuoteView> q1 = outbound.getQuotes(account, AMOUNT, USD);
        List<ProviderQuoteView> q2 = outbound.getQuotes(account, AMOUNT, USD);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<String> a = pool.submit(attempt(q1, go));
            Future<String> b = pool.submit(attempt(q2, go));
            go.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("COMPLETED", "FAILED");
        } finally {
            pool.shutdownNow();
        }
        Long sent = jdbc.queryForObject("select sum(source_amount_minor) from outbound_remittances "
                + "where source_account = ? and status <> 'FAILED'", Long.class, account);
        assertThat(sent).isEqualTo(30_000_000L);
    }

    private Callable<String> attempt(List<ProviderQuoteView> quotes, CountDownLatch go) {
        ProviderQuoteView q = quotes.stream().filter(x -> x.providerId().equals("swiftsend")).findFirst().orElseThrow();
        return () -> {
            go.await();
            return outbound.send(new OutboundRemittanceRequest(q.quoteId(), "Jane Roe", OK, null),
                    UUID.randomUUID().toString()).status();
        };
    }
}
