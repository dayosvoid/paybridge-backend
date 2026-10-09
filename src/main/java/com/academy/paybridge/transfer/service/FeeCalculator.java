package com.academy.paybridge.transfer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeeCalculator {

    public record Fees(long feeMinor, long taxMinor) {
        public static final Fees NONE = new Fees(0, 0);

        public long totalMinor() {
            return Math.addExact(feeMinor, taxMinor);
        }
    }

    private final long tier1LimitMinor;
    private final long tier2LimitMinor;
    private final long tier1FeeMinor;
    private final long tier2FeeMinor;
    private final long tier3FeeMinor;
    private final long taxBasisPoints;

    public FeeCalculator(
            @Value("${paybridge.fees.tier1-limit-minor:500000}") long tier1LimitMinor,
            @Value("${paybridge.fees.tier2-limit-minor:5000000}") long tier2LimitMinor,
            @Value("${paybridge.fees.tier1-fee-minor:1000}") long tier1FeeMinor,
            @Value("${paybridge.fees.tier2-fee-minor:2500}") long tier2FeeMinor,
            @Value("${paybridge.fees.tier3-fee-minor:5000}") long tier3FeeMinor,
            @Value("${paybridge.fees.tax-basis-points:750}") long taxBasisPoints) {
        this.tier1LimitMinor = tier1LimitMinor;
        this.tier2LimitMinor = tier2LimitMinor;
        this.tier1FeeMinor = tier1FeeMinor;
        this.tier2FeeMinor = tier2FeeMinor;
        this.tier3FeeMinor = tier3FeeMinor;
        this.taxBasisPoints = taxBasisPoints;
    }

    /** Fee by amount tier, plus tax on the fee only (7.5% = 750 basis points, rounded half-up). */
    public Fees forExternal(long amountMinor) {
        long fee = amountMinor <= tier1LimitMinor ? tier1FeeMinor
                : amountMinor <= tier2LimitMinor ? tier2FeeMinor
                : tier3FeeMinor;
        long tax = (Math.multiplyExact(fee, taxBasisPoints) + 5_000L) / 10_000L;
        return new Fees(fee, tax);
    }
}