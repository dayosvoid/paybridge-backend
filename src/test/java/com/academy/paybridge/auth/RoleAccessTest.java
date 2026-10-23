package com.academy.paybridge.auth;

import com.academy.paybridge.support.ApiAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call what. The table below is the decision for EVERY route in the application:
 * a new route fails {@link #everyRouteHasAnAccessDecision} until it is added here (and in SecurityConfig).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paybridge-roles;LOCK_TIMEOUT=10000",
                "paybridge.auth.bootstrap-admin.email=root@example.com",
                "paybridge.auth.bootstrap-admin.password=a-very-long-admin-password"
        })
class RoleAccessTest {

    private enum Access {
        /** No token needed (login, or a webhook that is signed instead). */
        PUBLIC,
        /** Any signed-in user. */
        SIGNED_IN,
        /** Only an ADMIN. */
        ADMIN,
        CUSTOMER
    }

    private static final Map<String, Access> ROUTES = new TreeMap<>(Map.ofEntries(
            Map.entry("POST /auth/register", Access.PUBLIC),
            Map.entry("POST /auth/login", Access.PUBLIC),
            Map.entry("POST /auth/refresh", Access.PUBLIC),
            Map.entry("POST /auth/logout", Access.PUBLIC),
            Map.entry("POST /auth/logout-all", Access.SIGNED_IN),
            Map.entry("GET /auth/me", Access.SIGNED_IN),

            Map.entry("POST /webhooks/remittance/inbound", Access.PUBLIC),
            Map.entry("POST /webhooks/remittance/inbound/reversal", Access.PUBLIC),
            Map.entry("POST /webhooks/remittance/outbound/returned", Access.PUBLIC),
            Map.entry("POST /webhooks/paystack", Access.PUBLIC),

            Map.entry("POST /accounts", Access.ADMIN),
            Map.entry("PUT /accounts/{accountNumber}/status", Access.ADMIN),
            Map.entry("GET /accounts/{accountNumber}", Access.SIGNED_IN),
            Map.entry("GET /accounts/{accountNumber}/balances", Access.SIGNED_IN),
            Map.entry("GET /accounts/{accountNumber}/transactions", Access.SIGNED_IN),

            Map.entry("POST /admin/reconciliation/run", Access.ADMIN),
            Map.entry("POST /admin/settlements/run", Access.ADMIN),
            Map.entry("GET /admin/settlements/batches", Access.ADMIN),
            Map.entry("GET /admin/settlements/batches/{reference}", Access.ADMIN),
            Map.entry("GET /inbound-payments", Access.ADMIN),
            Map.entry("GET /inbound-payments/{reference}", Access.ADMIN),
            Map.entry("GET /outbound-remittances", Access.ADMIN),
            Map.entry("GET /outbound-remittances/mine", Access.CUSTOMER),
            Map.entry("POST /outbound-remittances/{reference}/reconcile", Access.ADMIN),

            Map.entry("GET /outbound-remittances/{reference}", Access.SIGNED_IN),
            Map.entry("POST /outbound-remittances/quotes", Access.SIGNED_IN),
            Map.entry("POST /outbound-remittances", Access.SIGNED_IN),
            Map.entry("POST /fx/conversions/quotes", Access.SIGNED_IN),
            Map.entry("POST /fx/conversions", Access.SIGNED_IN),
            Map.entry("GET /fx/conversions/{reference}", Access.SIGNED_IN),
            Map.entry("GET /fx/quote", Access.SIGNED_IN),
            Map.entry("POST /remittances", Access.SIGNED_IN),
            Map.entry("GET /remittances/{reference}", Access.SIGNED_IN),
            Map.entry("POST /transfers", Access.SIGNED_IN),
            Map.entry("GET /transfers/{reference}", Access.SIGNED_IN),
            Map.entry("GET /transfers", Access.SIGNED_IN),
            Map.entry("GET /kyc/account-details", Access.SIGNED_IN)));

    @Value("${local.server.port}") int port;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> call(String route, String token) throws Exception {
        String method = route.substring(0, route.indexOf(' '));
        String path = route.substring(route.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "x");
        String body = method.equals("GET") ? null : "{}";
        return ApiAuth.request(http, port, method, path, body, token);
    }

    private String customerToken() throws Exception {
        return ApiAuth.customerToken(http, port);
    }

    private String adminToken() throws Exception {
        return ApiAuth.login(http, port, "root@example.com", "a-very-long-admin-password");
    }

    private static boolean blockedByTheLoginWall(HttpResponse<String> r) {
        return r.statusCode() == 401 && r.body().contains("Authentication is required");
    }

    private static boolean forbidden(HttpResponse<String> r) {
        return r.statusCode() == 403 && r.body().contains("\"error\":\"Forbidden\"");
    }

    // ---------- the table is complete ----------

    @Test
    void everyRouteHasAnAccessDecision() {
        TreeSet<String> actual = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getName().startsWith("com.academy.paybridge")) {
                continue;                       // framework endpoints: /error, Swagger
            }
            RequestMappingInfo info = e.getKey();
            assertThat(info.getMethodsCondition().getMethods())
                    .as("every route names its HTTP method: %s", info).isNotEmpty();
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    actual.add(method.name() + " " + pattern);
                }
            }
        }

        assertThat(actual).as("routes in the application vs routes in this table")
                .containsExactlyElementsOf(new TreeSet<>(ROUTES.keySet()));
    }

    // ---------- the table is enforced ----------

    @Test
    void anonymousCallersAreStoppedAtEveryNonPublicRoute() throws Exception {
        for (Map.Entry<String, Access> route : ROUTES.entrySet()) {
            HttpResponse<String> r = call(route.getKey(), null);
            if (route.getValue() == Access.PUBLIC) {
                assertThat(blockedByTheLoginWall(r)).as("%s must be reachable without a token", route.getKey()).isFalse();
                assertThat(r.statusCode()).as(route.getKey()).isNotEqualTo(403);
            } else {
                assertThat(blockedByTheLoginWall(r)).as("%s must need a token (got %d)", route.getKey(), r.statusCode())
                        .isTrue();
            }
        }
    }

    @Test
    void aCustomerIsForbiddenFromEveryAdminRouteAndPastTheLoginWallElsewhere() throws Exception {
        String token = customerToken();
        for (Map.Entry<String, Access> route : ROUTES.entrySet()) {
            if (route.getValue() == Access.PUBLIC) {
                continue;
            }
            HttpResponse<String> r = call(route.getKey(), token);
            if (route.getValue() == Access.ADMIN) {
                assertThat(forbidden(r)).as("%s must be forbidden to a customer (got %d)", route.getKey(), r.statusCode())
                        .isTrue();
            } else {
                // a customer is past the login wall; a 403 here can only be the ownership rule (OwnershipTest)
                assertThat(r.statusCode()).as("%s must let a customer through the wall", route.getKey())
                        .isNotEqualTo(401);
            }
        }
    }

    @Test
    void anAdminPassesEveryRoleCheck() throws Exception {
        String token = adminToken();
        for (Map.Entry<String, Access> route : ROUTES.entrySet()) {
            if (route.getValue() == Access.PUBLIC || route.getKey().equals("POST /auth/logout-all")) {
                continue;
            }
            HttpResponse<String> r = call(route.getKey(), token);
            if (route.getValue() == Access.CUSTOMER) {
                assertThat(forbidden(r)).as(route.getKey()).isTrue();
                continue;
            }
            assertThat(blockedByTheLoginWall(r) || forbidden(r))
                    .as("%s must let an admin through (got %d)", route.getKey(), r.statusCode()).isFalse();
        }
    }

    // ---------- the routes that matter most, by name ----------

    @Test
    void onlyAnAdminCanRunReconciliation() throws Exception {
        assertThat(ApiAuth.request(http, port, "POST", "/admin/reconciliation/run", null, null).statusCode())
                .isEqualTo(401);
        assertThat(ApiAuth.request(http, port, "POST", "/admin/reconciliation/run", null, customerToken()).statusCode())
                .isEqualTo(403);
        assertThat(ApiAuth.request(http, port, "POST", "/admin/reconciliation/run", null, adminToken()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aCustomerCannotFreezeAnAccountOrOpenOneForSomeoneElse() throws Exception {
        String token = customerToken();

        assertThat(ApiAuth.request(http, port, "PUT", "/accounts/0000000001/status", "{\"status\":\"FROZEN\"}", token)
                .statusCode()).isEqualTo(403);
        assertThat(ApiAuth.request(http, port, "POST", "/accounts", "{\"customerId\":4242}", token)
                .statusCode()).isEqualTo(403);
    }

    @Test
    void anAdminCanFreezeAndUnfreezeAnAccount() throws Exception {
        String admin = adminToken();

        HttpResponse<String> frozen = ApiAuth.request(http, port, "PUT", "/accounts/0000000002/status",
                "{\"status\":\"FROZEN\"}", admin);
        HttpResponse<String> active = ApiAuth.request(http, port, "PUT", "/accounts/0000000002/status",
                "{\"status\":\"ACTIVE\"}", admin);

        assertThat(frozen.statusCode()).isEqualTo(200);
        assertThat(active.statusCode()).isEqualTo(200);
    }

    @Test
    void methodMattersTheAdminListIsClosedButASingleOutboundRemittanceIsNot() throws Exception {
        String token = customerToken();

        assertThat(ApiAuth.request(http, port, "GET", "/outbound-remittances", null, token).statusCode()).isEqualTo(403);
        assertThat(ApiAuth.request(http, port, "GET", "/outbound-remittances/out-nope", null, token).statusCode())
                .isEqualTo(404);
    }
}
