package com.academy.paybridge.remittance.client;

import org.springframework.stereotype.Component;

/** Fake provider with the worse rate and the lower fee, and fast delivery. */
@Component
public class FakeQuickRemitGateway extends FakeRemittanceGateway {

    public static final String ID = "quickremit";

    public FakeQuickRemitGateway() {
        // rate 0.00062 USD per NGN, fee NGN 1,000.00, about 15 minutes
        super(ID, "QuickRemit", "0.00062", 100_000, 15);
    }
}
