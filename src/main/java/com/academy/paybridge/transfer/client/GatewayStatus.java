package com.academy.paybridge.transfer.client;

public enum GatewayStatus {
    SUCCESS,
    PENDING,
    FAILED,
    /**
     * The provider reversed a transfer that had previously succeeded.
     */
    REVERSED,
    /**
     * Only returned by verify: the provider has no record of this reference.
     */
    NOT_FOUND
}