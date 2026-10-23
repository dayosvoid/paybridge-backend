package com.academy.paybridge.remittance;

import com.academy.paybridge.remittance.domain.OutboundRemittance;
import com.academy.paybridge.remittance.domain.ProviderQuote;
import com.academy.paybridge.remittance.repository.OutboundRemittanceRepository;
import com.academy.paybridge.support.ApiAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paybridge-outbound-history;LOCK_TIMEOUT=10000",
                "paybridge.auth.bootstrap-admin.email=history-admin@example.com",
                "paybridge.auth.bootstrap-admin.password=a-very-long-admin-password"
        })
class OutboundHistoryTest {
    @Value("${local.server.port}") int port;
    @Autowired OutboundRemittanceRepository remittances;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String accountA, accountB, tokenA, tokenB;

    @BeforeEach
    void customers() throws Exception {
        tokenA = ApiAuth.customerToken(http, port);
        tokenB = ApiAuth.customerToken(http, port);
        accountA = ApiAuth.field(get("/auth/me", tokenA).body(), "accountNumber");
        accountB = ApiAuth.field(get("/auth/me", tokenB).body(), "accountNumber");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return ApiAuth.get(http, port, path, token);
    }

    private JsonNode history(String query, String token) throws Exception {
        var response = get("/outbound-remittances/mine" + query, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private String row(String account, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        ProviderQuote quote = new ProviderQuote(id, "swiftsend", account, 10000, 100,
                USD, 600, BigDecimal.ONE, 5, Instant.now().plusSeconds(120));
        var row = remittances.saveAndFlush(new OutboundRemittance("out-" + id, id, quote,
                "Recipient", "1234567890", "Bank"));
        jdbc.update("update outbound_remittances set created_at = ? where reference = ?",
                Timestamp.from(createdAt), row.getReference());
        return row.getReference();
    }

    @Test
    void historiesAreScopedAndBrowserAccountParametersCannotOverrideIdentity() throws Exception {
        String a = row(accountA, Instant.now());
        String b = row(accountB, Instant.now());
        var first = history("?account=" + accountB + "&sourceAccount=" + accountB + "&customerId=1", tokenA);
        assertThat(first.path("totalItems").asLong()).isEqualTo(1);
        assertThat(first.path("items").get(0).path("reference").asText()).isEqualTo(a);
        var second = history("?account=" + accountA, tokenB);
        assertThat(second.path("totalItems").asLong()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("reference").asText()).isEqualTo(b);
        assertThat(get("/outbound-remittances/" + a, tokenA).statusCode()).isEqualTo(200);
        assertThat(get("/outbound-remittances/" + a, tokenB).statusCode()).isEqualTo(403);
    }

    @Test
    void pagesUseCreationTimeThenIdAndIncludeScopedTotals() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String first = row(accountA, now);
        String second = row(accountA, now);
        String older = row(accountA, now.minusSeconds(1)); // higher id but older timestamp
        row(accountB, now.plusSeconds(1));
        var page = history("?page=0&size=2", tokenA);
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(page.path("totalItems").asLong()).isEqualTo(3);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("reference").asText()).isEqualTo(second);
        assertThat(page.path("items").get(1).path("reference").asText()).isEqualTo(first);
        var last = history("?page=1&size=2", tokenA);
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("items").get(0).path("reference").asText()).isEqualTo(older);
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(history("?page=2&size=2", tokenA).path("items").size()).isZero();
    }

    @Test
    void emptyHistoryUsesDefaultsAndDoesNotResolveMineAsAReference() throws Exception {
        var page = history("", tokenA);
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("totalItems").asLong()).isZero();
        assertThat(page.path("totalPages").asInt()).isZero();
        assertThat(page.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void invalidPaginationIsRejectedAndMaximumSizeIsAccepted() throws Exception {
        for (String query : new String[]{"?page=-1", "?size=0", "?size=-1", "?size=101", "?page=nope"}) {
            assertThat(get("/outbound-remittances/mine" + query, tokenA).statusCode()).as(query).isEqualTo(400);
        }
        assertThat(history("?size=100", tokenA).path("size").asInt()).isEqualTo(100);
    }

    @Test
    void roleRulesAndOperatorAccessArePreserved() throws Exception {
        String admin = ApiAuth.login(http, port, "history-admin@example.com", "a-very-long-admin-password");
        String reference = row(accountA, Instant.now());
        assertThat(get("/outbound-remittances/mine", null).statusCode()).isEqualTo(401);
        assertThat(get("/outbound-remittances/mine", admin).statusCode()).isEqualTo(403);
        assertThat(get("/outbound-remittances", tokenA).statusCode()).isEqualTo(403);
        assertThat(get("/outbound-remittances", null).statusCode()).isEqualTo(401);
        assertThat(get("/outbound-remittances", admin).statusCode()).isEqualTo(200);
        assertThat(get("/outbound-remittances/" + reference, admin).statusCode()).isEqualTo(200);
        assertThat(ApiAuth.post(http, port, "/outbound-remittances/" + reference + "/reconcile", "{}", tokenA)
                .statusCode()).isEqualTo(403);
    }
}
