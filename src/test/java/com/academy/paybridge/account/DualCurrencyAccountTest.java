package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceRequest;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.exception.AccountRestrictedException;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One account number holds an NGN balance and a USD balance.
 * Own in-memory database so the FX pool balances are not disturbed by other test classes.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-dual;LOCK_TIMEOUT=10000")
class DualCurrencyAccountTest {

    @Autowired AccountService accounts;
    @Autowired LedgerApi ledger;
    @Autowired RemittanceApi remittances;
    @Autowired JdbcTemplate jdbc;

    private long newCustomer() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
    }

    private String openAccount() {
        return accounts.open(newCustomer(), "Ada Obi", null, null).accountNumber();
    }

    private void setNgnBalance(String number, long minor) {
        jdbc.update("update accounts set balance_minor = ? where account_number = ? and currency = 'NGN'",
                minor, number);
    }

    private long balance(String number, com.academy.paybridge.shared.money.Currency currency) {
        return accounts.findByNumber(number, currency).orElseThrow().balanceMinor();
    }

    @Test
    void openingAnAccountCreatesBothBalancesUnderOneNumber() {
        AccountView opened = accounts.open(newCustomer(), "Ada Obi", "058", null);

        List<AccountView> all = accounts.findAllByNumber(opened.accountNumber());
        assertThat(all).hasSize(2);
        assertThat(all).extracting(AccountView::currency).containsExactly(NGN, USD);
        assertThat(all).allSatisfy(a -> {
            assertThat(a.accountNumber()).isEqualTo(opened.accountNumber());
            assertThat(a.customerId()).isEqualTo(opened.customerId());
            assertThat(a.balanceMinor()).isZero();
            assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(a.accountName()).isEqualTo("Ada Obi");
            assertThat(a.bankCode()).isEqualTo("058");
        });
        // the account returned from open() is the NGN one
        assertThat(opened.currency()).isEqualTo(NGN);
    }

    @Test
    void aCustomerGetsOnlyOneAccountNumber() {
        long customer = newCustomer();
        accounts.open(customer, null, null, null);

        assertThatThrownBy(() -> accounts.open(customer, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDatabaseRejectsTwoBalancesInTheSameCurrencyUnderOneNumber() {
        String number = openAccount();

        assertThatThrownBy(() -> jdbc.update(
                "insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) "
                        + "values (?, 'USD', 0, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Dup')",
                number, newCustomer()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lookupsByNumberAloneReturnTheNgnBalanceAndTheCurrencyOverloadIsExact() {
        String number = openAccount();

        assertThat(accounts.findByNumber(number).orElseThrow().currency()).isEqualTo(NGN);
        assertThat(accounts.findByNumber(number, NGN).orElseThrow().currency()).isEqualTo(NGN);
        assertThat(accounts.findByNumber(number, USD).orElseThrow().currency()).isEqualTo(USD);
        assertThat(accounts.findByNumber("9999999990")).isEmpty();
        assertThat(accounts.findByNumber("9999999990", USD)).isEmpty();
    }

    @Test
    void thePoolAccountsStaySingleBalanceAccounts() {
        assertThat(accounts.findAllByNumber(SystemAccounts.FX_USD_POOL)).hasSize(1);
        assertThat(accounts.findByNumber(SystemAccounts.FX_USD_POOL).orElseThrow().currency()).isEqualTo(USD);
        assertThat(accounts.findAllByNumber(SystemAccounts.FX_NGN_POOL)).hasSize(1);
    }

    @Test
    void existingDemoAccountsGotAUsdBalanceFromTheBackfill() {
        assertThat(accounts.findByNumber("0000000001", USD)).isPresent();
        assertThat(accounts.findByNumber("0000000002", USD)).isPresent();
        assertThat(balance("0000000001", USD)).isZero();
    }

    @Test
    void creditsAndDebitsOnlyTouchTheBalanceInTheirOwnCurrency() {
        String number = openAccount();

        accounts.credit(number, Money.of("5", USD));

        assertThat(balance(number, USD)).isEqualTo(500);
        assertThat(balance(number, NGN)).isZero();
    }

    @Test
    void creditingACurrencyTheNumberDoesNotHoldIsRejected() {
        String legacy = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) "
                        + "values (?, 'NGN', 0, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Legacy')",
                legacy, newCustomer());

        assertThatThrownBy(() -> accounts.credit(legacy, Money.of("1", USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> accounts.credit("9999999991", Money.of("1", USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account not found");
    }

    @Test
    void ledgerPostingsAreScopedToTheirCurrency() {
        String number = openAccount();
        setNgnBalance(number, 5_000_000);
        long usdPoolBefore = balance(SystemAccounts.FX_USD_POOL, USD);

        ledger.post(SystemAccounts.FX_USD_POOL, number, Money.ofMinor(2_500, USD), "dual-" + UUID.randomUUID());
        ledger.post(number, SystemAccounts.FX_NGN_POOL, Money.ofMinor(1_000_000, NGN), "dual-" + UUID.randomUUID());

        assertThat(balance(number, USD)).isEqualTo(2_500);
        assertThat(balance(number, NGN)).isEqualTo(4_000_000);
        assertThat(balance(SystemAccounts.FX_USD_POOL, USD)).isEqualTo(usdPoolBefore - 2_500);
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entries where account_number = ? and currency = 'USD'",
                Long.class, number)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entries where account_number = ? and currency = 'NGN'",
                Long.class, number)).isEqualTo(1);
    }

    @Test
    void aUsdBalanceCannotGoNegative() {
        String number = openAccount();
        accounts.credit(number, Money.of("1", USD));

        assertThatThrownBy(() -> ledger.post(number, SystemAccounts.FX_USD_POOL,
                Money.ofMinor(101, USD), "dual-" + UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        assertThat(balance(number, USD)).isEqualTo(100);
    }

    @Test
    void freezingAnAccountFreezesBothBalances() {
        String number = openAccount();
        accounts.credit(number, Money.of("5", USD));

        accounts.changeStatus(number, AccountStatus.FROZEN);

        assertThat(accounts.findAllByNumber(number))
                .extracting(AccountView::status).containsOnly(AccountStatus.FROZEN);
        assertThatThrownBy(() -> accounts.debit(number, Money.of("1", USD)))
                .isInstanceOf(AccountRestrictedException.class);
        // a frozen account can still receive
        accounts.credit(number, Money.of("1", USD));
        assertThat(balance(number, USD)).isEqualTo(600);
    }

    @Test
    void anAccountCannotBeClosedWhileEitherBalanceIsNotEmpty() {
        String number = openAccount();
        accounts.credit(number, Money.of("5", USD));      // NGN is empty, USD is not

        assertThatThrownBy(() -> accounts.changeStatus(number, AccountStatus.CLOSED))
                .isInstanceOf(IllegalStateException.class);

        // the failed close rolled back completely: neither balance changed status
        assertThat(accounts.findAllByNumber(number))
                .extracting(AccountView::status).containsOnly(AccountStatus.ACTIVE);
    }

    @Test
    void systemAccountsStillCannotChangeStatus() {
        assertThatThrownBy(() -> accounts.changeStatus(SystemAccounts.FX_USD_POOL, AccountStatus.FROZEN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRemittanceToTheSameNumberConvertsBetweenTheCustomersOwnBalances() {
        String number = openAccount();
        setNgnBalance(number, 20_000_000);

        // NGN 100,000.00 at the demo rate 0.00065 is USD 65.00
        RemittanceView r = remittances.initiate(
                new RemittanceRequest(number, number, 10_000_000, NGN, USD), UUID.randomUUID().toString());

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(balance(number, NGN)).isEqualTo(10_000_000);
        assertThat(balance(number, USD)).isEqualTo(6_500);
    }
}
