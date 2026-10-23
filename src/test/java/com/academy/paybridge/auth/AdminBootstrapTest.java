package com.academy.paybridge.auth;

import com.academy.paybridge.support.ApiAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The first admin comes from configuration. Own in-memory database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:paybridge-admin-bootstrap;LOCK_TIMEOUT=10000",
                "paybridge.auth.bootstrap-admin.email=Boss@Example.com",
                "paybridge.auth.bootstrap-admin.password=a-long-admin-password"
        })
class AdminBootstrapTest {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theConfiguredAdminExistsCanLogInAndHasNoAccount() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from app_users where email = 'boss@example.com' "
                + "and role = 'ADMIN'", Long.class)).isEqualTo(1);

        String token = ApiAuth.login(http, port, "boss@example.com", "a-long-admin-password");
        HttpResponse<String> me = ApiAuth.get(http, port, "/auth/me", token);

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(ApiAuth.field(me.body(), "role")).isEqualTo("ADMIN");
        assertThat(ApiAuth.field(me.body(), "accountNumber")).isNull();
    }
}
