package com.academy.paybridge.remittance.api;

public interface RemittanceApi {

    /**
     * Converts and sends money. Safe to retry with the same key: the same remittance is returned.
     * An invalid request throws; a valid one that cannot be paid (for example insufficient funds) is
     * recorded with status FAILED and nothing is moved.
     */
    RemittanceView initiate(RemittanceRequest request, String idempotencyKey);

    RemittanceView getByReference(String reference);
}
