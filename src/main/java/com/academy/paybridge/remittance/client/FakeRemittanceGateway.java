package com.academy.paybridge.remittance.client;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline stand-in for a remittance provider. The last digit of the beneficiary account decides
 * what happens, so every outcome can be tried without a real provider:
 * <pre>
 *   9 -> FAILED now (nothing was sent)
 *   8 -> PENDING now, SUCCESS when verified
 *   7 -> PENDING now, FAILED when verified
 *   6 -> the call fails with an exception (unknown outcome), SUCCESS when verified
 *   5 -> the call fails with an exception (unknown outcome), NOT_FOUND when verified
 *   anything else -> SUCCESS now
 * </pre>
 * The answers live in memory, so they are lost on restart, like an outage.
 */
abstract class FakeRemittanceGateway implements RemittanceGateway {

    private final String providerId;
    private final String displayName;
    private final BigDecimal rate;
    private final long feeMinor;
    private final int etaMinutes;
    private final Map<String, SendStatus> verifyAnswers = new ConcurrentHashMap<>();

    FakeRemittanceGateway(String providerId, String displayName, String rate, long feeMinor, int etaMinutes) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.rate = new BigDecimal(rate);
        this.feeMinor = feeMinor;
        this.etaMinutes = etaMinutes;
    }

    @Override public String providerId() { return providerId; }

    @Override public String displayName() { return displayName; }

    @Override
    public GatewayQuote quote(long sourceAmountMinor, Currency source, Currency destination) {
        if (source != Currency.NGN || destination != Currency.USD) {
            throw new IllegalArgumentException(displayName + " supports NGN to USD only");
        }
        if (sourceAmountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        long destinationMinor = BigDecimal.valueOf(sourceAmountMinor).multiply(rate)
                .setScale(0, RoundingMode.DOWN).longValueExact();
        if (destinationMinor < 1) {
            throw new IllegalArgumentException("Amount is too small to send");
        }
        return new GatewayQuote(rate, feeMinor, destinationMinor, etaMinutes);
    }

    @Override
    public SendResult send(SendInstruction instruction) {
        String reference = instruction.reference();
        String providerRef = displayName.toUpperCase().replace(' ', '-') + "-" + UUID.randomUUID();
        String account = instruction.beneficiaryAccount();
        char last = account.charAt(account.length() - 1);
        return switch (last) {
            case '9' -> {
                verifyAnswers.put(reference, SendStatus.FAILED);
                yield new SendResult(SendStatus.FAILED, null, "Beneficiary account rejected by " + displayName);
            }
            case '8' -> {
                verifyAnswers.put(reference, SendStatus.SUCCESS);
                yield new SendResult(SendStatus.PENDING, providerRef, "Accepted, awaiting payout");
            }
            case '7' -> {
                verifyAnswers.put(reference, SendStatus.FAILED);
                yield new SendResult(SendStatus.PENDING, providerRef, "Accepted, awaiting payout");
            }
            case '6' -> {
                verifyAnswers.put(reference, SendStatus.SUCCESS);
                throw new IllegalStateException(displayName + " timed out");
            }
            case '5' -> {
                verifyAnswers.put(reference, SendStatus.NOT_FOUND);
                throw new IllegalStateException(displayName + " timed out");
            }
            default -> {
                verifyAnswers.put(reference, SendStatus.SUCCESS);
                yield new SendResult(SendStatus.SUCCESS, providerRef, "Paid out by " + displayName);
            }
        };
    }

    @Override
    public SendStatus verify(String reference) {
        return verifyAnswers.getOrDefault(reference, SendStatus.NOT_FOUND);
    }
}
