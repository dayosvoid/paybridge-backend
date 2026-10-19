package com.academy.paybridge.remittance.client;

import org.springframework.stereotype.Component;

/** Fake provider with the better rate and the higher fee, and slow delivery. */
@Component
public class FakeSwiftSendGateway extends FakeRemittanceGateway {

    public static final String ID = "swiftsend";

    public FakeSwiftSendGateway() {
        // rate 0.00064 USD per NGN, fee NGN 2,500.00, about a day
        super(ID, "SwiftSend", "0.00064", 250_000, 1_440);
    }
}
