package com.academy.paybridge.auth;

import com.academy.paybridge.support.ApiAuth;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.academy.paybridge.support.ApiAuth.PASSWORD;
import static com.academy.paybridge.support.ApiAuth.field;
import static org.assertj.core.api.Assertions.assertThat;

/** Registration, login and access tokens over real HTTP. Own in-memory database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paybridge-auth;LOCK_TIMEOUT=10000",
                "paybridge.auth.jwt-secret=" + AuthIdentityTest.SECRET
        })
class AuthIdentityTest {

    static final String SECRET = "test-jwt-secret-for-auth-tests-0123456789";

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return ApiAuth.post(http, port, path, body, token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return ApiAuth.get(http, port, path, token);
    }

    private String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private HttpResponse<String> register(String email) throws Exception {
        return post("/auth/register", ApiAuth.registerBody(email, PASSWORD, "Ada Obi"), null);
    }

    private String tokenSignedWith(String secret, Instant issuedAt, Instant expiresAt, String issuer) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("1000")
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("role", "ADMIN").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    // ---------- registration ----------

    @Test
    void registeringCreatesTheUserAndAnNgnAndUsdAccountUnderOneNumber() throws Exception {
        String email = newEmail();

        HttpResponse<String> r = register(email);

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(field(r.body(), "email")).isEqualTo(email);
        assertThat(field(r.body(), "role")).isEqualTo("CUSTOMER");
        String account = field(r.body(), "accountNumber");
        assertThat(account).matches("\\d{10}");
        assertThat(r.body()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("passwordHash");
        assertThat(jdbc.queryForList("select currency from accounts where account_number = ? order by currency",
                String.class, account)).containsExactly("NGN", "USD");
        // the account belongs to the user: its customer_id is the user id
        assertThat(jdbc.queryForObject("select count(*) from accounts a join app_users u on u.id = a.customer_id "
                + "where a.account_number = ? and u.email = ?", Long.class, account, email)).isEqualTo(2);
    }

    @Test
    void thePasswordIsStoredOnlyAsABcryptHash() throws Exception {
        String email = newEmail();
        register(email);

        String stored = jdbc.queryForObject("select password_hash from app_users where email = ?",
                String.class, email);

        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void theSameEmailCannotRegisterTwiceEvenInADifferentCase() throws Exception {
        String email = newEmail();
        assertThat(register(email).statusCode()).isEqualTo(201);

        assertThat(register(email).statusCode()).isEqualTo(409);
        assertThat(register(email.toUpperCase()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from app_users where email = ?", Long.class, email))
                .isEqualTo(1);
    }

    @Test
    void sixSimultaneousRegistrationsOfOneEmailCreateOneUserAndOneAccount() throws Exception {
        String email = newEmail();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                Callable<Integer> job = () -> {
                    go.await();
                    return register(email).statusCode();
                };
                futures.add(pool.submit(job));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 201).count()).isEqualTo(1);
            assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from app_users where email = ?", Long.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from accounts a join app_users u on u.id = a.customer_id "
                + "where u.email = ?", Long.class, email)).isEqualTo(2);
    }

    @Test
    void badRegistrationInputIsRejected() throws Exception {
        assertThat(post("/auth/register", ApiAuth.registerBody("not-an-email", PASSWORD, "Ada"), null)
                .statusCode()).isEqualTo(400);
        assertThat(post("/auth/register", ApiAuth.registerBody(newEmail(), "short", "Ada"), null)
                .statusCode()).isEqualTo(400);
        assertThat(post("/auth/register", ApiAuth.registerBody(newEmail(), PASSWORD, " "), null)
                .statusCode()).isEqualTo(400);
        assertThat(post("/auth/register", "{}", null).statusCode()).isEqualTo(400);
    }

    @Test
    void registeringCannotChooseTheRole() throws Exception {
        String email = newEmail();
        HttpResponse<String> r = post("/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                        + "\",\"fullName\":\"Eve\",\"role\":\"ADMIN\"}", null);

        // either the extra field is ignored (201, a customer) or the request is refused (400); never an admin
        assertThat(r.statusCode()).isIn(201, 400);
        assertThat(jdbc.queryForList("select role from app_users where email = ?", String.class, email))
                .allMatch(role -> role.equals("CUSTOMER"));
    }

    // ---------- login ----------

    @Test
    void loggingInReturnsABearerTokenThatIdentifiesTheUser() throws Exception {
        String email = newEmail();
        String account = field(register(email).body(), "accountNumber");

        HttpResponse<String> login = post("/auth/login", ApiAuth.loginBody(email, PASSWORD), null);

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(field(login.body(), "tokenType")).isEqualTo("Bearer");
        assertThat(login.body()).contains("\"expiresInSeconds\":900");
        String token = field(login.body(), "accessToken");
        assertThat(token).isNotBlank();

        HttpResponse<String> me = get("/auth/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(field(me.body(), "email")).isEqualTo(email);
        assertThat(field(me.body(), "accountNumber")).isEqualTo(account);
        assertThat(field(me.body(), "role")).isEqualTo("CUSTOMER");
    }

    @Test
    void emailIsNotCaseSensitiveAtLogin() throws Exception {
        String email = newEmail();
        register(email);

        assertThat(post("/auth/login", ApiAuth.loginBody(email.toUpperCase(), PASSWORD), null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() throws Exception {
        String email = newEmail();
        register(email);

        HttpResponse<String> wrongPassword = post("/auth/login", ApiAuth.loginBody(email, "wrong-password-1"), null);
        HttpResponse<String> unknownEmail = post("/auth/login", ApiAuth.loginBody(newEmail(), PASSWORD), null);

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownEmail.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body()).isEqualTo(unknownEmail.body());
        assertThat(wrongPassword.body()).doesNotContain("accessToken");
    }

    @Test
    void aDisabledUserCannotLogIn() throws Exception {
        String email = newEmail();
        register(email);
        jdbc.update("update app_users set enabled = false where email = ?", email);

        assertThat(post("/auth/login", ApiAuth.loginBody(email, PASSWORD), null).statusCode()).isEqualTo(401);
    }

    @Test
    void loginWithMissingFieldsIsABadRequest() throws Exception {
        assertThat(post("/auth/login", "{}", null).statusCode()).isEqualTo(400);
    }

    // ---------- protected routes ----------

    @Test
    void protectedRoutesNeedAValidToken() throws Exception {
        String token = ApiAuth.customerToken(http, port);

        HttpResponse<String> anonymous = get("/accounts/0000000001", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"error\":\"Unauthorized\"");
        assertThat(get("/accounts/0000000001", "not-a-token").statusCode()).isEqualTo(401);
        assertThat(get("/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/auth/me", token).statusCode()).isEqualTo(200);
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String expired = tokenSignedWith(SECRET, Instant.now().minusSeconds(3_600),
                Instant.now().minusSeconds(1_800), "paybridge");

        assertThat(get("/auth/me", expired).statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        String forged = tokenSignedWith("some-other-secret-that-is-long-enough-1234",
                Instant.now(), Instant.now().plusSeconds(600), "paybridge");

        assertThat(get("/auth/me", forged).statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        String other = tokenSignedWith(SECRET, Instant.now(), Instant.now().plusSeconds(600), "someone-else");

        assertThat(get("/auth/me", other).statusCode()).isEqualTo(401);
    }

    @Test
    void theWebhooksStayOpenToTheProviderBecauseTheyAreSignedInstead() throws Exception {
        // no Bearer token: the answer must come from the webhook's own signature check, not from the login wall
        HttpResponse<String> r = post("/webhooks/remittance/inbound",
                "{\"providerReference\":\"p\",\"accountNumber\":\"0000000001\",\"amountMinor\":1,\"currency\":\"USD\"}",
                null);

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).doesNotContain("Authentication is required");
    }
}
