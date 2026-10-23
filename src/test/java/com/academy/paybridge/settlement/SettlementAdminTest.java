package com.academy.paybridge.settlement;

import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.scheduler.SettlementScheduler;
import com.academy.paybridge.support.ApiAuth;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:paybridge-settlement-admin;LOCK_TIMEOUT=10000",
        "paybridge.settlement.enabled=false",
        "paybridge.settlement.eligibility-delay-seconds=3600",
        "paybridge.settlement.batch-size=2",
        "paybridge.auth.bootstrap-admin.email=settlement-admin@example.com",
        "paybridge.auth.bootstrap-admin.password=a-very-long-admin-password"
})
class SettlementAdminTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferApi transfers;
    @Autowired SettlementApi settlement;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String admin, customer, account;

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("update transfers set settlement_batch_reference = 'drained' where settlement_batch_reference is null");
        jdbc.update("delete from settlement_items");
        jdbc.update("delete from settlement_batches");
        admin = ApiAuth.login(http, port, "settlement-admin@example.com", "a-very-long-admin-password");
        customer = ApiAuth.customerToken(http, port);
        account = ApiAuth.field(get("/auth/me", customer).body(), "accountNumber");
        jdbc.update("update accounts set balance_minor = 10000000 where account_number = ? and currency = 'NGN'", account);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return ApiAuth.get(http, port, path, token);
    }

    private HttpResponse<String> run(String token) throws Exception {
        return ApiAuth.post(http, port, "/admin/settlements/run", "{}", token);
    }

    private String transfer(long amount, String destination, boolean eligible) {
        var t = transfers.initiate(new TransferRequest(account, destination, amount, NGN, "058"), UUID.randomUUID().toString());
        if (eligible) {
            jdbc.update("update transfers set succeeded_at = ? where reference = ?",
                    Timestamp.from(Instant.now().minusSeconds(7200)), t.reference());
        }
        return t.reference();
    }

    private long balance(String number) {
        return jdbc.queryForObject("select balance_minor from accounts where account_number = ? and currency = 'NGN'",
                Long.class, number);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void managementRoutesRequireAdmin() throws Exception {
        for (String token : new String[]{null, customer}) {
            int expected = token == null ? 401 : 403;
            assertThat(run(token).statusCode()).isEqualTo(expected);
            assertThat(get("/admin/settlements/batches", token).statusCode()).isEqualTo(expected);
            assertThat(get("/admin/settlements/batches/unknown", token).statusCode()).isEqualTo(expected);
        }
        assertThat(count("settlement_batches")).isZero();
    }

    @Test
    void emptyRunAndEmptyHistoryAreExplicit() throws Exception {
        var response = run(admin);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        var page = json.readTree(get("/admin/settlements/batches", admin).body());
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("totalItems").asLong()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(count("settlement_batches")).isZero();
    }

    @Test
    void eligibleBatchMovesOnlyPrincipalAndCannotBeRepeated() throws Exception {
        String transfer = transfer(100000, "0123456780", true);
        long holding = balance("0000000000"), clearing = balance("0000000005");
        long fees = balance("0000000003"), tax = balance("0000000004"), customerBalance = balance(account);
        var response = run(admin);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var batch = json.readTree(response.body());
        assertThat(batch.path("status").asText()).isEqualTo("POSTED");
        assertThat(batch.path("currency").asText()).isEqualTo("NGN");
        assertThat(batch.path("provider").asText()).isEqualTo("PAYSTACK");
        assertThat(batch.path("totalMinor").asLong()).isEqualTo(100000);
        assertThat(batch.path("itemCount").asInt()).isEqualTo(1);
        assertThat(balance("0000000000")).isEqualTo(holding - 100000);
        assertThat(balance("0000000005")).isEqualTo(clearing + 100000);
        assertThat(balance("0000000003")).isEqualTo(fees);
        assertThat(balance("0000000004")).isEqualTo(tax);
        assertThat(balance(account)).isEqualTo(customerBalance);
        String reference = batch.path("reference").asText();
        assertThat(jdbc.queryForObject("select settlement_batch_reference from transfers where reference = ?",
                String.class, transfer)).isEqualTo(reference);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where reference = ?", Long.class, reference)).isEqualTo(2);
        assertThat(run(admin).statusCode()).isEqualTo(204);
        assertThat(count("settlement_batches")).isEqualTo(1);
    }

    @Test
    void eligibilityCannotBeOverriddenThroughRequestParameters() throws Exception {
        transfer(10000, "0123456788", true); // PENDING even with an old timestamp
        transfer(10000, "0123456789", true); // FAILED
        String fresh = transfer(10000, "0123456780", false);
        var internal = transfers.initiate(new TransferRequest(account, "0000000001", 10000, NGN), UUID.randomUUID().toString());
        jdbc.update("update transfers set succeeded_at = ? where reference = ?",
                Timestamp.from(Instant.now().minusSeconds(7200)), internal.reference());
        var response = ApiAuth.post(http, port, "/admin/settlements/run?eligibilityDelay=0&batchSize=500&currency=USD",
                "{\"eligibilityDelay\":0}", admin);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(count("settlement_items")).isZero();
        jdbc.update("update transfers set succeeded_at = ? where reference = ?",
                Timestamp.from(Instant.now().minusSeconds(7200)), fresh);
        assertThat(run(admin).statusCode()).isEqualTo(200);
        assertThat(count("settlement_items")).isEqualTo(1);
    }

    @Test
    void historyPaginationAndDetailsExposeOnlyStoredBatchAndItemFields() throws Exception {
        String firstTransfer = transfer(10000, "0123456780", true);
        transfer(20000, "0123456780", true);
        transfer(30000, "0123456780", true);
        var first = json.readTree(run(admin).body());
        var second = json.readTree(run(admin).body());
        assertThat(first.path("itemCount").asInt()).isEqualTo(2); // configured batch size
        assertThat(second.path("itemCount").asInt()).isEqualTo(1);
        jdbc.update("update settlement_batches set created_at = ?, status = 'RECONCILED'",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        var response = get("/admin/settlements/batches?page=0&size=1", admin);
        assertThat(response.statusCode()).isEqualTo(200);
        var page = json.readTree(response.body());
        assertThat(page.path("totalItems").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("items").get(0).path("reference").asText()).isEqualTo(second.path("reference").asText());
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("RECONCILED");
        var last = json.readTree(get("/admin/settlements/batches?page=1&size=1", admin).body());
        assertThat(last.path("items").get(0).path("reference").asText()).isEqualTo(first.path("reference").asText());
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(json.readTree(get("/admin/settlements/batches?page=2&size=1", admin).body()).path("items").size()).isZero();
        var detailResponse = get("/admin/settlements/batches/" + first.path("reference").asText(), admin);
        assertThat(detailResponse.statusCode()).isEqualTo(200);
        var detail = json.readTree(detailResponse.body());
        assertThat(detail.path("batch").path("totalMinor").asLong()).isEqualTo(30000);
        assertThat(detail.path("items").size()).isEqualTo(2);
        assertThat(detail.path("items").get(0).path("transferReference").asText()).isEqualTo(firstTransfer);
        assertThat(detail.path("items").get(0).path("amountMinor").asLong()).isEqualTo(10000);
        assertThat(detail.path("items").get(0).path("currency").asText()).isEqualTo("NGN");
        assertThat(detailResponse.body()).doesNotContain(account, "customerId", "email", "beneficiaryAccount");
    }

    @Test
    void badPaginationAndUnknownReferencesAreRejected() throws Exception {
        for (String query : new String[]{"page=-1", "size=0", "size=101", "page=invalid"}) {
            assertThat(get("/admin/settlements/batches?" + query, admin).statusCode()).as(query).isEqualTo(400);
        }
        assertThat(get("/admin/settlements/batches?size=100", admin).statusCode()).isEqualTo(200);
        assertThat(get("/admin/settlements/batches/unknown", admin).statusCode()).isEqualTo(404);
    }

    @Test
    void postingFailureReturnsAnErrorAndRollsBackTheBatch() throws Exception {
        String transfer = transfer(10000, "0123456780", true);
        long holding = balance("0000000000"), clearing = balance("0000000005");
        jdbc.update("update accounts set status = 'CLOSED' where account_number = '0000000005'");
        try {
            assertThat(run(admin).statusCode()).isEqualTo(422);
        } finally {
            jdbc.update("update accounts set status = 'ACTIVE' where account_number = '0000000005'");
        }
        assertThat(count("settlement_batches")).isZero();
        assertThat(count("settlement_items")).isZero();
        assertThat(balance("0000000000")).isEqualTo(holding);
        assertThat(balance("0000000005")).isEqualTo(clearing);
        assertThat(jdbc.queryForObject("select settlement_batch_reference from transfers where reference = ?",
                String.class, transfer)).isNull();
        assertThat(run(admin).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentManualAndSchedulerRunsSettleEachTransferOnce() throws Exception {
        transfer(10000, "0123456780", true);
        transfer(20000, "0123456780", true);
        long before = balance("0000000005");
        var scheduler = new SettlementScheduler(settlement, 3600, 2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var manual = pool.submit(() -> {
                ready.countDown(); start.await(); return run(admin).statusCode();
            });
            var scheduled = pool.submit(() -> {
                ready.countDown(); start.await(); scheduler.run(); return true;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(manual.get(30, TimeUnit.SECONDS)).isIn(200, 204);
            assertThat(scheduled.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown();
        }
        assertThat(count("settlement_batches")).isEqualTo(1);
        assertThat(count("settlement_items")).isEqualTo(2);
        assertThat(balance("0000000005")).isEqualTo(before + 30000);
        assertThat(run(admin).statusCode()).isEqualTo(204);
    }

    @Test
    void swaggerDocumentsBearerSecurityResponsesAndEligibility() throws Exception {
        var response = get("/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var docs = json.readTree(response.body());
        assertThat(docs.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asText())
                .isEqualTo("bearer");
        var operation = docs.path("paths").path("/admin/settlements/run").path("post");
        assertThat(operation.path("tags").get(0).asText()).isEqualTo("Settlement Administration");
        assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
        for (String status : new String[]{"200", "204", "401", "403", "422", "500"}) {
            assertThat(operation.path("responses").has(status)).as(status).isTrue();
        }
    }
}
