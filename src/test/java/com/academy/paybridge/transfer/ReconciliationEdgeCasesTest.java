package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.client.AccountName;
import com.academy.paybridge.transfer.client.GatewayResult;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferInstruction;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.repository.TransferRepository;
import com.academy.paybridge.transfer.service.ReconciliationService;
import com.academy.paybridge.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-edge;LOCK_TIMEOUT=10000")
class ReconciliationEdgeCasesTest {

    /** A provider whose answers the test controls. */
    static class ScriptedGateway implements TransferGateway {
        private final JdbcTemplate jdbc;
        final Map<String, Supplier<GatewayResult>> verifyScript = new ConcurrentHashMap<>();
        volatile GatewayResult nextSend;
        volatile String closeAccountBeforeReplying;

        ScriptedGateway(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            reset();
        }

        void reset() {
            nextSend = new GatewayResult(GatewayStatus.PENDING, "SCRIPT", "scripted pending");
            closeAccountBeforeReplying = null;
        }

        @Override
        public AccountName resolveAccount(String accountNumber, String bankCode) {
            return new AccountName(accountNumber, "Scripted Holder");
        }

        @Override
        public GatewayResult send(TransferInstruction instruction) {
            if (closeAccountBeforeReplying != null) {
                jdbc.update("update accounts set status = 'CLOSED' where account_number = ?", closeAccountBeforeReplying);
            }
            return nextSend;
        }

        @Override
        public GatewayResult verify(String reference) {
            Supplier<GatewayResult> script = verifyScript.get(reference);
            return script == null
                    ? new GatewayResult(GatewayStatus.PENDING, null, "still pending")
                    : script.get();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedGateway scriptedGateway(JdbcTemplate jdbc) {
            return new ScriptedGateway(jdbc);
        }
    }

    @Autowired ScriptedGateway gateway;
    @Autowired TransferService transferService;
    @Autowired TransferRepository transferRepository;
    @Autowired ReconciliationService reconciliation;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

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

    private TransferView send(String from) {
        return transferService.initiate(new TransferRequest(from, "0123456781", 100_000, NGN, "058"),
                UUID.randomUUID().toString());
    }

    private String status(TransferView t) {
        return transferService.getByReference(t.reference()).status();
    }

    @Test
    void aReferenceTheProviderDoesNotKnowIsKeptWhileYoung() {
        String sender = createAccount(1_000_000);
        TransferView t = send(sender);
        gateway.verifyScript.put(t.reference(),
                () -> new GatewayResult(GatewayStatus.NOT_FOUND, null, "unknown"));

        reconciliation.reconcile(Duration.ZERO, 50);

        assertThat(status(t)).isEqualTo("PENDING");
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);
    }

    @Test
    void aReferenceTheProviderStillDoesNotKnowAfterTheGraceIsRefunded() {
        String sender = createAccount(1_000_000);
        TransferView t = send(sender);
        gateway.verifyScript.put(t.reference(),
                () -> new GatewayResult(GatewayStatus.NOT_FOUND, null, "unknown"));
        jdbc.update("update transfers set created_at = ? where reference = ?",
                OffsetDateTime.now().minusHours(2), t.reference());

        reconciliation.reconcile(Duration.ZERO, 50);

        assertThat(status(t)).isEqualTo("FAILED");
        assertThat(balance(sender)).isEqualTo(1_000_000);
    }

    @Test
    void aProviderErrorLeavesTheTransferPendingAndTheLoopContinues() {
        String sender = createAccount(2_000_000);
        TransferView broken = send(sender);
        TransferView healthy = send(sender);
        gateway.verifyScript.put(broken.reference(), () -> {
            throw new IllegalStateException("provider down");
        });
        gateway.verifyScript.put(healthy.reference(),
                () -> new GatewayResult(GatewayStatus.SUCCESS, "TRF_ok", "ok"));

        ReconciliationService.Summary summary = reconciliation.reconcile(Duration.ZERO, 50);

        assertThat(summary.errors()).isGreaterThanOrEqualTo(1);
        assertThat(status(broken)).isEqualTo("PENDING");
        assertThat(status(healthy)).isEqualTo("SUCCESS");
    }

    @Test
    void aRefundThatFailsIsRetriedByReconciliation() {
        String sender = createAccount(1_000_000);
        gateway.nextSend = new GatewayResult(GatewayStatus.FAILED, null, "bank said no");
        gateway.closeAccountBeforeReplying = sender;        // the refund will be refused

        TransferView t = send(sender);

        assertThat(t.status()).isEqualTo("PENDING");
        assertThat(t.failureReason()).contains("Refund failed");
        assertThat(balance(sender)).isEqualTo(1_000_000 - 101_075);     // still held, nothing lost

        jdbc.update("update accounts set status = 'ACTIVE' where account_number = ?", sender);
        gateway.verifyScript.put(t.reference(),
                () -> new GatewayResult(GatewayStatus.FAILED, null, "bank said no"));
        reconciliation.reconcile(Duration.ZERO, 50);

        assertThat(status(t)).isEqualTo("FAILED");
        assertThat(balance(sender)).isEqualTo(1_000_000);
    }

    @Test
    void aStaleSaveCannotOverwriteASettledTransfer() {
        String sender = createAccount(1_000_000);
        TransferView t = send(sender);
        Transfer stale = transferRepository.findByReference(t.reference()).orElseThrow();

        transferService.settleFromProvider(t.reference(), GatewayStatus.SUCCESS, null);   // someone else settles

        stale.markPending("x", "stale write");
        assertThatThrownBy(() -> transferRepository.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(status(t)).isEqualTo("SUCCESS");
    }
}