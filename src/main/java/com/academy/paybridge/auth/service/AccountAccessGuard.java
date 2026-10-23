package com.academy.paybridge.auth.service;

import com.academy.paybridge.auth.api.AccountAccess;
import com.academy.paybridge.auth.api.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads the signed-in user's account number from their access token ("acct" claim), so no database
 * lookup is needed. A missing, blank or different account number never grants ownership.
 * Admins may view accounts without owning them.
 */
@Component("accountAccess")
public class AccountAccessGuard implements AccountAccess {

    @Override
    public boolean canView(Authentication authentication, String accountNumber) {
        return isAdmin(authentication) || owns(authentication, accountNumber);
    }

    @Override
    public boolean canViewEither(Authentication authentication, String accountNumber, String otherAccountNumber) {
        return isAdmin(authentication) || owns(authentication, accountNumber) || owns(authentication, otherAccountNumber);
    }

    @Override
    public boolean canUse(Authentication authentication, String accountNumber) {
        return owns(authentication, accountNumber);
    }

    @Override
    public void requireUse(Authentication authentication, String accountNumber) {
        if (!canUse(authentication, accountNumber)) {
            throw new AccessDeniedException("Not the owner of this account");
        }
    }

    @Override
    public String requireOwnedAccount(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Object claim = jwt.getClaims().get("acct");
            if (claim instanceof String account && canUse(authentication, account)) {
                return account;
            }
        }
        throw new AccessDeniedException("No owned account available");
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + UserRole.ADMIN.name()));
    }

    private static boolean owns(Authentication authentication, String accountNumber) {
        if (authentication == null || !authentication.isAuthenticated()
                || accountNumber == null || accountNumber.isBlank()) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        String owned = jwt.getClaimAsString("acct");
        return owned != null && owned.equals(accountNumber);
    }
}
