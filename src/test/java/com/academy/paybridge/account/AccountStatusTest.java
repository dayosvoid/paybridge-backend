package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.shared.exception.AccountRestrictedException;
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
class AccountStatusTest {

    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;

    private long newCustomer() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
    }

    private void setBalance(String accountNumber, long balanceMinor) {
        jdbc.update("update accounts set balance_minor = ? where account_number = ?", balanceMinor, accountNumber);
    }

    @Test
    void newAccountDefaultsToActiveAtPayBridge() {
        AccountView view = accounts.open(newCustomer(), NGN, null, null, null);

        assertThat(view.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(view.bankCode()).isEqualTo("999");
        assertThat(view.bankName()).isEqualTo("PayBridge MFB");
    }

    @Test
    void bankDetailsCanBeSetWhenOpening() {
        AccountView view = accounts.open(newCustomer(), NGN, "Jane Doe", "058", null);

        assertThat(view.accountName()).isEqualTo("Jane Doe");
        assertThat(view.bankCode()).isEqualTo("058");
        assertThat(view.bankName()).isEqualTo("Bank 058");
    }

    @Test
    void frozenAccountCanReceiveButCannotSend() {
        AccountView view = accounts.open(newCustomer(), NGN, null, null, null);
        String number = view.accountNumber();
        setBalance(number, 10_000L);
        accounts.changeStatus(number, AccountStatus.FROZEN);

        assertThatThrownBy(() -> accounts.debit(number, Money.of("1", NGN)))
                .isInstanceOf(AccountRestrictedException.class);

        accounts.credit(number, Money.of("1", NGN));
        assertThat(accounts.getByNumber(number).balanceMinor()).isEqualTo(10_100L);
    }

    @Test
    void closingNeedsZeroBalanceAndClosedIsFinal() {
        AccountView view = accounts.open(newCustomer(), NGN, null, null, null);
        String number = view.accountNumber();
        setBalance(number, 100L);

        assertThatThrownBy(() -> accounts.changeStatus(number, AccountStatus.CLOSED))
                .isInstanceOf(IllegalStateException.class);

        setBalance(number, 0L);
        accounts.changeStatus(number, AccountStatus.CLOSED);

        assertThatThrownBy(() -> accounts.changeStatus(number, AccountStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.credit(number, Money.of("1", NGN)))
                .isInstanceOf(AccountRestrictedException.class);
    }

    @Test
    void systemAccountsCannotChangeStatus() {
        assertThatThrownBy(() -> accounts.changeStatus("0000000000", AccountStatus.FROZEN))
                .isInstanceOf(IllegalStateException.class);
    }
}
