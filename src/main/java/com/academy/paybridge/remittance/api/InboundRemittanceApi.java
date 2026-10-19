package com.academy.paybridge.remittance.api;

import java.util.List;

/**
 * Receiving money: a remittance provider tells PayBridge that USD has arrived for an account number,
 * and PayBridge credits the USD balance of that account. The customer can later convert it to naira
 * with the conversion tool.
 */
public interface InboundRemittanceApi {

    /**
     * Records the payment and credits the account's USD balance. Idempotent on the provider reference:
     * a repeat delivery returns the recorded payment and never credits twice. A payment that cannot be
     * credited is still recorded (UNMATCHED or FAILED) so an operator can see it.
     *
     * @throws IllegalStateException if the provider reference was already used for a different payment
     */
    InboundPaymentView receive(InboundPaymentCommand command);

    /**
     * The provider took a payment back (a chargeback). If it was credited, the money is taken from the
     * customer's USD balance and returned to the USD pool, once. If the balance cannot cover it, the payment
     * becomes REVERSAL_FAILED and a repeated report retries it. A payment never credited is simply cancelled.
     * Idempotent on the provider reference.
     *
     * @throws com.academy.paybridge.shared.exception.ResourceNotFoundException for an unknown provider reference
     * @throws IllegalStateException if the payment is still being processed (retry shortly)
     */
    InboundPaymentView reverse(String providerReference, String reason);

    InboundPaymentView getByReference(String reference);

    /** Newest first. A null or blank status means all. Status: CREDITED, UNMATCHED, FAILED, REVERSED or REVERSAL_FAILED. */
    List<InboundPaymentView> list(String status);
}
