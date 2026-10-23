package com.academy.paybridge.auth.api;

/** A signed-in user as other modules and clients see them. Never carries the password hash. */
public record UserView(
        Long id,
        String email,
        String fullName,
        UserRole role,
        String accountNumber) {}
