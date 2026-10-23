
package com.academy.paybridge.demo;

import com.academy.paybridge.auth.service.AuthService;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoCustomerSeeder implements ApplicationRunner {

    private static final String EMAIL = "demo@paybridge.local";
    private static final String PASSWORD = "DemoPayBridge2026!";
    private static final String FULL_NAME = "PayBridge Demo Customer";

    // Dedicated demo-only reserves. Do not use the operational FX pools.
    private static final String NGN_RESERVE = "0000000009";
    private static final String USD_RESERVE = "0000000010";

    private final AuthService auth;
    private final LedgerApi ledger;
    private final JdbcTemplate jdbc;

    public DemoCustomerSeeder(
            AuthService auth,
            LedgerApi ledger,
            JdbcTemplate jdbc
    ) {
        this.auth = auth;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // A successful previous run must never fund the user again.
        if (auth.emailExists(EMAIL)) {
            System.out.println(
                    "PayBridge demo customer already exists; skipping demo funding."
            );
            return;
        }

        // Create these reserves only if they do not already exist.
        // Their initial balances are synthetic demo liquidity.
        createReserve(
                NGN_RESERVE, Currency.NGN, 1_000_000_000L, -7L
        );
        createReserve(
                USD_RESERVE, Currency.USD, 10_000_000L, -8L
        );

        // Reuse the real registration flow: password hashing and both
        // currency accounts are handled by AuthService and AccountService.
        var user = auth.register(EMAIL, PASSWORD, FULL_NAME);
        String customerAccount = user.accountNumber();

        // Money.of takes major currency units, not minor units.
        // NGN 100,000.00 = 10,000,000 minor units.
        // USD   1,000.00 =    100,000 minor units.
        ledger.post(
                NGN_RESERVE,
                customerAccount,
                Money.of("100000.00", Currency.NGN),
                "demo-funding-ngn-v1"
        );

        ledger.post(
                USD_RESERVE,
                customerAccount,
                Money.of("1000.00", Currency.USD),
                "demo-funding-usd-v1"
        );

        System.out.println("PayBridge demo customer created.");
        System.out.println("Login email: " + EMAIL);
        System.out.println("NGN balance: 100000.00");
        System.out.println("USD balance: 1000.00");
    }

    private void createReserve(
            String accountNumber,
            Currency currency,
            long openingBalanceMinor,
            long customerId
    ) {
        jdbc.update("""
                INSERT INTO accounts (
                    account_number,
                    currency,
                    balance_minor,
                    customer_id,
                    version,
                    status,
                    bank_code,
                    bank_name,
                    account_name
                )
                VALUES (?, ?, ?, ?, 0, 'ACTIVE', '999',
                        'PayBridge Demo', 'Demo Funding Reserve')
                ON CONFLICT (account_number, currency) DO NOTHING
                """,
                accountNumber,
                currency.name(),
                openingBalanceMinor,
                customerId
        );
    }
}
