package com.academy.paybridge.transfer.api;

import com.academy.paybridge.transfer.api.ResolvedAccountView;

public interface TransferApi {
    TransferView initiate(TransferRequest request, String idempotencyKey);

    TransferView getByReference(String reference);

    /** Transfers where the account is sender or receiver, newest first. */
    TransferPage list(String account, String status, int page, int size);

    /** Resolve the name associated with an external bank account. */
    ResolvedAccountView resolveAccount(String accountNumber, String bankCode);
}