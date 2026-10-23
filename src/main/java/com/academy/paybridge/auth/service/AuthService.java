package com.academy.paybridge.auth.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.auth.api.UserRole;
import com.academy.paybridge.auth.api.UserView;
import com.academy.paybridge.auth.domain.AppUser;
import com.academy.paybridge.auth.repository.AppUserRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final AccountApi accounts;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final TransactionTemplate tx;

    /** Checked against when the email is unknown, so an unknown email takes as long as a wrong password. */
    private final String dummyHash;

    public AuthService(AppUserRepository users, AccountApi accounts, PasswordEncoder encoder,
                       JwtService jwt, RefreshTokenService refreshTokens,
                       PlatformTransactionManager txManager) {
        this.users = users;
        this.accounts = accounts;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.tx = new TransactionTemplate(txManager);
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /** Creates a customer and opens their account (NGN and USD, one number) in one transaction. */
    public UserView register(String email, String password, String fullName) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new IllegalStateException("Email is already registered");
        }
        String hash = encoder.encode(password);
        try {
            return tx.execute(status -> {
                AppUser user = users.saveAndFlush(
                        new AppUser(normalized, hash, fullName.trim(), UserRole.CUSTOMER));
                AccountView account = accounts.open(user.getId(), user.getFullName(), null, null);
                user.assignAccount(account.accountNumber());
                return toView(users.save(user));
            });
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Email is already registered");
        }
    }

    /** Creates an admin. Used only by the bootstrap; there is no public way to become an admin. */
    public UserView createAdmin(String email, String password, String fullName) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new IllegalStateException("Email is already registered");
        }
        return toView(users.save(new AppUser(normalized, encoder.encode(password), fullName, UserRole.ADMIN)));
    }

    public Session login(String email, String password) {
        AppUser user = users.findByEmail(normalize(email)).orElse(null);
        boolean passwordOk = encoder.matches(password == null ? "" : password,
                user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordOk || !user.isEnabled()) {
            throw new InvalidCredentialsException();
        }
        return new Session(jwt.issue(user), refreshTokens.startSession(user.getId()), toView(user));
    }

    public UserView me(Long userId) {
        return users.findById(userId).map(AuthService::toView)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    public boolean emailExists(String email) {
        return users.existsByEmail(normalize(email));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserView toView(AppUser u) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getAccountNumber());
    }
}
