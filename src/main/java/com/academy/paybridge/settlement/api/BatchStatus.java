package com.academy.paybridge.settlement.api;

/**
 * Where a settlement batch stands. The three states are deliberately separate events:
 * PayBridge recording its own accounting is not the same as the provider confirming it.
 */
public enum BatchStatus {

    /** PayBridge's own ledger posting for the batch was committed. */
    POSTED,

    /** The batch was compared with provider records and matched. */
    RECONCILED,

    /** The comparison found a problem. The ledger posting stays; the batch needs attention. */
    EXCEPTION
}