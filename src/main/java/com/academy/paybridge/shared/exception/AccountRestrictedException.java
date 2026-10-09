package com.academy.paybridge.shared.exception;

public class AccountRestrictedException extends RuntimeException {
    public AccountRestrictedException(String message) {
        super(message);
    }
}