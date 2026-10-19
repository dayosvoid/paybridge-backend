package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.FxQuote;
import com.academy.paybridge.shared.money.Currency;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts at fixed, configured rates: one for NGN to USD and one for USD to NGN. The two are set
 * separately, so the gap between them is PayBridge's margin. NGN and USD both use two decimal places,
 * so a rate applies directly to minor units. The result is rounded DOWN to a whole minor unit, so
 * PayBridge never pays out more than the rate allows.
 */
@Service
public class FxService implements FxApi {

    private final BigDecimal ngnToUsd;
    private final BigDecimal usdToNgn;

    public FxService(@Value("${paybridge.fx.ngn-usd-rate:0.00065}") String ngnToUsd,
                     @Value("${paybridge.fx.usd-ngn-rate:1500}") String usdToNgn) {
        this.ngnToUsd = parse("paybridge.fx.ngn-usd-rate", ngnToUsd);
        this.usdToNgn = parse("paybridge.fx.usd-ngn-rate", usdToNgn);
    }

    private static BigDecimal parse(String property, String value) {
        BigDecimal rate;
        try {
            rate = new BigDecimal(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException(property + " is not a number: " + value, e);
        }
        if (rate.signum() <= 0) {
            throw new IllegalStateException(property + " must be greater than zero");
        }
        return rate;
    }

    @Override
    public FxQuote quote(Currency source, Currency destination, long sourceAmountMinor) {
        BigDecimal rate;
        if (source == Currency.NGN && destination == Currency.USD) {
            rate = ngnToUsd;
        } else if (source == Currency.USD && destination == Currency.NGN) {
            rate = usdToNgn;
        } else {
            throw new IllegalArgumentException("Unsupported currency pair: " + source + " to " + destination
                    + ". Only NGN to USD and USD to NGN are supported");
        }
        if (sourceAmountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        long destinationMinor;
        try {
            destinationMinor = BigDecimal.valueOf(sourceAmountMinor)
                    .multiply(rate)
                    .setScale(0, RoundingMode.DOWN)
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount is too large to convert");
        }
        if (destinationMinor < 1) {
            throw new IllegalArgumentException("Amount is too small to convert");
        }
        return new FxQuote(source, destination, sourceAmountMinor, destinationMinor, rate, DEMO_RATE_TYPE);
    }
}
