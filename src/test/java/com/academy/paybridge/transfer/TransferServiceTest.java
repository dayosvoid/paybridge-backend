package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TransferServiceTest {

    private static final String RICH = "0000000001";
    private static final String POOR = "0000000002";

    @Autowired TransferApi transfers;
    @Autowired AccountApi accounts;

    private long balance(String number) {
        return accounts.findByNumber(number).orElseThrow().balanceMinor();
    }

    private String newKey() { return UUID.randomUUID().toString(); }

    @Test
    void sameKeyTwiceMovesMoneyOnlyOnce() {
        String key = newKey();
        TransferRequest request = new TransferRequest(RICH, POOR, 100_000L, NGN);
        long richBefore = balance(RICH);
        long poorBefore = balance(POOR);

        TransferView first = transfers.initiate(request, key);
        TransferView second = transfers.initiate(request, key);

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(balance(RICH)).isEqualTo(richBefore - 100_000L);
        assertThat(balance(POOR)).isEqualTo(poorBefore + 100_000L);
    }

    @Test
    void insufficientFundsIsRecordedAsFailedAndBalancesUnchanged() {
        long poorBefore = balance(POOR);
        long richBefore = balance(RICH);

        TransferView result = transfers.initiate(
                new TransferRequest(POOR, RICH, 100_000_000_000L, NGN), newKey());

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("Insufficient funds");
        assertThat(balance(POOR)).isEqualTo(poorBefore);
        assertThat(balance(RICH)).isEqualTo(richBefore);
        // the FAILED record survives even though the ledger rolled back
        assertThat(transfers.getByReference(result.reference()).status()).isEqualTo("FAILED");
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        String key = newKey();
        transfers.initiate(new TransferRequest(RICH, POOR, 1_000L, NGN), key);

        assertThatThrownBy(() -> transfers.initiate(new TransferRequest(RICH, POOR, 2_000L, NGN), key))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownAccountIsRejectedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> transfers.initiate(
                new TransferRequest(RICH, "9999999999", 1_000L, NGN), newKey()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void externalTransferSucceedsAndMoneyStaysInSettlement() {
        long before = balance(RICH);
        long settlementBefore = balance("0000000000");

        TransferView result = transfers.initiate(
                new TransferRequest(RICH, "0123456781", 50_000L, NGN, "058"), newKey());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(balance(RICH)).isEqualTo(before - 51_075L);
        assertThat(balance("0000000000")).isEqualTo(settlementBefore + 50_000L);
    }

    @Test
    void externalTransferRejectedByProviderIsRefunded() {
        long before = balance(RICH);

        TransferView result = transfers.initiate(
                new TransferRequest(RICH, "0123456789", 50_000L, NGN, "058"), newKey());

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(balance(RICH)).isEqualTo(before);   // money came back
    }

    @Test
    void externalTransferStaysPendingAndKeepsTheHold() {
        long before = balance(RICH);

        TransferView result = transfers.initiate(
                new TransferRequest(RICH, "0123456788", 50_000L, NGN, "058"), newKey());

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(balance(RICH)).isEqualTo(before - 51_075L);   // not refunded yet
    }
}
