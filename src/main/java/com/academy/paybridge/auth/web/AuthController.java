package com.academy.paybridge.auth.web;

import com.academy.paybridge.auth.api.UserView;
import com.academy.paybridge.auth.service.AuthService;
import com.academy.paybridge.auth.service.RefreshTokenService;
import com.academy.paybridge.auth.service.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            // 72: bcrypt ignores anything longer
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String fullName) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String refreshToken,
            long refreshExpiresInSeconds,
            UserView user) {

        static TokenResponse of(Session s) {
            return new TokenResponse(s.accessToken().token(), "Bearer", s.accessToken().expiresInSeconds(),
                    s.refreshToken().token(), s.refreshToken().expiresInSeconds(), s.user());
        }
    }

    private final AuthService auth;
    private final RefreshTokenService refreshTokens;

    public AuthController(AuthService auth, RefreshTokenService refreshTokens) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
    }

    /** Creates a customer and their account. The response carries the new account number. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request.email(), request.password(), request.fullName());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.of(auth.login(request.email(), request.password()));
    }

    /**
     * Trades a refresh token for a new access token and a NEW refresh token. The old refresh token stops
     * working at once; presenting it again signs that login out everywhere.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(refreshTokens.refresh(request.refreshToken()));
    }

    /** Ends this login. Always 204, so it cannot be used to probe which tokens exist. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokens.logout(request.refreshToken());
    }

    /** Ends every login of the signed-in user, on all devices. Needs a valid access token. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        refreshTokens.logoutAll(Long.valueOf(jwt.getSubject()));
    }

    /** Who the access token belongs to. */
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(Long.valueOf(jwt.getSubject()));
    }
}
