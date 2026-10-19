package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceRequest;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Own in-memory database so other test classes cannot disturb the FX pool balances.
 * Rate is the default demo rate 0.00065: NGN 100,000.00 (10,000,000 minor) becomes USD 65.00 (6,500 minor).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paybridge-remittance;LOCK_TIMEOUT=10000")
class RemittanceServiceTest {

    private static final String NGN_POOL = "0000000006";
    private static final String USD_POOL = "0000000007";
    private static final long SOURCE = 10_000_000;
    private static final long DESTINATION = 6_500;

    @Autowired RemittanceApi remittances;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String createAccount(String currency, long balanceMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version, "
                        + "status, bank_code, bank_name, account_name) values (?, ?, ?, ?, 0, 'ACTIVE', '999', 'PayBridge MFB', 'Test')",
                number, currency, balanceMinor, ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L));
        return number;
    }

    private long balance(String number) {
        return accounts.findByNumber(number).orElseThrow().balanceMinor();
    }

    private RemittanceRequest request(String from, String to, long amount) {
        return new RemittanceRequest(from, to, amount, NGN, USD);
    }

    private RemittanceView send(RemittanceRequest r) {
        return remittances.initiate(r, UUID.randomUUID().toString());
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long entriesFor(RemittanceView v) {
        return jdbc.queryForObject("select count(*) from ledger_entries where reference like ?",
                Long.class, v.reference() + "-%");
    }

    // ---------- tests ----------

    @Test
    void anNgnToUsdRemittanceMovesBothCurrenciesThroughTheFxPools() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);
        long ngnPoolBefore = balance(NGN_POOL);
        long usdPoolBefore = balance(USD_POOL);

        RemittanceView r = send(request(sender, beneficiary, SOURCE));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.sourceAmountMinor()).isEqualTo(SOURCE);
        assertThat(r.destinationAmountMinor()).isEqualTo(DESTINATION);
        assertThat(r.rate()).isEqualByComparingTo(new BigDecimal("0.00065"));
        assertThat(r.rateType()).isEqualTo("DEMO_CONFIGURED");

        // NGN leg: sender -> NGN pool
        assertThat(balance(sender)).isEqualTo(20_000_000 - SOURCE);
        assertThat(balance(NGN_POOL)).isEqualTo(ngnPoolBefore + SOURCE);
        // USD leg: USD pool -> beneficiary
        assertThat(balance(USD_POOL)).isEqualTo(usdPoolBefore - DESTINATION);
        assertThat(balance(beneficiary)).isEqualTo(DESTINATION);

        // explicit accounting: one DEBIT and one CREDIT per leg
        assertThat(entriesFor(r)).isEqualTo(4);
    }

    @Test
    void theRemittanceRecordKeepsEverythingNeededToExplainIt() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);

        RemittanceView r = send(request(sender, beneficiary, SOURCE));

        assertThat(jdbc.queryForMap("select source_account, beneficiary_account, source_currency, "
                + "destination_currency, source_amount_minor, destination_amount_minor, rate_type, status "
                + "from remittances where reference = ?", r.reference()))
                .containsEntry("SOURCE_ACCOUNT", sender)
                .containsEntry("BENEFICIARY_ACCOUNT", beneficiary)
                .containsEntry("SOURCE_CURRENCY", "NGN")
                .containsEntry("DESTINATION_CURRENCY", "USD")
                .containsEntry("SOURCE_AMOUNT_MINOR", SOURCE)
                .containsEntry("DESTINATION_AMOUNT_MINOR", DESTINATION)
                .containsEntry("RATE_TYPE", "DEMO_CONFIGURED")
                .containsEntry("STATUS", "COMPLETED");
        assertThat(r.createdAt()).isNotNull();
        assertThat(remittances.getByReference(r.reference()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void insufficientBalanceIsRecordedAsFailedAndNothingMoves() {
        String sender = createAccount("NGN", 5_000_000); // less than the 10,000,000 needed
        String beneficiary = createAccount("USD", 0);
        long ngnPoolBefore = balance(NGN_POOL);
        long usdPoolBefore = balance(USD_POOL);

        RemittanceView r = send(request(sender, beneficiary, SOURCE));

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.failureReason()).isNotBlank();
        assertThat(balance(sender)).isEqualTo(5_000_000);
        assertThat(balance(beneficiary)).isZero();
        assertThat(balance(NGN_POOL)).isEqualTo(ngnPoolBefore);
        assertThat(balance(USD_POOL)).isEqualTo(usdPoolBefore);
        assertThat(entriesFor(r)).isZero();
    }

    @Test
    void ifTheUsdLegFailsTheNgnLegIsRolledBackToo() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);
        long ngnPoolBefore = balance(NGN_POOL);
        long usdPoolBefore = balance(USD_POOL);

        // The pool cannot cover the payout, so the second leg fails after the first has been applied.
        jdbc.update("update accounts set balance_minor = 100 where account_number = ?", USD_POOL);
        RemittanceView r;
        try {
            r = send(request(sender, beneficiary, SOURCE));
        } finally {
            jdbc.update("update accounts set balance_minor = ? where account_number = ?", usdPoolBefore, USD_POOL);
        }

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(balance(sender)).isEqualTo(20_000_000);        // NGN leg undone
        assertThat(balance(NGN_POOL)).isEqualTo(ngnPoolBefore);   // NGN leg undone
        assertThat(balance(beneficiary)).isZero();
        assertThat(entriesFor(r)).isZero();
    }

    @Test
    void theSameKeyAndRequestCreatesOnlyOneRemittance() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);
        String key = UUID.randomUUID().toString();
        RemittanceRequest request = request(sender, beneficiary, SOURCE);

        RemittanceView first = remittances.initiate(request, key);
        long remittancesAfterFirst = count("remittances");
        RemittanceView second = remittances.initiate(request, key);

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(count("remittances")).isEqualTo(remittancesAfterFirst);
        assertThat(balance(sender)).isEqualTo(20_000_000 - SOURCE);   // charged once
        assertThat(balance(beneficiary)).isEqualTo(DESTINATION);       // paid once
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefused() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);
        String key = UUID.randomUUID().toString();
        remittances.initiate(request(sender, beneficiary, SOURCE), key);

        assertThatThrownBy(() -> remittances.initiate(request(sender, beneficiary, SOURCE + 1), key))
                .isInstanceOf(IllegalStateException.class);
        assertThat(balance(sender)).isEqualTo(20_000_000 - SOURCE);
    }

    @Test
    void theDatabaseRefusesASecondRemittanceWithTheSameKey() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);
        String key = UUID.randomUUID().toString();
        remittances.initiate(request(sender, beneficiary, SOURCE), key);

        assertThatThrownBy(() -> jdbc.update(
                "insert into remittances (reference, idempotency_key, source_account, beneficiary_account, "
                        + "source_currency, destination_currency, source_amount_minor, destination_amount_minor, "
                        + "fx_rate, rate_type, status, created_at) "
                        + "values (?, ?, ?, ?, 'NGN', 'USD', 1000, 1, 0.00065, 'DEMO_CONFIGURED', 'PENDING', current_timestamp)",
                "rem-" + UUID.randomUUID(), key, sender, beneficiary))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void unsupportedCurrenciesAreRejectedAndNothingIsStored() {
        String usdAccount = createAccount("USD", 1_000_000);
        String ngnAccount = createAccount("NGN", 0);
        long before = count("remittances");

        assertThatThrownBy(() -> send(new RemittanceRequest(usdAccount, ngnAccount, 10_000, USD, NGN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(new RemittanceRequest(ngnAccount, ngnAccount, 10_000, NGN, NGN)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(count("remittances")).isEqualTo(before);
    }

    @Test
    void zeroNegativeAndTooSmallAmountsAreRejected() {
        String sender = createAccount("NGN", 20_000_000);
        String beneficiary = createAccount("USD", 0);
        long before = count("remittances");

        assertThatThrownBy(() -> send(request(sender, beneficiary, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(request(sender, beneficiary, -1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(request(sender, beneficiary, 1_000))).isInstanceOf(IllegalArgumentException.class);

        assertThat(count("remittances")).isEqualTo(before);
        assertThat(balance(sender)).isEqualTo(20_000_000);
    }

    @Test
    void accountsMustExistAndBeInTheRightCurrency() {
        String ngnSender = createAccount("NGN", 20_000_000);
        String usdBeneficiary = createAccount("USD", 0);
        String ngnBeneficiary = createAccount("NGN", 0);
        long before = count("remittances");

        // beneficiary is an NGN account, not USD
        assertThatThrownBy(() -> send(request(ngnSender, ngnBeneficiary, SOURCE)))
                .isInstanceOf(IllegalArgumentException.class);
        // sender is a USD account, not NGN
        assertThatThrownBy(() -> send(request(usdBeneficiary, usdBeneficiary, SOURCE)))
                .isInstanceOf(IllegalArgumentException.class);
        // unknown accounts
        assertThatThrownBy(() -> send(request("9999999999", usdBeneficiary, SOURCE)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> send(request(ngnSender, "9999999998", SOURCE)))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("remittances")).isEqualTo(before);
        assertThat(balance(ngnSender)).isEqualTo(20_000_000);
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        assertThatThrownBy(() -> remittances.initiate(request("0000000001", "0000000002", SOURCE), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> remittances.initiate(request("0000000001", "0000000002", SOURCE), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownReferenceIsNotFound() {
        assertThatThrownBy(() -> remittances.getByReference("rem-does-not-exist"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
