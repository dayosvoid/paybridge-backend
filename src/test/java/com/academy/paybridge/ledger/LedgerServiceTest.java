package com.academy.paybridge.ledger;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import com.academy.paybridge.shared.exception.AccountRestrictedException;
import com.academy.paybridge.shared.exception.InsufficientFundsException;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LedgerServiceTest {

    private static final String RICH = "0000000001";   // 100,000.00
    private static final String POOR = "0000000002";   //   5,000.00

    @Autowired LedgerApi ledger;
    @Autowired AccountApi accounts;
    @Autowired LedgerEntryRepository entries;
    @Autowired JdbcTemplate jdbc;

    private long balance(String number) {
        return accounts.findByNumber(number).orElseThrow().balanceMinor();
    }

    @Test
    void movesMoneyAndWritesTwoEntries() {
        long richBefore = balance(RICH);
        long poorBefore = balance(POOR);

        ledger.post(RICH, POOR, Money.of("1000.00", NGN), "REF-OK-1");

        assertThat(balance(RICH)).isEqualTo(richBefore - 100_000L);
        assertThat(balance(POOR)).isEqualTo(poorBefore + 100_000L);
        assertThat(entries.findByReference("REF-OK-1")).hasSize(2);
    }

    @Test
    void rejectsOverdraft() {
        long before = balance(POOR);

        assertThatThrownBy(() -> ledger.post(POOR, RICH, Money.of("999999.00", NGN), "REF-OD-1"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(POOR)).isEqualTo(before);
        assertThat(entries.findByReference("REF-OD-1")).isEmpty();
    }

    @Test
    void debitIsRolledBackWhenCreditFails() {
        long before = balance(RICH);

        // the debit succeeds first, then the credit hits an account that does not exist
        assertThatThrownBy(() -> ledger.post(RICH, "9999999999", Money.of("500.00", NGN), "REF-RB-1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(balance(RICH)).isEqualTo(before);   // proves the debit was undone
        assertThat(entries.findByReference("REF-RB-1")).isEmpty();
    }

    @Test
    void rejectsZeroAmountAndSameAccount() {
        assertThatThrownBy(() -> ledger.post(RICH, POOR, Money.of("0", NGN), "REF-Z-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.post(RICH, RICH, Money.of("10", NGN), "REF-S-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void debitIsRolledBackWhenTheCreditIsRejected() {
        String closed = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) values (?, 'NGN', 0, ?, 0, 'CLOSED', '999', 'PayBridge MFB', 'Closed')",
                closed, ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));
        long before = balance(RICH);

        assertThatThrownBy(() -> ledger.post(RICH, closed, Money.of("500.00", NGN), "REF-CLOSED-" + closed))
                .isInstanceOf(AccountRestrictedException.class);

        assertThat(balance(RICH)).isEqualTo(before);                       // the debit really was undone
        assertThat(entries.findByReference("REF-CLOSED-" + closed)).isEmpty();
    }
}
