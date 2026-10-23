package com.academy.paybridge.auth;

import com.academy.paybridge.support.ApiAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.UUID;

import static com.academy.paybridge.support.ApiAuth.PASSWORD;
import static com.academy.paybridge.support.ApiAuth.field;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A customer can only look at and spend from their own account. Two customers (A and B), a stranger (C)
 * and an admin, over real HTTP. Own in-memory database.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paybridge-ownership;LOCK_TIMEOUT=10000",
                "paybridge.auth.bootstrap-admin.email=boss@example.com",
                "paybridge.auth.bootstrap-admin.password=a-very-long-admin-password"
        })
class OwnershipTest {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private String accountA, accountB, accountC;
    private String tokenA, tokenB, tokenC, admin;

    @BeforeEach
    void people() throws Exception {
        accountA = register("a");
        accountB = register("b");
        accountC = register("c");
        tokenA = tokenFor("a");
        tokenB = tokenFor("b");
        tokenC = tokenFor("c");
        admin = ApiAuth.login(http, port, "boss@example.com", "a-very-long-admin-password");
        fund(accountA, "NGN", 50_000_000);
        fund(accountA, "USD", 100_000);
        fund(accountB, "NGN", 1_000_000);
    }

    // ---------- helpers ----------

    private String emailOf(String who) {
        return who + "-" + suffix + "@example.com";
    }

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private String register(String who) throws Exception {
        HttpResponse<String> r = ApiAuth.post(http, port, "/auth/register",
                ApiAuth.registerBody(emailOf(who), PASSWORD, "Person " + who), null);
        assertThat(r.statusCode()).isEqualTo(201);
        return field(r.body(), "accountNumber");
    }

    private String tokenFor(String who) throws Exception {
        return ApiAuth.login(http, port, emailOf(who), PASSWORD);
    }

    private void fund(String account, String currency, long minor) {
        jdbc.update("update accounts set balance_minor = ? where account_number = ? and currency = ?",
                minor, account, currency);
    }

    private long balance(String account, String currency) {
        return jdbc.queryForObject("select balance_minor from accounts where account_number = ? and currency = ?",
                Long.class, account, currency);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return ApiAuth.get(http, port, path, token);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return ApiAuth.request(http, port, "POST", path, body, token, UUID.randomUUID().toString());
    }

    private static String transferJson(String from, String to, long amount) {
        return String.format("{\"sourceAccount\":\"%s\",\"destinationAccount\":\"%s\",\"amountMinor\":%d,"
                + "\"currency\":\"NGN\"}", from, to, amount);
    }

    private static String conversionQuoteJson(String account) {
        return String.format("{\"accountNumber\":\"%s\",\"sourceCurrency\":\"NGN\",\"destinationCurrency\":\"USD\","
                + "\"sourceAmountMinor\":5000000}", account);
    }

    private static String outboundQuotesJson(String account) {
        return String.format("{\"sourceAccount\":\"%s\",\"sourceAmountMinor\":10000000,\"destinationCurrency\":\"USD\"}",
                account);
    }

    private static String sendJson(String quoteId) {
        return String.format("{\"quoteId\":\"%s\",\"beneficiaryName\":\"Jane Roe\",\"beneficiaryAccount\":\"1234567890\","
                + "\"beneficiaryBank\":\"Chase\"}", quoteId);
    }

    // ---------- reading accounts ----------

    @Test
    void aCustomerSeesOnlyTheirOwnAccount() throws Exception {
        assertThat(get("/accounts/" + accountA, tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/accounts/" + accountA + "/balances", tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/accounts/" + accountA + "/transactions", tokenA).statusCode()).isEqualTo(200);

        assertThat(get("/accounts/" + accountA, tokenB).statusCode()).isEqualTo(403);
        assertThat(get("/accounts/" + accountA + "/balances", tokenB).statusCode()).isEqualTo(403);
        assertThat(get("/accounts/" + accountA + "/transactions", tokenB).statusCode()).isEqualTo(403);
    }

    @Test
    void anAccountThatDoesNotExistLooksTheSameAsSomeoneElsesAccount() throws Exception {
        HttpResponse<String> someoneElses = get("/accounts/" + accountA, tokenB);
        HttpResponse<String> missing = get("/accounts/9999999999", tokenB);
        HttpResponse<String> system = get("/accounts/0000000000", tokenB);

        assertThat(someoneElses.statusCode()).isEqualTo(403);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(system.statusCode()).isEqualTo(403);
        assertThat(missing.body()).isEqualTo(someoneElses.body());
    }

    @Test
    void anAdminCanLookAtAnyAccount() throws Exception {
        assertThat(get("/accounts/" + accountA, admin).statusCode()).isEqualTo(200);
        assertThat(get("/accounts/" + accountA + "/balances", admin).statusCode()).isEqualTo(200);
        assertThat(get("/accounts/" + accountA + "/transactions", admin).statusCode()).isEqualTo(200);
        assertThat(get("/accounts/0000000000", admin).statusCode()).isEqualTo(200);
    }

    // ---------- transfers ----------

    @Test
    void aCustomerCannotMoveMoneyOutOfSomeoneElsesAccount() throws Exception {
        long before = balance(accountA, "NGN");

        HttpResponse<String> theft = post("/transfers", transferJson(accountA, accountB, 1_000_000), tokenB);

        assertThat(theft.statusCode()).isEqualTo(403);
        assertThat(balance(accountA, "NGN")).isEqualTo(before);
        assertThat(balance(accountB, "NGN")).isEqualTo(1_000_000);
    }

    @Test
    void anAdminCannotSpendFromACustomersAccountEither() throws Exception {
        long before = balance(accountA, "NGN");

        assertThat(post("/transfers", transferJson(accountA, accountB, 1_000_000), admin).statusCode()).isEqualTo(403);

        assertThat(balance(accountA, "NGN")).isEqualTo(before);
    }

    @Test
    void anOwnerCanTransferAndBothSidesCanSeeTheTransferButAStrangerCannot() throws Exception {
        HttpResponse<String> made = post("/transfers", transferJson(accountA, accountB, 1_000_000), tokenA);
        assertThat(made.statusCode()).isEqualTo(201);
        String reference = field(made.body(), "reference");
        assertThat(balance(accountB, "NGN")).isEqualTo(2_000_000);

        assertThat(get("/transfers/" + reference, tokenA).statusCode()).isEqualTo(200);   // sender
        assertThat(get("/transfers/" + reference, tokenB).statusCode()).isEqualTo(200);   // receiver
        assertThat(get("/transfers/" + reference, admin).statusCode()).isEqualTo(200);
        assertThat(get("/transfers/" + reference, tokenC).statusCode()).isEqualTo(403);   // stranger
    }

    @Test
    void theTransferListIsPerAccount() throws Exception {
        assertThat(get("/transfers?account=" + accountA, tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/transfers?account=" + accountA, tokenB).statusCode()).isEqualTo(403);
        assertThat(get("/transfers?account=" + accountA, admin).statusCode()).isEqualTo(200);
    }

    // ---------- conversions ----------

    @Test
    void conversionQuotesAndConversionsBelongToTheAccountOwner() throws Exception {
        assertThat(post("/fx/conversions/quotes", conversionQuoteJson(accountA), tokenB).statusCode()).isEqualTo(403);
        assertThat(post("/fx/conversions/quotes", conversionQuoteJson(accountA), admin).statusCode()).isEqualTo(403);

        HttpResponse<String> quote = post("/fx/conversions/quotes", conversionQuoteJson(accountA), tokenA);
        assertThat(quote.statusCode()).isEqualTo(201);
        String quoteId = field(quote.body(), "quoteId");

        // B holds A's quote id but cannot use it
        assertThat(post("/fx/conversions", "{\"quoteId\":\"" + quoteId + "\"}", tokenB).statusCode()).isEqualTo(403);
        assertThat(post("/fx/conversions", "{\"quoteId\":\"" + quoteId + "\"}", admin).statusCode()).isEqualTo(403);
        assertThat(balance(accountA, "NGN")).isEqualTo(50_000_000);

        HttpResponse<String> done = post("/fx/conversions", "{\"quoteId\":\"" + quoteId + "\"}", tokenA);
        assertThat(done.statusCode()).isEqualTo(201);
        assertThat(balance(accountA, "NGN")).isEqualTo(45_000_000);
        String reference = field(done.body(), "reference");

        assertThat(get("/fx/conversions/" + reference, tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/fx/conversions/" + reference, tokenB).statusCode()).isEqualTo(403);
        assertThat(get("/fx/conversions/" + reference, admin).statusCode()).isEqualTo(200);
    }

    @Test
    void anUnknownConversionQuoteIsNotFound() throws Exception {
        assertThat(post("/fx/conversions", "{\"quoteId\":\"cq-nope\"}", tokenA).statusCode()).isEqualTo(404);
    }

    // ---------- sending abroad ----------

    @Test
    void outboundQuotesAndSendsBelongToTheAccountOwner() throws Exception {
        assertThat(post("/outbound-remittances/quotes", outboundQuotesJson(accountA), tokenB).statusCode())
                .isEqualTo(403);
        assertThat(post("/outbound-remittances/quotes", outboundQuotesJson(accountA), admin).statusCode())
                .isEqualTo(403);

        HttpResponse<String> quotes = post("/outbound-remittances/quotes", outboundQuotesJson(accountA), tokenA);
        assertThat(quotes.statusCode()).isEqualTo(201);
        String quoteId = field(quotes.body(), "quoteId");

        // B holds A's quote id but cannot send with it
        assertThat(post("/outbound-remittances", sendJson(quoteId), tokenB).statusCode()).isEqualTo(403);
        assertThat(post("/outbound-remittances", sendJson(quoteId), admin).statusCode()).isEqualTo(403);
        assertThat(balance(accountA, "NGN")).isEqualTo(50_000_000);

        HttpResponse<String> sent = post("/outbound-remittances", sendJson(quoteId), tokenA);
        assertThat(sent.statusCode()).isEqualTo(201);
        assertThat(balance(accountA, "NGN")).isLessThan(50_000_000);
        String reference = field(sent.body(), "reference");

        assertThat(get("/outbound-remittances/" + reference, tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/outbound-remittances/" + reference, tokenB).statusCode()).isEqualTo(403);
        assertThat(get("/outbound-remittances/" + reference, admin).statusCode()).isEqualTo(200);
        assertThat(post("/outbound-remittances/" + reference + "/reconcile", "{}", tokenA).statusCode())
                .as("manual reconcile is an operator action").isEqualTo(403);
    }

    @Test
    void anUnknownOutboundQuoteIsNotFound() throws Exception {
        assertThat(post("/outbound-remittances", sendJson("pq-nope"), tokenA).statusCode()).isEqualTo(404);
    }

    // ---------- the older demo remittance ----------

    @Test
    void theDemoRemittanceAlsoNeedsTheSourceAccountOwner() throws Exception {
        String body = String.format("{\"sourceAccount\":\"%s\",\"beneficiaryAccount\":\"%s\","
                + "\"sourceAmountMinor\":10000000,\"sourceCurrency\":\"NGN\",\"destinationCurrency\":\"USD\"}",
                accountA, accountB);

        assertThat(post("/remittances", body, tokenB).statusCode()).isEqualTo(403);
        assertThat(balance(accountA, "NGN")).isEqualTo(50_000_000);

        HttpResponse<String> mine = post("/remittances", body, tokenA);
        assertThat(mine.statusCode()).isEqualTo(201);
        String reference = field(mine.body(), "reference");
        assertThat(get("/remittances/" + reference, tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/remittances/" + reference, tokenC).statusCode()).isEqualTo(403);
    }
}
