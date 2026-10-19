package com.academy.paybridge.remittance.client;

import com.academy.paybridge.shared.money.Currency;

/**
 * The port to a cross-border remittance provider. Real providers are adapters behind this interface.
 *
 * <p>Contract for {@link #send}: returning FAILED means the provider confirms nothing was sent, so
 * PayBridge may refund. Throwing (a timeout, a dropped connection) means the outcome is unknown, so
 * PayBridge must NOT refund until {@link #verify} gives a definite answer.
 */
public interface RemittanceGateway {

    /** Stable id stored on quotes and remittances, for example "swiftsend". */
    String providerId();

    String displayName();

    /**
     * Prices a payment.
     *
     * @throws IllegalArgumentException if the provider does not support the pair or the amount
     */
    GatewayQuote quote(long sourceAmountMinor, Currency source, Currency destination);

    SendResult send(SendInstruction instruction);

    /** Asks the provider what happened to a payment, by PayBridge's reference. */
    SendStatus verify(String reference);
}
