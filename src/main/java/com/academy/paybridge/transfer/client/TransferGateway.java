package com.academy.paybridge.transfer.client;

public interface TransferGateway {
    /** Name enquiry: who owns this account? */
    AccountName resolveAccount(String accountNumber, String bankCode);

    /** Ask the provider to send the money. */
    GatewayResult send(TransferInstruction instruction);

    /**
     * Asks the provider what happened to a transfer. Returns NOT_FOUND if it has no record.
     * Any other problem (timeout, 5xx, bad key) is thrown, so the caller can retry later.
     */
    GatewayResult verify(String reference);
}