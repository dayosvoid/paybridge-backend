package com.academy.paybridge.auth.api;

import org.springframework.security.core.Authentication;

/**
 * Who may look at or spend from an account. A customer owns exactly one account number (the one opened at
 * registration, carried in their access token). An admin may look at any account but never spends from one.
 *
 * Used from other modules' controllers, mostly as @PreAuthorize("@accountAccess.canUse(authentication, #account)").
 */
public interface AccountAccess {

    /** Read access: the owner of the account, or an admin. */
    boolean canView(Authentication authentication, String accountNumber);

    /** Read access to a record that involves two accounts (a transfer): the owner of either, or an admin. */
    boolean canViewEither(Authentication authentication, String accountNumber, String otherAccountNumber);

    /** Spend access (transfer, convert, send, quote): the owner only. Admins are not owners. */
    boolean canUse(Authentication authentication, String accountNumber);

    /** Like {@link #canUse} but throws AccessDeniedException (403). */
    void requireUse(Authentication authentication, String accountNumber);

    /** The authenticated owner's account, or AccessDeniedException when no account is available. */
    String requireOwnedAccount(Authentication authentication);
}
