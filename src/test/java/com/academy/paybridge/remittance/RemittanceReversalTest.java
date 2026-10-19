package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.fx.api.ConversionApi;
import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.api.InboundPaymentView;
import com.academy.paybridge.remittance.api.InboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * Money that is taken back after the fact: a provider chargeback on an inbound payment, and an outbound
 * payout that comes back after it was COMPLETED. Own in-memory database.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-reversal;LOCK_TIMEOUT=10000")
class RemittanceReversalTest {

    private static final String USD_POOL = "0000000007";
    private static final String CLEARING = "0000000008";
    private static final long AMOUNT = 10_000_000;
    private static final long FEE = 250_000;                 // SwiftSend fee
    private static final long FUNDED = 20_000_000;
    private static final String OK = "1234567890";
    private static final String PENDING_THEN_OK = "1234567898";
    private static final String REJECTED = "1234567899";

    @Autowired InboundRemittanceApi inbound;
    @Autowired OutboundRemittanceApi outbound;
    @Autowired ConversionApi conversions;
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

    private long balance(String number, Currency currency) {
        return accounts.findByNumber(number, currency).orElseThrow().balanceMinor();
    }

    private InboundPaymentCommand payment(String account, long amount) {
        return new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, amount, USD, "John Smith");
    }

    private long entries(String reference) {
        return jdbc.queryForObject("select count(*) from ledger_entries where reference = ?", Long.class, reference);
    }

    private OutboundRemittanceView sendOut(String account, String beneficiary) {
        ProviderQuoteView q = outbound.getQuotes(account, AMOUNT, USD).stream()
                .filter(x -> x.providerId().equals("swiftsend")).findFirst().orElseThrow();
        return outbound.send(new OutboundRemittanceRequest(q.quoteId(), "Jane Roe", beneficiary, null),
                UUID.randomUUID().toString());
    }

    // ---------- inbound: the provider takes a payment back ----------

    @Test
    void aChargebackTakesTheMoneyBackFromTheCustomerOnce() {
        String account = createAccount(0);
        long poolBefore = balance(USD_POOL, USD);
        InboundPaymentCommand p = payment(account, 25_000);
        InboundPaymentView credited = inbound.receive(p);
        assertThat(balance(account, USD)).isEqualTo(25_000);

        InboundPaymentView reversed = inbound.reverse(p.providerReference(), "chargeback");
        InboundPaymentView again = inbound.reverse(p.providerReference(), "chargeback");

        assertThat(reversed.status()).isEqualTo("REVERSED");
        assertThat(reversed.failureReason()).contains("chargeback");
        assertThat(again.status()).isEqualTo("REVERSED");
        assertThat(balance(account, USD)).isZero();
        assertThat(balance(USD_POOL, USD)).isEqualTo(poolBefore);          // the pool got it back
        assertThat(entries(credited.reference() + "-REV")).isEqualTo(2);    // taken back exactly once
    }

    @Test
    void ifTheCustomerAlreadyConvertedTheMoneyTheReversalFailsAndIsRetriedLater() {
        String account = createAccount(0);
        InboundPaymentCommand a = payment(account, 10_000);
        inbound.receive(a);
        conversions.convert(conversions.createQuote(account, USD, NGN, 10_000).quoteId(), UUID.randomUUID().toString());
        assertThat(balance(account, USD)).isZero();

        InboundPaymentView stuck = inbound.reverse(a.providerReference(), "chargeback");

        assertThat(stuck.status()).isEqualTo("REVERSAL_FAILED");
        assertThat(stuck.failureReason()).contains("Could not take the money back");
        assertThat(balance(account, NGN)).isEqualTo(15_000_000);            // nothing was touched
        assertThat(inbound.list("REVERSAL_FAILED")).extracting(InboundPaymentView::reference)
                .contains(stuck.reference());

        // the customer receives USD again; the provider repeats its report and the reversal now succeeds
        inbound.receive(payment(account, 10_000));
        InboundPaymentView retried = inbound.reverse(a.providerReference(), "chargeback");

        assertThat(retried.status()).isEqualTo("REVERSED");
        assertThat(balance(account, USD)).isZero();
    }

    @Test
    void aFrozenAccountCannotBeDebitedSoItBecomesAReversalFailure() {
        String account = createAccount(0);
        InboundPaymentCommand p = payment(account, 25_000);
        inbound.receive(p);
        jdbc.update("update accounts set status = 'FROZEN' where account_number = ?", account);

        assertThat(inbound.reverse(p.providerReference(), "chargeback").status()).isEqualTo("REVERSAL_FAILED");
        assertThat(balance(account, USD)).isEqualTo(25_000);
    }

    @Test
    void aPaymentThatWasNeverCreditedIsJustCancelled() {
        long poolBefore = balance(USD_POOL, USD);
        InboundPaymentCommand p = payment("9999999980", 25_000);
        InboundPaymentView unmatched = inbound.receive(p);
        assertThat(unmatched.status()).isEqualTo("UNMATCHED");

        InboundPaymentView r = inbound.reverse(p.providerReference(), "cancelled");

        assertThat(r.status()).isEqualTo("REVERSED");
        assertThat(balance(USD_POOL, USD)).isEqualTo(poolBefore);
        assertThat(entries(unmatched.reference() + "-REV")).isZero();
    }

    @Test
    void reversingAnUnknownPaymentIsNotFoundAndABlankReferenceIsRejected() {
        assertThatThrownBy(() -> inbound.reverse("prov-unknown", "x")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inbound.reverse(" ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbound.reverse(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoReversalReportsAtOnceTakeTheMoneyBackOnce() throws Exception {
        String account = createAccount(0);
        InboundPaymentCommand p = payment(account, 25_000);
        InboundPaymentView credited = inbound.receive(p);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<String> report = () -> {
                go.await();
                return inbound.reverse(p.providerReference(), "chargeback").status();
            };
            Future<String> a = pool.submit(report);
            Future<String> b = pool.submit(report);
            go.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(inbound.getByReference(credited.reference()).status()).isEqualTo("REVERSED");
        assertThat(balance(account, USD)).isZero();
        assertThat(entries(credited.reference() + "-REV")).isEqualTo(2);
    }

    // ---------- outbound: a payout comes back after it completed ----------

    @Test
    void aReturnedPayoutRefundsTheAmountSentAndKeepsTheFee() {
        String account = createAccount(FUNDED);
        long clearingBefore = balance(CLEARING, NGN);
        OutboundRemittanceView sent = sendOut(account, OK);
        assertThat(sent.status()).isEqualTo("COMPLETED");
        assertThat(balance(account, NGN)).isEqualTo(FUNDED - AMOUNT - FEE);

        OutboundRemittanceView returned = outbound.markReturned(sent.reference(), "beneficiary bank closed");
        OutboundRemittanceView again = outbound.markReturned(sent.reference(), "beneficiary bank closed");

        assertThat(returned.status()).isEqualTo("RETURNED");
        assertThat(returned.failureReason()).contains("beneficiary bank closed");
        assertThat(again.status()).isEqualTo("RETURNED");
        assertThat(balance(account, NGN)).isEqualTo(FUNDED - FEE);                  // amount back, fee kept
        assertThat(balance(CLEARING, NGN)).isEqualTo(clearingBefore + FEE);
        assertThat(entries(sent.reference() + "-REV")).isEqualTo(2);                // refunded exactly once
    }

    @Test
    void onlyACompletedPayoutCanBeReturned() {
        String account = createAccount(FUNDED * 3);
        OutboundRemittanceView pending = sendOut(account, PENDING_THEN_OK);
        OutboundRemittanceView failed = sendOut(account, REJECTED);

        assertThatThrownBy(() -> outbound.markReturned(pending.reference(), "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PENDING");
        assertThatThrownBy(() -> outbound.markReturned(failed.reference(), "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already failed");
        assertThatThrownBy(() -> outbound.markReturned("out-unknown", "x"))
                .isInstanceOf(ResourceNotFoundException.class);

        // once the pending one completes it can be returned
        outbound.reconcile(pending.reference());
        assertThat(outbound.markReturned(pending.reference(), "x").status()).isEqualTo("RETURNED");
    }

    @Test
    void twoReturnReportsAtOnceRefundOnce() throws Exception {
        String account = createAccount(FUNDED);
        OutboundRemittanceView sent = sendOut(account, OK);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<String> report = () -> {
                go.await();
                return outbound.markReturned(sent.reference(), "bounced").status();
            };
            Future<String> a = pool.submit(report);
            Future<String> b = pool.submit(report);
            go.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(balance(account, NGN)).isEqualTo(FUNDED - FEE);
        assertThat(entries(sent.reference() + "-REV")).isEqualTo(2);
    }

    @Test
    void theOperatorCanListOutboundPaymentsByStatus() {
        String account = createAccount(FUNDED * 3);
        OutboundRemittanceView pending = sendOut(account, PENDING_THEN_OK);

        assertThat(outbound.list("pending")).extracting(OutboundRemittanceView::reference)
                .contains(pending.reference());
        assertThat(outbound.list(null)).extracting(OutboundRemittanceView::reference).contains(pending.reference());
        assertThatThrownBy(() -> outbound.list("SIDEWAYS")).isInstanceOf(IllegalArgumentException.class);
    }
}
