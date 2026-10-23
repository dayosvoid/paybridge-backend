package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;

/**
 * The PayBridge conversion tool: a customer converts between their own NGN and USD balances,
 * which live under one account number. A conversion is two steps: get a stored, short-lived quote,
 * then convert against that quote.
 */
public interface ConversionApi {

    /**
     * Prices a conversion for an account and stores the quote until it expires.
     *
     * @throws IllegalArgumentException for an unsupported pair, a bad amount, or an account without both balances
     */
    ConversionQuoteView createQuote(String accountNumber, Currency source, Currency destination,
                                    long sourceAmountMinor);

    /**
     * Converts at the quoted rate. Replaying the same idempotency key with the same quote returns the
     * same conversion. A quote can be used once and only before it expires.
     *
     * @throws IllegalStateException if the quote is expired or already used, or the key was used for another quote
     */
    ConversionView convert(String quoteId, String idempotencyKey);

    ConversionView getByReference(String reference);

    /** The account number a stored quote was made for. Used to check the caller owns it before converting. */
    String getQuoteAccountNumber(String quoteId);
}
