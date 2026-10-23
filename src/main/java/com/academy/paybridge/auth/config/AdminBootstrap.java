package com.academy.paybridge.auth.config;

import com.academy.paybridge.auth.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin at startup from configuration, because nobody can register as an admin.
 * Set PAYBRIDGE_ADMIN_EMAIL and PAYBRIDGE_ADMIN_PASSWORD (at least 12 characters). If that email already
 * exists nothing happens, so it is safe on every start. Leave them unset to create no admin.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthService auth;
    private final String email;
    private final String password;

    public AdminBootstrap(AuthService auth,
                          @Value("${paybridge.auth.bootstrap-admin.email:}") String email,
                          @Value("${paybridge.auth.bootstrap-admin.password:}") String password) {
        this.auth = auth;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank()) {
            return;
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException(
                    "paybridge.auth.bootstrap-admin.password must be at least 12 characters");
        }
        if (auth.emailExists(email)) {
            log.info("Bootstrap admin {} already exists", email);
            return;
        }
        auth.createAdmin(email, password, "PayBridge Admin");
        log.info("Bootstrap admin {} created", email);
    }
}
