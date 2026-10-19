package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.api.InboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.remittance.service.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

/** The signed reversal and return webhooks, over real HTTP. Own in-memory database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:paybridge-reversal-webhook;LOCK_TIMEOUT=10000")
class ReversalWebhookTest {

    private static final String SECRET = "dev-webhook-secret-change-me";

    @Value("${local.server.port}") int port;
    @Autowired InboundRemittanceApi inbound;
    @Autowired OutboundRemittanceApi outbound;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

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

    private HttpResponse<String> post(String path, String body, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header("X-Signature", signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aSignedReversalWebhookTakesTheMoneyBack() throws Exception {
        String account = createAccount(0);
        String providerRef = "prov-" + UUID.randomUUID();
        inbound.receive(new InboundPaymentCommand(providerRef, account, 25_000, USD, "John"));
        String body = "{\"providerReference\":\"" + providerRef + "\",\"reason\":\"chargeback\"}";

        HttpResponse<String> r = post("/webhooks/remittance/inbound/reversal", body,
                WebhookSignature.signText(SECRET, WebhookSignature.reversalCanonical(providerRef)));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("REVERSED");
        assertThat(accounts.findByNumber(account, USD).orElseThrow().balanceMinor()).isZero();
    }

    @Test
    void aReversalWithABadOrMissingSignatureIsRejectedAndNothingMoves() throws Exception {
        String account = createAccount(0);
        String providerRef = "prov-" + UUID.randomUUID();
        inbound.receive(new InboundPaymentCommand(providerRef, account, 25_000, USD, "John"));
        String body = "{\"providerReference\":\"" + providerRef + "\",\"reason\":\"chargeback\"}";

        assertThat(post("/webhooks/remittance/inbound/reversal", body,
                WebhookSignature.signText("other-secret", WebhookSignature.reversalCanonical(providerRef)))
                .statusCode()).isEqualTo(401);
        assertThat(post("/webhooks/remittance/inbound/reversal", body, null).statusCode()).isEqualTo(401);
        // a signature for the payment webhook is not valid for a reversal
        assertThat(post("/webhooks/remittance/inbound/reversal", body,
                WebhookSignature.signText(SECRET, providerRef)).statusCode()).isEqualTo(401);

        assertThat(accounts.findByNumber(account, USD).orElseThrow().balanceMinor()).isEqualTo(25_000);
    }

    @Test
    void aReversalForAnUnknownPaymentIsNotFound() throws Exception {
        String providerRef = "prov-" + UUID.randomUUID();
        String body = "{\"providerReference\":\"" + providerRef + "\",\"reason\":\"x\"}";

        HttpResponse<String> r = post("/webhooks/remittance/inbound/reversal", body,
                WebhookSignature.signText(SECRET, WebhookSignature.reversalCanonical(providerRef)));

        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void aSignedReturnWebhookRefundsTheCustomer() throws Exception {
        String account = createAccount(20_000_000);
        ProviderQuoteView q = outbound.getQuotes(account, 10_000_000, USD).stream()
                .filter(x -> x.providerId().equals("swiftsend")).findFirst().orElseThrow();
        OutboundRemittanceView sent = outbound.send(
                new OutboundRemittanceRequest(q.quoteId(), "Jane Roe", "1234567890", null), UUID.randomUUID().toString());
        String body = "{\"reference\":\"" + sent.reference() + "\",\"reason\":\"bounced\"}";

        HttpResponse<String> r = post("/webhooks/remittance/outbound/returned", body,
                WebhookSignature.signText(SECRET, WebhookSignature.returnedCanonical(sent.reference())));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("RETURNED");
        assertThat(accounts.findByNumber(account, NGN).orElseThrow().balanceMinor())
                .isEqualTo(20_000_000 - 250_000);       // the amount came back, the fee did not
    }

    @Test
    void aReturnWithABadSignatureIsRejected() throws Exception {
        String body = "{\"reference\":\"out-anything\",\"reason\":\"x\"}";

        assertThat(post("/webhooks/remittance/outbound/returned", body, null).statusCode()).isEqualTo(401);
        assertThat(post("/webhooks/remittance/outbound/returned", body,
                WebhookSignature.signText("other-secret", WebhookSignature.returnedCanonical("out-anything")))
                .statusCode()).isEqualTo(401);
    }
}
