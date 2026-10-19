package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;

public interface FxApi {

    /** Rate type reported on every quote while rates come from configuration. */
    String DEMO_RATE_TYPE = "DEMO_CONFIGURED";

    /**
     * Prices a conversion. Read-only: nothing is stored or reserved.
     *
     * @throws IllegalArgumentException for an unsupported pair, a non-positive amount, or an amount
     *                                  too small to convert to at least one minor unit
     */
    FxQuote quote(Currency source, Currency destination, long sourceAmountMinor);
}
