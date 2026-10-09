package com.academy.paybridge.transfer;

import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferPage;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TransferHistoryTest {

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

    @Test
    void listsTransfersNewestFirstForSenderAndReceiver() {
        String sender = createAccount(5_000_000);
        String receiver = createAccount(0);
        TransferView internal = send(sender, receiver, 100_000, null);
        TransferView failed = send(sender, "0123456789", 100_000, "058");
        TransferView external = send(sender, "0123456781", 100_000, "058");

        TransferPage senderPage = transfers.list(sender, null, 0, 20);
        assertThat(senderPage.totalItems()).isEqualTo(3);
        assertThat(senderPage.items()).extracting(TransferView::reference)
                .containsExactly(external.reference(), failed.reference(), internal.reference());

        assertThat(transfers.list(receiver, null, 0, 20).items()).extracting(TransferView::reference)
                .containsExactly(internal.reference());
    }

    @Test
    void filtersByStatusAndPages() {
        String sender = createAccount(5_000_000);
        TransferView ok1 = send(sender, "0123456781", 100_000, "058");
        TransferView failed = send(sender, "0123456789", 100_000, "058");
        TransferView ok2 = send(sender, "0123456781", 100_000, "058");

        assertThat(transfers.list(sender, "failed", 0, 20).items()).extracting(TransferView::reference)
                .containsExactly(failed.reference());

        TransferPage first = transfers.list(sender, null, 0, 2);
        TransferPage second = transfers.list(sender, null, 1, 2);
        assertThat(first.items()).extracting(TransferView::reference).containsExactly(ok2.reference(), failed.reference());
        assertThat(first.hasNext()).isTrue();
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.items()).extracting(TransferView::reference).containsExactly(ok1.reference());
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void rejectsUnknownAccountAndBadInput() {
        String account = createAccount(0);

        assertThatThrownBy(() -> transfers.list("9999999999", null, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> transfers.list(account, "SIDEWAYS", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.list(account, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.list(account, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}