package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReconciliationTest {

    private static final String FEE_INCOME = "0000000003";

    @Autowired TransferApi transfers;
    @Autowired ReconciliationService reconciliation;
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

    private TransferView send(String from, String to) {
        return transfers.initiate(new TransferRequest(from, to, 100_000, NGN, "058"),
                UUID.randomUUID().toString());
    }

    @Test
    void pendingTransferBecomesSuccessWhenTheProviderConfirms() {
        String sender = createAccount(1_000_000);
        long feeBefore = balance(FEE_INCOME);
        TransferView pending = send(sender, "0123456788");          // fake: pending now, success later
        assertThat(pending.status()).isEqualTo("PENDING");

        reconciliation.reconcile(Duration.ZERO, 500);

        TransferView settled = transfers.getByReference(pending.reference());
        assertThat(settled.status()).isEqualTo("SUCCESS");
        assertThat(settled.failureReason()).isNull();
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore + 1_000);
    }

    @Test
    void pendingTransferIsRefundedWhenTheProviderLaterReportsFailure() {
        String sender = createAccount(1_000_000);
        long feeBefore = balance(FEE_INCOME);
        TransferView pending = send(sender, "0123456787");          // fake: pending now, failed later
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);

        reconciliation.reconcile(Duration.ZERO, 500);
        reconciliation.reconcile(Duration.ZERO, 500);               // a second run must change nothing

        assertThat(transfers.getByReference(pending.reference()).status()).isEqualTo("FAILED");
        assertThat(balance(sender)).isEqualTo(1_000_000);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore);
    }

    @Test
    void youngTransfersAreLeftAlone() {
        String sender = createAccount(1_000_000);
        TransferView pending = send(sender, "0123456788");

        reconciliation.reconcile(Duration.ofMinutes(5), 500);

        assertThat(transfers.getByReference(pending.reference()).status()).isEqualTo("PENDING");
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);
    }
}
