package com.academy.paybridge.transfer.api;

public interface TransferApi {
    TransferView initiate(TransferRequest request, String idempotencyKey);

    TransferView getByReference(String reference);

    /** Transfers where the account is sender or receiver, newest first. */
    TransferPage list(String account, String status, int page, int size);
}