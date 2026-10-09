package com.academy.paybridge.transfer.client;

public enum GatewayStatus {
    SUCCESS,
    PENDING,
    FAILED,
    /** Only returned by verify: the provider has no record of the reference. */
    NOT_FOUND
}