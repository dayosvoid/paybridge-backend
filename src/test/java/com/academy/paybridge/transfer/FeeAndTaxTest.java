package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.academy.paybridge.transfer.client.GatewayStatus;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FeeAndTaxTest {

    private static final String SETTLEMENT = "0000000000";
    private static final String FEE_INCOME = "0000000003";
    private static final String TAX_PAYABLE = "0000000004";

    @Autowired TransferService transfers;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    private String createAccount(long balanceMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) values (?, 'NGN', ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                number, balanceMinor, ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));
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
    void externalTransferChargesFeeAndTaxToTheSender() {
        String sender = createAccount(1_000_000);
        long feeBefore = balance(FEE_INCOME);
        long taxBefore = balance(TAX_PAYABLE);
        long settlementBefore = balance(SETTLEMENT);

        TransferView result = send(sender, "0123456781", 100_000, "058");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.feeMinor()).isEqualTo(1_000);
        assertThat(result.taxMinor()).isEqualTo(75);
        assertThat(balance(sender)).isEqualTo(1_000_000 - 100_000 - 1_000 - 75);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore + 1_000);
        assertThat(balance(TAX_PAYABLE)).isEqualTo(taxBefore + 75);
        assertThat(balance(SETTLEMENT)).isEqualTo(settlementBefore + 100_000);
    }

    @Test
    void internalTransferHasNoFeeOrTax() {
        String sender = createAccount(1_000_000);
        String receiver = createAccount(0);
        long feeBefore = balance(FEE_INCOME);
        long taxBefore = balance(TAX_PAYABLE);

        TransferView result = send(sender, receiver, 100_000, null);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.feeMinor()).isZero();
        assertThat(result.taxMinor()).isZero();
        assertThat(balance(sender)).isEqualTo(900_000);
        assertThat(balance(receiver)).isEqualTo(100_000);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore);
        assertThat(balance(TAX_PAYABLE)).isEqualTo(taxBefore);
    }

    @Test
    void providerRejectionRefundsPrincipalFeeAndTax() {
        String sender = createAccount(1_000_000);
        long feeBefore = balance(FEE_INCOME);
        long taxBefore = balance(TAX_PAYABLE);
        long settlementBefore = balance(SETTLEMENT);

        TransferView result = send(sender, "0123456789", 100_000, "058");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(balance(sender)).isEqualTo(1_000_000);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore);
        assertThat(balance(TAX_PAYABLE)).isEqualTo(taxBefore);
        assertThat(balance(SETTLEMENT)).isEqualTo(settlementBefore);
    }

    @Test
    void senderMustCoverPrincipalFeeAndTaxTogether() {
        String sender = createAccount(100_000);        // enough for the amount, not for the charges
        long feeBefore = balance(FEE_INCOME);

        TransferView result = send(sender, "0123456781", 100_000, "058");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("Insufficient funds");
        assertThat(balance(sender)).isEqualTo(100_000);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore);
    }

    @Test
    void pendingTransferRefundsEverythingWhenTheProviderLaterFails() {
        String sender = createAccount(1_000_000);
        long feeBefore = balance(FEE_INCOME);
        long taxBefore = balance(TAX_PAYABLE);

        TransferView pending = send(sender, "0123456788", 100_000, "058");
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);

        transfers.settleFromProvider(pending.reference(), GatewayStatus.FAILED, "provider reversed");
        transfers.settleFromProvider(pending.reference(), GatewayStatus.FAILED, "duplicate event");

        assertThat(balance(sender)).isEqualTo(1_000_000);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore);
        assertThat(balance(TAX_PAYABLE)).isEqualTo(taxBefore);
    }

    @Test
    void pendingTransferSettledAsSuccessKeepsFeeAndTaxAndClearsTheNote() {
        String sender = createAccount(1_000_000);
        long feeBefore = balance(FEE_INCOME);

        TransferView pending = send(sender, "0123456788", 100_000, "058");
        transfers.settleFromProvider(pending.reference(), GatewayStatus.SUCCESS, null);

        TransferView settled = transfers.getByReference(pending.reference());
        assertThat(settled.status()).isEqualTo("SUCCESS");
        assertThat(settled.failureReason()).isNull();
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore + 1_000);
    }
}