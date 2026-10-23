package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.service.WebhookSignature;
import com.academy.paybridge.support.ApiAuth;
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

import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

/** The real HTTP endpoint, with signatures. Own in-memory database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paybridge-inbound-webhook;LOCK_TIMEOUT=10000",
                "paybridge.auth.bootstrap-admin.email=ops@example.com",
                "paybridge.auth.bootstrap-admin.password=an-operator-password"
        })
class InboundWebhookTest {

    private static final String SECRET = "dev-webhook-secret-change-me";   // the built-in default

    @Value("${local.server.port}") int port;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private String createAccount() {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customer = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        for (String currency : new String[] {"NGN", "USD"}) {
            jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                            + "status, bank_code, bank_name, account_name) "
                            + "values (?, ?, 0, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                    number, currency, customer);
        }
        return number;
    }

    private long usdBalance(String number) {
        return accounts.findByNumber(number, USD).orElseThrow().balanceMinor();
    }

    private static String json(InboundPaymentCommand c) {
        return String.format("{\"providerReference\":\"%s\",\"accountNumber\":\"%s\",\"amountMinor\":%d,"
                        + "\"currency\":\"%s\",\"senderName\":\"John Smith\"}",
                c.providerReference(), c.accountNumber(), c.amountMinor(), c.currency());
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/remittance/inbound"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header("X-Signature", signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The operator lists need an ADMIN (the webhook itself needs only its signature). */
    private HttpResponse<String> get(String path) throws Exception {
        return ApiAuth.get(http, port, path, ApiAuth.login(http, port, "ops@example.com", "an-operator-password"));
    }

    private static InboundPaymentCommand payment(String account, long amount) {
        return new InboundPaymentCommand("prov-" + UUID.randomUUID(), account, amount, USD, "John Smith");
    }

    @Test
    void aSignedWebhookCreditsTheAccount() throws Exception {
        String account = createAccount();
        InboundPaymentCommand p = payment(account, 25_000);

        HttpResponse<String> r = post(json(p), WebhookSignature.sign(SECRET, p));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("CREDITED");
        assertThat(usdBalance(account)).isEqualTo(25_000);
    }

    @Test
    void aWrongSignatureIsRejectedAndNothingIsRecorded() throws Exception {
        String account = createAccount();
        InboundPaymentCommand p = payment(account, 25_000);

        HttpResponse<String> r = post(json(p), WebhookSignature.sign("some-other-secret", p));

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(usdBalance(account)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inbound_payments where provider_reference = ?",
                Long.class, p.providerReference())).isZero();
    }

    @Test
    void aMissingSignatureIsRejected() throws Exception {
        String account = createAccount();

        HttpResponse<String> r = post(json(payment(account, 25_000)), null);

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(usdBalance(account)).isZero();
    }

    @Test
    void aTamperedAmountInvalidatesTheSignature() throws Exception {
        String account = createAccount();
        InboundPaymentCommand signed = payment(account, 25_000);
        InboundPaymentCommand tampered = new InboundPaymentCommand(
                signed.providerReference(), account, 2_500_000, USD, "John Smith");

        HttpResponse<String> r = post(json(tampered), WebhookSignature.sign(SECRET, signed));

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(usdBalance(account)).isZero();
    }

    @Test
    void theProviderRedeliveringTheSameWebhookCreditsOnce() throws Exception {
        String account = createAccount();
        InboundPaymentCommand p = payment(account, 25_000);
        String signature = WebhookSignature.sign(SECRET, p);

        assertThat(post(json(p), signature).statusCode()).isEqualTo(200);
        assertThat(post(json(p), signature).statusCode()).isEqualTo(200);

        assertThat(usdBalance(account)).isEqualTo(25_000);
    }

    @Test
    void aPaymentForAnUnknownAccountIsAcceptedButListedAsUnmatched() throws Exception {
        InboundPaymentCommand p = payment("9999999992", 25_000);

        HttpResponse<String> r = post(json(p), WebhookSignature.sign(SECRET, p));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("UNMATCHED");
        assertThat(get("/inbound-payments?status=UNMATCHED").body()).contains(p.providerReference());
    }

    @Test
    void aMalformedBodyIsABadRequest() throws Exception {
        HttpResponse<String> r = post("{\"providerReference\":\"\",\"accountNumber\":\"x\"}", "abc");

        assertThat(r.statusCode()).isEqualTo(400);
    }
}
