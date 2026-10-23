package com.academy.paybridge.auth.service;

/** The refresh token is unknown, expired, revoked or already used. Says nothing about which. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}
