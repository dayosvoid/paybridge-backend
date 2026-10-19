package com.academy.paybridge.settlement;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.settlement.api.BatchStatus;
import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.api.SettlementBatchView;
import com.academy.paybridge.shared.exception.AccountRestrictedException;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Settlement runs against the offline fake provider. Its last-digit rules decide the outcome:
 * 0 -> SUCCESS now, 8 -> PENDING now, 9 -> FAILED now.
 *
 * Own in-memory database so other test classes' transfers cannot leak into these batches.
 * Fee for these amounts is 1,000 and tax 75 (minor units), so a sender is debited amount + 1,075.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-settlement;LOCK_TIMEOUT=10000")
class SettlementServiceTest {

    private static final String SETTLEMENT = "0000000000";
    private static final String FEE_INCOME = "0000000003";
    private static final String TAX_PAYABLE = "0000000004";
    private static final String CLEARING = "0000000005";

    private static final String SUCCEEDS = "0123456780";
    private static final String STAYS_PENDING = "0123456788";
    private static final String FAILS = "0123456789";

    private static final Duration NO_DELAY = Duration.ZERO;

    @Autowired SettlementApi settlement;
    @Autowired TransferApi transfers;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    /** Takes anything left over by an earlier test out of play, so each test sees only its own transfers. */
    @BeforeEach
    void isolate() {
        jdbc.update("update transfers set settlement_batch_reference = 'drained' "
                + "where settlement_batch_reference is null");
    }

    // ---------- helpers ----------

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

    private TransferView sendExternal(String from, long amountMinor, String destination) {
        return transfers.initiate(new TransferRequest(from, destination, amountMinor, NGN, "058"),
                UUID.randomUUID().toString());
    }

    private Optional<SettlementBatchView> settleNow() {
        return settlement.settleNextBatch(NGN, NO_DELAY, 50);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private String batchReferenceOf(TransferView t) {
        return jdbc.queryForObject("select settlement_batch_reference from transfers where reference = ?",
                String.class, t.reference());
    }

    private Object succeededAtOf(TransferView t) {
        return jdbc.queryForObject("select succeeded_at from transfers where reference = ?",
                Object.class, t.reference());
    }

    // ---------- tests ----------

    @Test
    void aSuccessfulExternalTransferIsSettledOnceAndOnlyItsPrincipalMoves() {
        String sender = createAccount(1_000_000);
        TransferView t = sendExternal(sender, 100_000, SUCCEEDS);
        assertThat(t.status()).isEqualTo("SUCCESS");

        long settlementBefore = balance(SETTLEMENT);
        long clearingBefore = balance(CLEARING);
        long feeBefore = balance(FEE_INCOME);
        long taxBefore = balance(TAX_PAYABLE);

        SettlementBatchView batch = settleNow().orElseThrow();

        assertThat(batch.status()).isEqualTo(BatchStatus.POSTED);
        assertThat(batch.itemCount()).isEqualTo(1);
        assertThat(batch.totalMinor()).isEqualTo(100_000);
        assertThat(balance(SETTLEMENT)).isEqualTo(settlementBefore - 100_000);
        assertThat(balance(CLEARING)).isEqualTo(clearingBefore + 100_000);
        assertThat(balance(FEE_INCOME)).isEqualTo(feeBefore);
        assertThat(balance(TAX_PAYABLE)).isEqualTo(taxBefore);

        // one DEBIT and one CREDIT entry under the batch reference
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where reference = ?",
                Long.class, batch.reference())).isEqualTo(2L);
        assertThat(batchReferenceOf(t)).isEqualTo(batch.reference());
    }

    @Test
    void aSecondRunSettlesNothing() {
        String sender = createAccount(1_000_000);
        sendExternal(sender, 100_000, SUCCEEDS);
        assertThat(settleNow()).isPresent();

        long settlementAfterFirst = balance(SETTLEMENT);
        long clearingAfterFirst = balance(CLEARING);
        long batchesAfterFirst = count("settlement_batches");

        assertThat(settleNow()).isEmpty();

        assertThat(balance(SETTLEMENT)).isEqualTo(settlementAfterFirst);
        assertThat(balance(CLEARING)).isEqualTo(clearingAfterFirst);
        assertThat(count("settlement_batches")).isEqualTo(batchesAfterFirst);
    }

    @Test
    void aTransferIsNotSettledUntilTheEligibilityDelayHasPassed() {
        String sender = createAccount(1_000_000);
        TransferView t = sendExternal(sender, 100_000, SUCCEEDS);

        assertThat(settlement.settleNextBatch(NGN, Duration.ofHours(6), 50)).isEmpty();
        assertThat(batchReferenceOf(t)).isNull();

        assertThat(settleNow()).isPresent();
        assertThat(batchReferenceOf(t)).isNotNull();
    }

    @Test
    void pendingFailedAndInternalTransfersAreNotEligible() {
        String sender = createAccount(1_000_000);
        String receiver = createAccount(0);

        TransferView pending = sendExternal(sender, 10_000, STAYS_PENDING);
        TransferView failed = sendExternal(sender, 10_000, FAILS);
        TransferView internal = transfers.initiate(new TransferRequest(sender, receiver, 10_000, NGN),
                UUID.randomUUID().toString());

        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(internal.status()).isEqualTo("SUCCESS");

        assertThat(settleNow()).isEmpty();

        // the success time is recorded when a transfer succeeds, and only then
        assertThat(succeededAtOf(pending)).isNull();
        assertThat(succeededAtOf(failed)).isNull();
        assertThat(succeededAtOf(internal)).isNotNull();
    }

    @Test
    void aBatchNeverExceedsTheBatchSizeAndItsTotalMatchesItsItems() {
        String sender = createAccount(1_000_000);
        TransferView t1 = sendExternal(sender, 10_000, SUCCEEDS);
        TransferView t2 = sendExternal(sender, 20_000, SUCCEEDS);
        TransferView t3 = sendExternal(sender, 30_000, SUCCEEDS);

        SettlementBatchView first = settlement.settleNextBatch(NGN, NO_DELAY, 2).orElseThrow();
        SettlementBatchView second = settlement.settleNextBatch(NGN, NO_DELAY, 2).orElseThrow();

        // oldest first
        assertThat(first.itemCount()).isEqualTo(2);
        assertThat(itemReferences(first)).containsExactlyInAnyOrder(t1.reference(), t2.reference());
        assertThat(second.itemCount()).isEqualTo(1);
        assertThat(itemReferences(second)).containsExactly(t3.reference());

        assertThat(itemTotal(first)).isEqualTo(first.totalMinor()).isEqualTo(30_000);
        assertThat(itemTotal(second)).isEqualTo(second.totalMinor()).isEqualTo(30_000);

        assertThat(settlement.settleNextBatch(NGN, NO_DELAY, 2)).isEmpty();
    }

    @Test
    void theDatabaseRefusesToPutATransferInASecondBatch() {
        String sender = createAccount(1_000_000);
        TransferView t = sendExternal(sender, 100_000, SUCCEEDS);
        SettlementBatchView batch = settleNow().orElseThrow();
        Long batchId = jdbc.queryForObject("select id from settlement_batches where reference = ?",
                Long.class, batch.reference());

        assertThatThrownBy(() -> jdbc.update(
                "insert into settlement_items (batch_id, transfer_reference, amount_minor, currency, created_at) "
                        + "values (?, ?, ?, 'NGN', current_timestamp)",
                batchId, t.reference(), 100_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFailedLedgerPostingRollsTheWholeBatchBack() {
        String sender = createAccount(1_000_000);
        TransferView t = sendExternal(sender, 100_000, SUCCEEDS);

        long batchesBefore = count("settlement_batches");
        long itemsBefore = count("settlement_items");
        long settlementBefore = balance(SETTLEMENT);
        long clearingBefore = balance(CLEARING);

        // a closed account cannot be credited, so the ledger posting at the end of the batch fails
        jdbc.update("update accounts set status = 'CLOSED' where account_number = ?", CLEARING);
        try {
            assertThatThrownBy(this::settleNow).isInstanceOf(AccountRestrictedException.class);
        } finally {
            jdbc.update("update accounts set status = 'ACTIVE' where account_number = ?", CLEARING);
        }

        assertThat(count("settlement_batches")).isEqualTo(batchesBefore);
        assertThat(count("settlement_items")).isEqualTo(itemsBefore);
        assertThat(batchReferenceOf(t)).isNull();
        assertThat(balance(SETTLEMENT)).isEqualTo(settlementBefore);
        assertThat(balance(CLEARING)).isEqualTo(clearingBefore);

        // nothing was lost: once the problem is fixed, the same transfer settles
        assertThat(settleNow()).isPresent();
        assertThat(batchReferenceOf(t)).isNotNull();
    }


    @Test
    void lockingCandidatesOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> transfers.lockSettlementCandidates(NGN, Instant.now(), 10))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void badArgumentsAreRejected() {
        assertThatThrownBy(() -> settlement.settleNextBatch(NGN, NO_DELAY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlement.settleNextBatch(NGN, Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlement.settleNextBatch(com.academy.paybridge.shared.money.Currency.USD, NO_DELAY, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- more helpers ----------

    private java.util.List<String> itemReferences(SettlementBatchView batch) {
        return jdbc.queryForList(
                "select transfer_reference from settlement_items "
                        + "where batch_id = (select id from settlement_batches where reference = ?)",
                String.class, batch.reference());
    }

    private Long itemTotal(SettlementBatchView batch) {
        return jdbc.queryForObject(
                "select sum(amount_minor) from settlement_items "
                        + "where batch_id = (select id from settlement_batches where reference = ?)",
                Long.class, batch.reference());
    }
}
