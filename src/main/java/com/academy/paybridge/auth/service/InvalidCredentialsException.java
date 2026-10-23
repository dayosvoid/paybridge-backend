package com.academy.paybridge.auth.service;

/** Wrong email or password, or a disabled user. Deliberately says nothing about which. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
