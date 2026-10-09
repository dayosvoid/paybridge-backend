package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.academy.paybridge.transfer.client.GatewayStatus;

import java.util.UUID;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferSettlementTest {

    private static final String RICH = "0000000001";

    @Autowired TransferService transfers;
    @Autowired AccountApi accounts;

    private long balance() { return accounts.findByNumber(RICH).orElseThrow().balanceMinor(); }

    /** The fake gateway leaves destinations ending in 8 PENDING. */
    private TransferView pending() {
        return transfers.initiate(new TransferRequest(RICH, "0123456788", 50_000L, NGN, "058"),
                UUID.randomUUID().toString());
    }

    @Test
    void failedOutcomeRefundsExactlyOnce() {
        long before = balance();
        TransferView t = pending();
        assertThat(balance()).isEqualTo(before - 51_075L);
        transfers.settleFromProvider(t.reference(), GatewayStatus.FAILED, "reversed");
        transfers.settleFromProvider(t.reference(), GatewayStatus.FAILED, "reversed");   // duplicate delivery

        assertThat(balance()).isEqualTo(before);
        assertThat(transfers.getByReference(t.reference()).status()).isEqualTo("FAILED");
    }

    @Test
    void successOutcomeKeepsTheHoldAndLaterEventsAreIgnored() {
        long before = balance();
        TransferView t = pending();

        transfers.settleFromProvider(t.reference(), GatewayStatus.SUCCESS, null);
        transfers.settleFromProvider(t.reference(), GatewayStatus.FAILED, "late contradictory event");

        assertThat(transfers.getByReference(t.reference()).status()).isEqualTo("SUCCESS");
        assertThat(balance()).isEqualTo(before - 51_075L);
    }

    @Test
    void unknownReferenceIsIgnored() {
        transfers.settleFromProvider("trf-does-not-exist", GatewayStatus.SUCCESS, null);
    }
}