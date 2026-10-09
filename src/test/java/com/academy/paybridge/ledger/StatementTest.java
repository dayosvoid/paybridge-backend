package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.EntryKind;
import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.ledger.api.StatementPage;
import com.academy.paybridge.ledger.service.StatementService;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StatementTest {

    @Autowired StatementService statements;
    @Autowired TransferApi transfers;
    @Autowired JdbcTemplate jdbc;

    private String createAccount(long balanceMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) values (?, 'NGN', ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                number, balanceMinor, ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));
        return number;
    }

    private TransferView send(String from, String to, long amountMinor, String bankCode) {
        return transfers.initiate(new TransferRequest(from, to, amountMinor, NGN, bankCode),
                UUID.randomUUID().toString());
    }

    private StatementPage all(String account) {
        return statements.statement(account, null, null, null, 0, 20);
    }

    @Test
    void internalTransferAppearsOnBothStatementsWithRunningBalances() {
        String sender = createAccount(1_000_000);
        String receiver = createAccount(0);
        TransferView t = send(sender, receiver, 100_000, null);

        StatementPage s = all(sender);
        assertThat(s.currentBalanceMinor()).isEqualTo(900_000);
        assertThat(s.items()).hasSize(1);
        LedgerEntryView debit = s.items().get(0);
        assertThat(debit.type()).isEqualTo("DEBIT");
        assertThat(debit.kind()).isEqualTo(EntryKind.TRANSFER);
        assertThat(debit.amountMinor()).isEqualTo(100_000);
        assertThat(debit.transferReference()).isEqualTo(t.reference());
        assertThat(debit.balanceAfterMinor()).isEqualTo(900_000);

        LedgerEntryView credit = all(receiver).items().get(0);
        assertThat(credit.type()).isEqualTo("CREDIT");
        assertThat(credit.balanceAfterMinor()).isEqualTo(100_000);
    }

    @Test
    void externalTransferShowsPrincipalFeeAndTaxNewestFirst() {
        String sender = createAccount(1_000_000);
        send(sender, "0123456781", 100_000, "058");

        StatementPage s = all(sender);

        assertThat(s.items()).extracting(LedgerEntryView::kind)
                .containsExactly(EntryKind.TAX, EntryKind.FEE, EntryKind.TRANSFER);
        assertThat(s.items()).extracting(LedgerEntryView::amountMinor)
                .containsExactly(75L, 1_000L, 100_000L);
        assertThat(s.items()).extracting(LedgerEntryView::balanceAfterMinor)
                .containsExactly(898_925L, 899_000L, 900_000L);
    }

    @Test
    void secondPageKeepsTheRunningBalanceCorrect() {
        String sender = createAccount(1_000_000);
        String receiver = createAccount(0);
        send(sender, receiver, 100_000, null);              // 900,000
        send(sender, "0123456781", 100_000, "058");         // 800,000, then 799,000, then 798,925

        StatementPage first = statements.statement(sender, null, null, null, 0, 2);
        StatementPage second = statements.statement(sender, null, null, null, 1, 2);

        assertThat(first.totalItems()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).extracting(LedgerEntryView::balanceAfterMinor)
                .containsExactly(798_925L, 799_000L);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.items()).extracting(LedgerEntryView::balanceAfterMinor)
                .containsExactly(800_000L, 900_000L);
    }

    @Test
    void failedExternalTransferShowsTheChargesAndTheirReversals() {
        String sender = createAccount(1_000_000);
        TransferView t = send(sender, "0123456789", 100_000, "058");     // provider rejects

        StatementPage s = all(sender);

        assertThat(t.status()).isEqualTo("FAILED");
        assertThat(s.currentBalanceMinor()).isEqualTo(1_000_000);
        assertThat(s.items()).extracting(LedgerEntryView::kind).containsExactly(
                EntryKind.TAX_REVERSAL, EntryKind.FEE_REVERSAL, EntryKind.REVERSAL,
                EntryKind.TAX, EntryKind.FEE, EntryKind.TRANSFER);
        assertThat(s.items()).extracting(LedgerEntryView::transferReference).containsOnly(t.reference());
        assertThat(s.items().get(0).balanceAfterMinor()).isEqualTo(1_000_000);
        assertThat(s.items().get(5).balanceAfterMinor()).isEqualTo(900_000);
    }

    @Test
    void filtersDropTheRunningBalance() {
        String sender = createAccount(1_000_000);
        send(sender, createAccount(0), 100_000, null);

        StatementPage debits = statements.statement(sender, "debit", null, null, 0, 20);
        assertThat(debits.items()).hasSize(1);
        assertThat(debits.items().get(0).balanceAfterMinor()).isNull();

        assertThat(statements.statement(sender, "CREDIT", null, null, 0, 20).items()).isEmpty();
        assertThat(statements.statement(sender, null, Instant.now().plusSeconds(3600), null, 0, 20).items()).isEmpty();
    }

    @Test
    void unknownAccountAndBadInputAreRejected() {
        String account = createAccount(0);

        assertThatThrownBy(() -> all("9999999999")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> statements.statement(account, null, null, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statements.statement(account, null, null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statements.statement(account, "SIDEWAYS", null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statements.statement(account, null, Instant.now(), Instant.now().minusSeconds(60), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}