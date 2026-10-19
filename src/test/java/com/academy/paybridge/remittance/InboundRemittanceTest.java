package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.fx.api.ConversionApi;
import com.academy.paybridge.fx.api.ConversionView;
import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.api.InboundPaymentView;
import com.academy.paybridge.remittance.api.InboundRemittanceApi;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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

/** Receiving USD. Own in-memory database so the FX pool balances are not disturbed by other classes. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-inbound;LOCK_TIMEOUT=10000")
class InboundRemittanceTest {

    private static final String USD_POOL = "0000000007";

    @Autowired InboundRemittanceApi inbound;
    @Autowired ConversionApi conversions;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String createAccount() {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customer = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        insert(number, "NGN", customer);
        insert(number, "USD", customer);
        return number;
    }

    private void insert(String number, String currency, long customer) {
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) "
                        + "values (?, ?, 0, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                number, currency, customer);
    }

    private long balance(String number, Currency currency) {
        return accounts.findByNumber(number, currency).orElseThrow().balanceMinor();
    }

    private static InboundPaymentCommand payment(String account, long amount) {
        return new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, amount, USD, "John Smith");
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    // ---------- crediting ----------

    @Test
    void incomingUsdCreditsTheCustomersOwnUsdBalance() {
        String account = createAccount();
        long poolBefore = balance(USD_POOL, USD);

        InboundPaymentView v = inbound.receive(payment(account, 25_000));

        assertThat(v.status()).isEqualTo("CREDITED");
        assertThat(v.reference()).startsWith("rin-");
        assertThat(v.creditedAt()).isNotNull();
        assertThat(balance(account, USD)).isEqualTo(25_000);
        assertThat(balance(account, NGN)).isZero();                 // the naira balance is untouched
        assertThat(balance(USD_POOL, USD)).isEqualTo(poolBefore - 25_000);
    }

    @Test
    void theCreditIsPostedToTheLedgerAsAnInboundEntry() {
        String account = createAccount();

        InboundPaymentView v = inbound.receive(payment(account, 25_000));

        List<String> rows = jdbc.queryForList("select type || ':' || currency || ':' || account_number "
                + "from ledger_entries where reference = ?", String.class, v.reference() + "-IN");
        assertThat(rows).containsExactlyInAnyOrder("DEBIT:USD:" + USD_POOL, "CREDIT:USD:" + account);
    }

    @Test
    void aFrozenAccountCanStillReceiveMoney() {
        String account = createAccount();
        jdbc.update("update accounts set status = 'FROZEN' where account_number = ?", account);

        assertThat(inbound.receive(payment(account, 1_000)).status()).isEqualTo("CREDITED");
        assertThat(balance(account, USD)).isEqualTo(1_000);
    }

    // ---------- redelivery ----------

    @Test
    void deliveringTheSamePaymentTwiceCreditsOnce() {
        String account = createAccount();
        InboundPaymentCommand p = payment(account, 25_000);

        InboundPaymentView first = inbound.receive(p);
        InboundPaymentView second = inbound.receive(p);

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(balance(account, USD)).isEqualTo(25_000);
        assertThat(jdbc.queryForObject("select count(*) from inbound_payments where provider_reference = ?",
                Long.class, p.providerReference())).isEqualTo(1);
    }

    @Test
    void aProviderReferenceReusedForADifferentPaymentIsRejected() {
        String account = createAccount();
        InboundPaymentCommand p = payment(account, 25_000);
        inbound.receive(p);

        InboundPaymentCommand tampered = new InboundPaymentCommand(
                p.providerReference(), account, 99_000, USD, "John Smith");

        assertThatThrownBy(() -> inbound.receive(tampered)).isInstanceOf(IllegalStateException.class);
        assertThat(balance(account, USD)).isEqualTo(25_000);
    }

    @Test
    void twoSimultaneousDeliveriesOfOnePaymentCreditOnce() throws Exception {
        String account = createAccount();
        InboundPaymentCommand p = payment(account, 25_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<String> delivery = () -> {
                go.await();
                return inbound.receive(p).reference();
            };
            Future<String> a = pool.submit(delivery);
            Future<String> b = pool.submit(delivery);
            go.countDown();
            assertThat(a.get()).isEqualTo(b.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(account, USD)).isEqualTo(25_000);
    }

    // ---------- payments that cannot be credited are recorded, not lost ----------

    @Test
    void aPaymentForAnUnknownAccountIsRecordedAsUnmatched() {
        long poolBefore = balance(USD_POOL, USD);

        InboundPaymentView v = inbound.receive(payment("9999999990", 25_000));

        assertThat(v.status()).isEqualTo("UNMATCHED");
        assertThat(v.failureReason()).contains("Account not found");
        assertThat(balance(USD_POOL, USD)).isEqualTo(poolBefore);
        assertThat(inbound.list("UNMATCHED")).extracting(InboundPaymentView::reference).contains(v.reference());
    }

    @Test
    void anAccountNumberWithoutAUsdBalanceIsUnmatched() {
        String ngnOnly = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        insert(ngnOnly, "NGN", ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));

        InboundPaymentView v = inbound.receive(payment(ngnOnly, 25_000));

        assertThat(v.status()).isEqualTo("UNMATCHED");
        assertThat(v.failureReason()).contains("no USD balance");
    }

    @Test
    void onlyUsdPaymentsAreAccepted() {
        String account = createAccount();

        InboundPaymentView v = inbound.receive(
                new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, 25_000, NGN, "John"));

        assertThat(v.status()).isEqualTo("UNMATCHED");
        assertThat(v.failureReason()).contains("Only USD");
        assertThat(balance(account, NGN)).isZero();
    }

    @Test
    void aClosedAccountCannotBeCreditedSoThePaymentIsRecordedAsFailed() {
        String account = createAccount();
        jdbc.update("update accounts set status = 'CLOSED' where account_number = ?", account);

        InboundPaymentView v = inbound.receive(payment(account, 25_000));

        assertThat(v.status()).isEqualTo("FAILED");
        assertThat(balance(account, USD)).isZero();
        assertThat(inbound.list("FAILED")).extracting(InboundPaymentView::reference).contains(v.reference());
    }

    // ---------- validation and lookups ----------

    @Test
    void invalidPaymentsAreRejectedAndStoreNothing() {
        String account = createAccount();
        long before = count("inbound_payments");

        assertThatThrownBy(() -> inbound.receive(new InboundPaymentCommand(" ", account, 100, USD, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbound.receive(new InboundPaymentCommand("p-1", " ", 100, USD, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbound.receive(new InboundPaymentCommand("p-1", account, 0, USD, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbound.receive(new InboundPaymentCommand("p-1", account, -5, USD, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbound.receive(new InboundPaymentCommand("p-1", account, 100, null, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbound.receive(null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(count("inbound_payments")).isEqualTo(before);
    }

    @Test
    void listFiltersByStatusAndRejectsAnUnknownStatus() {
        String account = createAccount();
        InboundPaymentView credited = inbound.receive(payment(account, 1_000));
        InboundPaymentView unmatched = inbound.receive(payment("9999999991", 1_000));

        assertThat(inbound.list("credited")).extracting(InboundPaymentView::reference)
                .contains(credited.reference()).doesNotContain(unmatched.reference());
        assertThat(inbound.list(null)).extracting(InboundPaymentView::reference)
                .contains(credited.reference(), unmatched.reference());
        assertThatThrownBy(() -> inbound.list("SIDEWAYS")).isInstanceOf(IllegalArgumentException.class);
        assertThat(inbound.getByReference(credited.reference()).providerReference())
                .isEqualTo(credited.providerReference());
        assertThatThrownBy(() -> inbound.getByReference("rin-nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- the whole receiving story ----------

    @Test
    void receivedUsdCanBeConvertedToNairaWithTheConversionTool() {
        String account = createAccount();
        inbound.receive(payment(account, 10_000));      // USD 100.00 arrives

        ConversionView c = conversions.convert(
                conversions.createQuote(account, USD, NGN, 10_000).quoteId(), UUID.randomUUID().toString());

        assertThat(c.status()).isEqualTo("COMPLETED");
        assertThat(balance(account, USD)).isZero();
        assertThat(balance(account, NGN)).isEqualTo(15_000_000);   // NGN 150,000.00 at 1500
    }
}
