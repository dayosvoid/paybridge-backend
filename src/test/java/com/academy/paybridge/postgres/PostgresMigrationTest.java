package com.academy.paybridge.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the Flyway migrations on a real PostgreSQL, exactly as production does (db/migration + db/seed-dev).
 * H2 cannot prove this: constraint, foreign-key and CHECK handling differ.
 * Each test gets its own empty database on the shared server.
 */
@Tag("postgres")
class PostgresMigrationTest {

    private static final int LATEST = 24;

    private static Flyway flyway(String database, String target) {
        var config = Flyway.configure()
                .dataSource(PostgresDatabase.url(database), PostgresDatabase.USER, PostgresDatabase.PASSWORD)
                .locations("classpath:db/migration", "classpath:db/seed-dev");
        if (target != null) {
            config = config.target(target);
        }
        return config.load();
    }

    private static JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                PostgresDatabase.url(database), PostgresDatabase.USER, PostgresDatabase.PASSWORD));
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---------- from scratch ----------

    @Test
    void everyMigrationAppliesToAnEmptyDatabaseInOrder() {
        String db = PostgresDatabase.createDatabase("mig_full");

        flyway(db, null).migrate();

        JdbcTemplate jdbc = jdbc(db);
        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success and version is not null "
                        + "order by installed_rank", String.class);
        List<String> expected = IntStream.rangeClosed(1, LATEST).mapToObj(Integer::toString).toList();
        assertThat(applied).as("every version V1..V%d applied exactly once, in order", LATEST)
                .containsExactlyElementsOf(expected);
    }

    @Test
    void migratingAgainChangesNothing() {
        String db = PostgresDatabase.createDatabase("mig_again");
        flyway(db, null).migrate();
        JdbcTemplate jdbc = jdbc(db);
        long before = count(jdbc, "select count(*) from flyway_schema_history");

        flyway(db, null).migrate();
        flyway(db, null).validate();

        assertThat(count(jdbc, "select count(*) from flyway_schema_history")).isEqualTo(before);
    }

    @Test
    void theSystemAccountsAndDemoAccountsAreSeeded() {
        String db = PostgresDatabase.createDatabase("mig_seed");
        flyway(db, null).migrate();
        JdbcTemplate jdbc = jdbc(db);

        // settlement, FX pools, remittance clearing
        for (String system : new String[] {"0000000000", "0000000006", "0000000007", "0000000008"}) {
            assertThat(count(jdbc, "select count(*) from accounts where account_number = ?", system))
                    .as("system account %s", system).isEqualTo(1);
        }
        assertThat(count(jdbc, "select balance_minor from accounts where account_number = '0000000007' "
                + "and currency = 'USD'")).isEqualTo(100_000_000L);
        assertThat(count(jdbc, "select balance_minor from accounts where account_number = '0000000006' "
                + "and currency = 'NGN'")).isEqualTo(100_000_000_000L);

        // the demo customers got a USD balance under the same number
        for (String demo : new String[] {"0000000001", "0000000002"}) {
            assertThat(jdbc.queryForList("select currency from accounts where account_number = ? order by currency",
                    String.class, demo)).as("demo account %s", demo).containsExactly("NGN", "USD");
        }
    }

    // ---------- upgrading data that already exists ----------

    @Test
    void existingDataSurvivesTheDualCurrencyUpgrade() {
        String db = PostgresDatabase.createDatabase("mig_upgrade");
        flyway(db, "14").migrate();
        JdbcTemplate jdbc = jdbc(db);

        // data created while the app was still single-currency (schema as of V14)
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version) "
                + "values ('5550000001', 'NGN', 100000, 777, 0)");
        // a customer that already has a USD account of their own under another number
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version) "
                + "values ('5550000003', 'NGN', 5000, 778, 0)");
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version) "
                + "values ('5550000004', 'USD', 900, 778, 0)");
        jdbc.update("insert into ledger_entries (account_number, amount_minor, created_at, currency, reference, type) "
                + "values ('5550000001', 100000, now(), 'NGN', 'legacy-1', 'CREDIT')");
        jdbc.update("insert into transfers (amount_minor, created_at, currency, destination_account, "
                + "idempotency_key, reference, source_account, status, succeeded_at) "
                + "values (2500, now(), 'NGN', '0000000002', 'legacy-key', 'legacy-trf', '5550000001', 'SUCCESS', now())");

        flyway(db, null).migrate();

        // V16: the NGN-only customer gains a USD balance under the same number, with nothing in it
        assertThat(jdbc.queryForList("select currency from accounts where account_number = '5550000001' "
                + "order by currency", String.class)).containsExactly("NGN", "USD");
        assertThat(count(jdbc, "select balance_minor from accounts where account_number = '5550000001' "
                + "and currency = 'USD'")).isZero();
        assertThat(count(jdbc, "select balance_minor from accounts where account_number = '5550000001' "
                + "and currency = 'NGN'")).isEqualTo(100_000);
        // a customer who already had a USD account is left alone; system accounts get no USD balance
        assertThat(count(jdbc, "select count(*) from accounts where account_number = '5550000003'")).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from accounts where customer_id = 778")).isEqualTo(2);
        assertThat(count(jdbc, "select count(*) from accounts where account_number = '0000000000'")).isEqualTo(1);

        // the old rows still point at their account through the new composite keys
        assertThat(count(jdbc, "select count(*) from ledger_entries where reference = 'legacy-1'")).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from transfers where reference = 'legacy-trf'")).isEqualTo(1);
    }

    // ---------- the constraints really bite on Postgres ----------

    @Test
    void theConstraintsRejectBadData() {
        String db = PostgresDatabase.createDatabase("mig_rules");
        flyway(db, null).migrate();
        JdbcTemplate jdbc = jdbc(db);
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version) "
                + "values ('5551000001', 'NGN', 1000, 901, 0), ('5551000001', 'USD', 0, 901, 0)");

        // a ledger entry must point at an existing (account number, currency) balance
        jdbc.update("insert into ledger_entries (account_number, amount_minor, created_at, currency, reference, type) "
                + "values ('5551000001', 10, now(), 'USD', 'ok-1', 'CREDIT')");
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (account_number, amount_minor, created_at, "
                + "currency, reference, type) values ('5559999999', 10, now(), 'NGN', 'bad-1', 'CREDIT')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (account_number, amount_minor, created_at, "
                + "currency, reference, type) values ('0000000000', 10, now(), 'USD', 'bad-2', 'CREDIT')"))
                .as("the settlement account has no USD balance")
                .isInstanceOf(DataIntegrityViolationException.class);

        // one balance per (account number, currency)
        assertThatThrownBy(() -> jdbc.update("insert into accounts (account_number, currency, balance_minor, "
                + "customer_id, version) values ('5551000001', 'NGN', 0, 902, 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // a balance can never go negative; a posting can never be zero; a reference posts each side once
        assertThatThrownBy(() -> jdbc.update("update accounts set balance_minor = -1 where account_number = '5551000001' "
                + "and currency = 'NGN'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (account_number, amount_minor, created_at, "
                + "currency, reference, type) values ('5551000001', 0, now(), 'NGN', 'bad-3', 'CREDIT')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (account_number, amount_minor, created_at, "
                + "currency, reference, type) values ('5551000001', 10, now(), 'USD', 'ok-1', 'CREDIT')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thePhase5StatusesAreAcceptedAndUnknownOnesAreNot() {
        String db = PostgresDatabase.createDatabase("mig_status");
        flyway(db, null).migrate();
        JdbcTemplate jdbc = jdbc(db);

        for (String status : new String[] {"RECEIVED", "CREDITED", "UNMATCHED", "FAILED", "REVERSED", "REVERSAL_FAILED"}) {
            jdbc.update("insert into inbound_payments (reference, provider_reference, account_number, currency, "
                    + "amount_minor, status, received_at) values (?, ?, '0000000001', 'USD', 100, ?, now())",
                    "rin-" + status, "prov-" + status, status);
        }
        assertThatThrownBy(() -> jdbc.update("insert into inbound_payments (reference, provider_reference, "
                + "account_number, currency, amount_minor, status, received_at) "
                + "values ('rin-bogus', 'prov-bogus', '0000000001', 'USD', 100, 'BOGUS', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into provider_quotes (quote_id, provider_id, source_account, source_amount_minor, fee_minor, "
                + "destination_currency, destination_amount_minor, fx_rate, eta_minutes, created_at, expires_at) "
                + "values ('pq-1', 'swiftsend', '0000000001', 1000, 10, 'USD', 1, 0.00064, 5, now(), now())");
        jdbc.update("insert into provider_quotes (quote_id, provider_id, source_account, source_amount_minor, fee_minor, "
                + "destination_currency, destination_amount_minor, fx_rate, eta_minutes, created_at, expires_at) "
                + "values ('pq-2', 'swiftsend', '0000000001', 1000, 10, 'USD', 1, 0.00064, 5, now(), now())");
        String insert = "insert into outbound_remittances (reference, idempotency_key, quote_id, provider_id, "
                + "source_account, source_amount_minor, fee_minor, destination_currency, destination_amount_minor, "
                + "fx_rate, beneficiary_name, beneficiary_account, status, created_at) "
                + "values (?, ?, ?, 'swiftsend', '0000000001', 1000, 10, 'USD', 1, 0.00064, 'Jane', '1234567890', ?, now())";
        jdbc.update(insert, "out-1", "k-1", "pq-1", "RETURNED");
        assertThatThrownBy(() -> jdbc.update(insert, "out-2", "k-2", "pq-2", "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // a quote can be sent once: a second payment on the same quote is refused by the database itself
        assertThatThrownBy(() -> jdbc.update(insert, "out-3", "k-3", "pq-1", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
