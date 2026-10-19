package com.academy.paybridge.transfer.api;

import com.academy.paybridge.shared.money.Currency;

import java.time.Instant;
import java.util.List;

public interface TransferApi {

    TransferView initiate(TransferRequest request, String idempotencyKey);

    TransferView getByReference(String reference);

    /** Transfers where the account is sender or receiver, newest first. */
    TransferPage list(String account, String status, int page, int size);

    /** Resolve the name associated with an external bank account. */
    ResolvedAccountView resolveAccount(String accountNumber, String bankCode);

    /**
     * Locks and returns successful, unsettled external transfers whose success time is at or before
     * {@code succeededBefore}, oldest first. Must run inside the caller's transaction.
     */
    List<SettlementCandidate> lockSettlementCandidates(Currency currency, Instant succeededBefore, int limit);

    /** Marks the transfers as settled in the given batch. Must run inside the caller's transaction. */
    void markSettled(List<String> transferReferences, String batchReference);
}