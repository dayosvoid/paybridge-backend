package com.academy.paybridge.shared.exception;

/** A transaction limit (per transaction or per day) would be exceeded. */
public class LimitExceededException extends RuntimeException {
    public LimitExceededException(String message) {
        super(message);
    }
}
