package com.academy.paybridge.auth.config;

import com.academy.paybridge.auth.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API security. Every route needs a valid access token except:
 *  - /auth/register, /auth/login, /auth/refresh and /auth/logout: how you get, renew and drop a token
 *  - /webhooks/**: called by the remittance provider, protected by its own HMAC signature instead
 *  - the demo page, Swagger UI and the error page
 * Operator routes (reconciliation, opening or freezing accounts, the inbound-payment list, the outbound list
 * and manual reconcile) need the ADMIN role. Outbound /mine requires CUSTOMER. Other routes need a signed-in user; module 4 adds
 * per-account ownership checks on top. RoleAccessTest lists every route and fails when one is added without
 * a decision here.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder(@Value("${paybridge.auth.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                            JsonSecurityHandlers handlers) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/", "/index.html", "/error",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // ---- operator routes: ADMIN only ----
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/accounts").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/accounts/*/status").hasRole("ADMIN")
                        .requestMatchers("/inbound-payments", "/inbound-payments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/outbound-remittances").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/outbound-remittances/mine").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/outbound-remittances/*/reconcile").hasRole("ADMIN")
                        // ---- everything else: any signed-in user ----
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
