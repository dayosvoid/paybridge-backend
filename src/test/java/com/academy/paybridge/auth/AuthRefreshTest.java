package com.academy.paybridge.auth;

import com.academy.paybridge.support.ApiAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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

/** Refresh tokens: rotation, reuse detection, logout. Own in-memory database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:paybridge-refresh;LOCK_TIMEOUT=10000")
class AuthRefreshTest {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Login(String email, String access, String refresh) {}

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return ApiAuth.post(http, port, path, body, token);
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", null);
    }

    private HttpResponse<String> logout(String refreshToken) throws Exception {
        return post("/auth/logout", "{\"refreshToken\":\"" + refreshToken + "\"}", null);
    }

    private String registerNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        assertThat(post("/auth/register", ApiAuth.registerBody(email, PASSWORD, "Ada Obi"), null).statusCode())
                .isEqualTo(201);
        return email;
    }

    private Login login(String email) throws Exception {
        HttpResponse<String> r = post("/auth/login", ApiAuth.loginBody(email, PASSWORD), null);
        assertThat(r.statusCode()).isEqualTo(200);
        return new Login(email, field(r.body(), "accessToken"), field(r.body(), "refreshToken"));
    }

    private Login newLogin() throws Exception {
        return login(registerNewUser());
    }

    private long unrevokedTokens(String email) {
        return jdbc.queryForObject("select count(*) from refresh_tokens t join app_users u on u.id = t.user_id "
                + "where u.email = ? and t.revoked_at is null", Long.class, email);
    }

    // ---------- issuing ----------

    @Test
    void loggingInReturnsARefreshTokenThatIsStoredOnlyAsAHash() throws Exception {
        String email = registerNewUser();

        HttpResponse<String> r = post("/auth/login", ApiAuth.loginBody(email, PASSWORD), null);

        String refreshToken = field(r.body(), "refreshToken");
        assertThat(refreshToken).hasSizeGreaterThanOrEqualTo(40);
        assertThat(r.body()).contains("\"refreshExpiresInSeconds\":604800");
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Long.class,
                RefreshHash.of(refreshToken))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Long.class,
                refreshToken)).as("the raw token is never stored").isZero();
    }

    @Test
    void everyLoginGetsItsOwnRefreshToken() throws Exception {
        String email = registerNewUser();

        Login first = login(email);
        Login second = login(email);

        assertThat(first.refresh()).isNotEqualTo(second.refresh());
        assertThat(jdbc.queryForObject("select count(distinct t.family_id) from refresh_tokens t "
                + "join app_users u on u.id = t.user_id where u.email = ?", Long.class, email)).isEqualTo(2);
    }

    // ---------- rotation ----------

    @Test
    void refreshingGivesANewWorkingAccessTokenAndANewRefreshToken() throws Exception {
        Login login = newLogin();

        HttpResponse<String> r = refresh(login.refresh());

        assertThat(r.statusCode()).isEqualTo(200);
        String newAccess = field(r.body(), "accessToken");
        String newRefresh = field(r.body(), "refreshToken");
        assertThat(newRefresh).isNotEqualTo(login.refresh());
        assertThat(newAccess).isNotBlank();
        HttpResponse<String> me = ApiAuth.get(http, port, "/auth/me", newAccess);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(field(me.body(), "email")).isEqualTo(login.email());
    }

    @Test
    void aLoginCanBeRefreshedOverAndOverByAlwaysUsingTheLatestToken() throws Exception {
        String token = newLogin().refresh();

        for (int i = 0; i < 4; i++) {
            HttpResponse<String> r = refresh(token);
            assertThat(r.statusCode()).as("refresh #%d", i + 1).isEqualTo(200);
            token = field(r.body(), "refreshToken");
        }
    }

    @Test
    void aRefreshTokenWorksOnlyOnce() throws Exception {
        Login login = newLogin();
        assertThat(refresh(login.refresh()).statusCode()).isEqualTo(200);

        HttpResponse<String> again = refresh(login.refresh());

        assertThat(again.statusCode()).isEqualTo(401);
        assertThat(again.body()).doesNotContain("accessToken");
    }

    @Test
    void usingAnOldTokenAgainSignsTheWholeLoginOut() throws Exception {
        Login login = newLogin();
        String newest = field(refresh(login.refresh()).body(), "refreshToken");

        assertThat(refresh(login.refresh()).statusCode()).isEqualTo(401);     // replayed: someone else has it

        assertThat(refresh(newest).statusCode()).as("the token that replaced it is dead too").isEqualTo(401);
        assertThat(unrevokedTokens(login.email())).isZero();
    }

    @Test
    void reuseInOneLoginDoesNotSignOutTheUsersOtherLogins() throws Exception {
        String email = registerNewUser();
        Login phone = login(email);
        Login laptop = login(email);
        refresh(phone.refresh());
        refresh(phone.refresh());                                               // replay on the phone

        assertThat(refresh(laptop.refresh()).statusCode()).isEqualTo(200);
    }

    // ---------- bad tokens ----------

    @Test
    void anUnknownOrMissingRefreshTokenIsRejected() throws Exception {
        assertThat(refresh("not-a-real-token").statusCode()).isEqualTo(401);
        assertThat(refresh(UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        assertThat(post("/auth/refresh", "{}", null).statusCode()).isEqualTo(400);
    }

    @Test
    void anExpiredRefreshTokenIsRejected() throws Exception {
        Login login = newLogin();
        jdbc.update("update refresh_tokens set expires_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), RefreshHash.of(login.refresh()));

        assertThat(refresh(login.refresh()).statusCode()).isEqualTo(401);
    }

    @Test
    void anAccessTokenIsNotAcceptedAsARefreshToken() throws Exception {
        Login login = newLogin();

        assertThat(refresh(login.access()).statusCode()).isEqualTo(401);
    }

    @Test
    void aDisabledUserCannotRefreshAndTheLoginIsRevoked() throws Exception {
        Login login = newLogin();
        jdbc.update("update app_users set enabled = false where email = ?", login.email());

        assertThat(refresh(login.refresh()).statusCode()).isEqualTo(401);
        assertThat(unrevokedTokens(login.email())).isZero();
    }

    // ---------- logout ----------

    @Test
    void loggingOutKillsTheRefreshTokenAndIsRepeatable() throws Exception {
        Login login = newLogin();

        assertThat(logout(login.refresh()).statusCode()).isEqualTo(204);
        assertThat(logout(login.refresh()).statusCode()).isEqualTo(204);

        assertThat(refresh(login.refresh()).statusCode()).isEqualTo(401);
    }

    @Test
    void loggingOutAfterARefreshKillsTheLatestTokenToo() throws Exception {
        Login login = newLogin();
        String newest = field(refresh(login.refresh()).body(), "refreshToken");

        assertThat(logout(login.refresh()).statusCode()).isEqualTo(204);

        assertThat(refresh(newest).statusCode()).isEqualTo(401);
    }

    @Test
    void loggingOutWithAnUnknownTokenIsHarmless() throws Exception {
        assertThat(logout("never-issued").statusCode()).isEqualTo(204);
    }

    @Test
    void loggingOutOneDeviceLeavesTheOthersSignedIn() throws Exception {
        String email = registerNewUser();
        Login phone = login(email);
        Login laptop = login(email);

        logout(phone.refresh());

        assertThat(refresh(phone.refresh()).statusCode()).isEqualTo(401);
        assertThat(refresh(laptop.refresh()).statusCode()).isEqualTo(200);
    }

    @Test
    void loggingOutEverywhereKillsEveryLoginOfTheUserButNobodyElses() throws Exception {
        String email = registerNewUser();
        Login phone = login(email);
        Login laptop = login(email);
        Login stranger = newLogin();

        assertThat(post("/auth/logout-all", "", null).statusCode()).isEqualTo(401);     // needs an access token
        assertThat(post("/auth/logout-all", "", phone.access()).statusCode()).isEqualTo(204);

        assertThat(refresh(phone.refresh()).statusCode()).isEqualTo(401);
        assertThat(refresh(laptop.refresh()).statusCode()).isEqualTo(401);
        assertThat(refresh(stranger.refresh()).statusCode()).isEqualTo(200);
    }

    // ---------- the same token twice at once ----------

    @Test
    void sixSimultaneousRefreshesWithOneTokenYieldExactlyOneNewToken() throws Exception {
        Login login = newLogin();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                Callable<Integer> job = () -> {
                    go.await();
                    return refresh(login.refresh()).statusCode();
                };
                futures.add(pool.submit(job));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
            assertThat(codes.stream().filter(c -> c == 401).count()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        // the extra attempts are treated as theft: the login ends up fully revoked
        assertThat(unrevokedTokens(login.email())).isZero();
    }
}
