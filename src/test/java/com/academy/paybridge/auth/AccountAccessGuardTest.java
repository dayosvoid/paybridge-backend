package com.academy.paybridge.auth;

import com.academy.paybridge.auth.service.AccountAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountAccessGuardTest {
    private final AccountAccessGuard guard = new AccountAccessGuard();

    @Test
    void resolvesOnlyAnAuthenticatedJwtWithANonblankAccount() {
        Jwt jwt = token("1234567890");
        assertThat(guard.requireOwnedAccount(new JwtAuthenticationToken(jwt, java.util.List.of())))
                .isEqualTo("1234567890");
        assertThatThrownBy(() -> guard.requireOwnedAccount(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.requireOwnedAccount(
                UsernamePasswordAuthenticationToken.unauthenticated(jwt, "")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.requireOwnedAccount(
                UsernamePasswordAuthenticationToken.authenticated("user", "", java.util.List.of())))
                .isInstanceOf(AccessDeniedException.class);
        for (Object claim : new Object[]{"", "   ", 123L}) {
            assertThatThrownBy(() -> guard.requireOwnedAccount(new JwtAuthenticationToken(token(claim), java.util.List.of())))
                    .isInstanceOf(AccessDeniedException.class);
        }
        Jwt missing = Jwt.withTokenValue("test").header("alg", "HS256").subject("1").build();
        assertThatThrownBy(() -> guard.requireOwnedAccount(new JwtAuthenticationToken(missing, java.util.List.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Jwt token(Object account) {
        return Jwt.withTokenValue("test").header("alg", "HS256").subject("1").claim("acct", account).build();
    }
}
