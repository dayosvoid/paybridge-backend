package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.StatementPage;
import com.academy.paybridge.ledger.service.StatementService;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

/** Statements are per balance: an account number's NGN and USD entries never mix. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-dual-statement;LOCK_TIMEOUT=10000")
class DualCurrencyStatementTest {

    @Autowired StatementService statements;
    @Autowired LedgerApi ledger;
    @Autowired JdbcTemplate jdbc;

    private String createDualAccount(long ngnMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customer = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        for (String currency : new String[] {"NGN", "USD"}) {
            jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                            + "status, bank_code, bank_name, account_name) "
                            + "values (?, ?, ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                    number, currency, currency.equals("NGN") ? ngnMinor : 0, customer);
        }
        return number;
    }

    @Test
    void statementsAreSeparatePerCurrency() {
        String number = createDualAccount(5_000_000);
        ledger.post(SystemAccounts.FX_USD_POOL, number, Money.ofMinor(2_500, USD), "dual-" + UUID.randomUUID());
        ledger.post(number, SystemAccounts.FX_NGN_POOL, Money.ofMinor(1_000_000, NGN), "dual-" + UUID.randomUUID());

        StatementPage ngn = statements.statement(number, null, null, null, 0, 20);
        StatementPage usd = statements.statement(number, USD, null, null, null, 0, 20);

        assertThat(ngn.currency()).isEqualTo(NGN);
        assertThat(ngn.currentBalanceMinor()).isEqualTo(4_000_000);
        assertThat(ngn.items()).hasSize(1);
        assertThat(ngn.items().get(0).currency()).isEqualTo(NGN);
        assertThat(ngn.items().get(0).balanceAfterMinor()).isEqualTo(4_000_000);

        assertThat(usd.currency()).isEqualTo(USD);
        assertThat(usd.currentBalanceMinor()).isEqualTo(2_500);
        assertThat(usd.items()).hasSize(1);
        assertThat(usd.items().get(0).currency()).isEqualTo(USD);
        assertThat(usd.items().get(0).balanceAfterMinor()).isEqualTo(2_500);
    }
}
