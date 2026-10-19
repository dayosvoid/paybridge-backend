package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;

import java.util.List;

/**
 * Sending money abroad through a provider. The customer asks for quotes (one per provider), picks
 * one, and sends. Naira is held first; a provider that confirms nothing was sent triggers a refund,
 * and an unknown outcome stays PENDING (money still held) until a verification answers.
 */
public interface OutboundRemittanceApi {

    /**
     * Asks every provider for a price and stores each offer until it expires. Best offer first.
     *
     * @throws IllegalArgumentException for a bad amount, unsupported currency or an account without an NGN balance
     * @throws IllegalStateException    if no provider could quote
     */
    List<ProviderQuoteView> getQuotes(String sourceAccount, long sourceAmountMinor, Currency destinationCurrency);

    /**
     * Sends against a stored quote. Replaying the same idempotency key with the same quote returns the same
     * remittance. A quote can be used once and only before it expires.
     *
     * @throws IllegalStateException if the quote is expired or already used, or the key was used for another quote
     */
    OutboundRemittanceView send(OutboundRemittanceRequest request, String idempotencyKey);

    OutboundRemittanceView getByReference(String reference);

    /** Asks the provider about a PENDING remittance and settles it if the answer is definite. */
    OutboundRemittanceView reconcile(String reference);

    /**
     * The provider reports that a COMPLETED payout came back (bounced). The amount sent is refunded to the
     * customer once; the provider fee stays with PayBridge's clearing account. Idempotent.
     *
     * @throws IllegalStateException if the remittance is not COMPLETED (a PENDING one must be reconciled first)
     */
    OutboundRemittanceView markReturned(String reference, String reason);

    /** Newest first, at most 100. A null or blank status means all. */
    List<OutboundRemittanceView> list(String status);

    /** Reconciles every PENDING remittance older than the given age. Returns how many were settled. */
    int reconcilePending(long olderThanSeconds, int limit);
}
