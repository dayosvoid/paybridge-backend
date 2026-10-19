package com.academy.paybridge.remittance.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "inbound_payments")
public class InboundPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    /** Unique: the provider's own id for the payment. This is what makes redelivery safe. */
    @Column(name = "provider_reference", nullable = false, unique = true)
    private String providerReference;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "sender_name")
    private String senderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboundPaymentStatus status = InboundPaymentStatus.RECEIVED;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "credited_at")
    private Instant creditedAt;

    protected InboundPayment() {}

    public InboundPayment(String reference, String providerReference, String accountNumber,
                          Currency currency, long amountMinor, String senderName) {
        this.reference = reference;
        this.providerReference = providerReference;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.senderName = senderName;
    }

    public void markCredited() {
        this.status = InboundPaymentStatus.CREDITED;
        this.failureReason = null;
        this.creditedAt = Instant.now();
    }

    public void markUnmatched(String reason) {
        this.status = InboundPaymentStatus.UNMATCHED;
        this.failureReason = trim(reason);
    }

    public void markFailed(String reason) {
        this.status = InboundPaymentStatus.FAILED;
        this.failureReason = trim(reason);
    }

    private static String trim(String reason) {
        return reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public String getReference() { return reference; }
    public String getProviderReference() { return providerReference; }
    public String getAccountNumber() { return accountNumber; }
    public Currency getCurrency() { return currency; }
    public long getAmountMinor() { return amountMinor; }
    public String getSenderName() { return senderName; }
    public InboundPaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getCreditedAt() { return creditedAt; }
}
