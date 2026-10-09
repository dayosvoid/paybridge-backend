package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentTransferTest {

    @Autowired TransferApi transfers;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    private String createAccount(long balanceMinor) {
        String number = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        long customerId = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        jdbc.update("insert into accounts (account_number, currency, balance_minor, customer_id, version) "
                + "values (?, 'NGN', ?, ?, 0)", number, balanceMinor, customerId);
        return number;
    }

    private long balance(String number) {
        return accounts.findByNumber(number).orElseThrow().balanceMinor();
    }

    private TransferView send(String from, String to, long amountMinor) {
        return transfers.initiate(new TransferRequest(from, to, amountMinor, NGN), UUID.randomUUID().toString());
    }

    /** Releases all jobs at the same moment so they genuinely overlap. */
    private List<TransferView> runTogether(List<Callable<TransferView>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferView>> futures = new ArrayList<>();
        for (Callable<TransferView> job : jobs) {
            futures.add(pool.submit(() -> { start.await(); return job.call(); }));
        }
        start.countDown();
        List<TransferView> results = new ArrayList<>();
        for (Future<TransferView> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    @Test
    void twentyTransfersFromOneAccountNeverOverdraw() throws Exception {
        String source = createAccount(100_000);     // N1,000.00
        String dest = createAccount(0);

        List<Callable<TransferView>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> send(source, dest, 10_000));   // 20 x N100.00 against N1,000.00
        }
        List<TransferView> results = runTogether(jobs);

        long succeeded = results.stream().filter(r -> r.status().equals("SUCCESS")).count();
        assertThat(succeeded).isEqualTo(10);                            // only 10 can be afforded
        assertThat(balance(source)).isEqualTo(0);                       // never negative
        assertThat(balance(dest)).isEqualTo(100_000);                   // money conserved
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        String a = createAccount(100_000);
        String b = createAccount(100_000);

        List<Callable<TransferView>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> send(a, b, 1_000));
            jobs.add(() -> send(b, a, 1_000));
        }
        List<TransferView> results = runTogether(jobs);

        assertThat(results).allMatch(r -> r.status().equals("SUCCESS"));   // a deadlock would show as FAILED
        assertThat(balance(a) + balance(b)).isEqualTo(200_000);
    }
}
