package com.academy.paybridge.remittance.service;

import com.academy.paybridge.shared.exception.LimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits on money sent abroad, in NGN minor units of the amount sent (the provider fee is not counted).
 * Per transaction: checked when quoting and again when sending. Per day: the total sent from one account
 * in a rolling 24 hours, counting payments that are COMPLETED or still PENDING.
 */
@Component
public class RemittanceLimits {

    private final long perTransactionMinor;
    private final long dailyMinor;

    public RemittanceLimits(
            @Value("${paybridge.remittance.limits.per-transaction-minor:500000000}") long perTransactionMinor,
            @Value("${paybridge.remittance.limits.daily-minor:1000000000}") long dailyMinor) {
        if (perTransactionMinor <= 0 || dailyMinor <= 0) {
            throw new IllegalStateException("paybridge.remittance.limits.* must be greater than zero");
        }
        this.perTransactionMinor = perTransactionMinor;
        this.dailyMinor = dailyMinor;
    }

    public long perTransactionMinor() { return perTransactionMinor; }

    public long dailyMinor() { return dailyMinor; }

    public void checkPerTransaction(long amountMinor) {
        if (amountMinor > perTransactionMinor) {
            throw new LimitExceededException("Amount is above the per-transaction limit of "
                    + perTransactionMinor + " minor units (NGN " + format(perTransactionMinor) + ")");
        }
    }

    private static String format(long minor) {
        return String.format("%,d.%02d", minor / 100, minor % 100);
    }
}
