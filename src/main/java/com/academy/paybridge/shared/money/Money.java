package com.academy.paybridge.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money (long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency is required");
    }

    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits,currency);
    }

    public static Money of(String amount, Currency currency ) {
        BigDecimal rounded = new BigDecimal(amount).setScale(2,RoundingMode.HALF_EVEN);
        return new Money(rounded.movePointRight(2).longValueExact(), currency);
    }


    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public boolean isNegative() { return minorUnits < 0; }

    public boolean isZero() { return minorUnits == 0; }

    public boolean isPositive() { return minorUnits > 0; }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, 2);
    }

    @Override
    public String toString() {
        return currency + " " + toMajor().toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (currency != other.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
            }
        }

    }
