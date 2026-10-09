package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.client.TransferGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InterbankSimulationTest {

    private static final String SETTLEMENT = "0000000000";

    @Autowired TransferApi transfers;
    @Autowired AccountApi accounts;
    @Autowired TransferGateway gateway;
    @Autowired JdbcTemplate jdbc;

    private String createAccount(long balanceMinor, String bankCode, String holder, String status) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customerId = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) values (?, 'NGN', ?, ?, 0, ?, ?, ?, ?)",
                number, balanceMinor, customerId, status, bankCode, "Bank " + bankCode, holder);
        return number;
    }

    private long balance(String number) {
        return accounts.findByNumber(number).orElseThrow().balanceMinor();
    }

    private TransferView send(String from, String to, long amountMinor, String bankCode) {
        return transfers.initiate(new TransferRequest(from, to, amountMinor, NGN, bankCode),
                UUID.randomUUID().toString());
    }

    @Test
    void externalTransferToAnotherBanksAccountCreditsTheBeneficiary() {
        String sender = createAccount(1_000_000, "999", "Sender", "ACTIVE");
        String receiver = createAccount(0, "058", "Receiver", "ACTIVE");
        long settlementBefore = balance(SETTLEMENT);

        TransferView result = send(sender, receiver, 200_000, "058");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(balance(sender)).isEqualTo(798_925);
        assertThat(balance(receiver)).isEqualTo(200_000);
        assertThat(balance(SETTLEMENT)).isEqualTo(settlementBefore);   // money passed straight through
    }

    @Test
    void nameEnquiryReturnsTheBeneficiaryHolderName() {
        String receiver = createAccount(0, "058", "Ngozi Okafor", "ACTIVE");

        assertThat(gateway.resolveAccount(receiver, "058").accountName()).isEqualTo("Ngozi Okafor");
    }

    @Test
    void wrongBankCodeIsRejectedBeforeAnythingMoves() {
        String sender = createAccount(500_000, "999", "Sender", "ACTIVE");
        String receiver = createAccount(0, "058", "Receiver", "ACTIVE");

        assertThatThrownBy(() -> send(sender, receiver, 10_000, "044"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balance(sender)).isEqualTo(500_000);
    }

    @Test
    void frozenBeneficiaryStillReceivesMoney() {
        String sender = createAccount(500_000, "999", "Sender", "ACTIVE");
        String receiver = createAccount(0, "058", "Receiver", "FROZEN");

        TransferView result = send(sender, receiver, 100_000, "058");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(balance(receiver)).isEqualTo(100_000);
    }

    @Test
    void closedBeneficiaryFailsAndTheSenderIsFullyRefunded() {
        String sender = createAccount(500_000, "999", "Sender", "ACTIVE");
        String receiver = createAccount(0, "058", "Receiver", "CLOSED");
        long settlementBefore = balance(SETTLEMENT);

        TransferView result = send(sender, receiver, 100_000, "058");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("rejected");
        assertThat(balance(sender)).isEqualTo(500_000);
        assertThat(balance(receiver)).isEqualTo(0);
        assertThat(balance(SETTLEMENT)).isEqualTo(settlementBefore);
    }

    @Test
    void frozenSenderCannotSend() {
        String sender = createAccount(500_000, "999", "Sender", "FROZEN");
        String receiver = createAccount(0, "058", "Receiver", "ACTIVE");

        TransferView result = send(sender, receiver, 100_000, "058");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("FROZEN");
        assertThat(balance(sender)).isEqualTo(500_000);
        assertThat(balance(receiver)).isEqualTo(0);
    }
}