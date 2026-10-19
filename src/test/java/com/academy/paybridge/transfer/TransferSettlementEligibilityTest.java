package com.academy.paybridge.transfer;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.transfer.domain.Transfer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The settlement rules on the Transfer entity itself, with no Spring and no database. */
class TransferSettlementEligibilityTest {

    private static Transfer external() {
        return new Transfer("trf-1", "key-1", "0000000001", "0123456780",
                100_000, Currency.NGN, "058", 1_000, 75);
    }

    private static Transfer internal() {
        return new Transfer("trf-2", "key-2", "0000000001", "0000000002",
                100_000, Currency.NGN, null, 0, 0);
    }

    @Test
    void successTimeIsRecordedWhenTheTransferSucceeds() {
        Transfer t = external();
        assertThat(t.getSucceededAt()).isNull();

        Instant before = Instant.now();
        t.markSuccess("provider-ref");

        assertThat(t.getSucceededAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void aSuccessfulExternalTransferIsEligibleOnceTheCutoffReachesItsSuccessTime() {
        Transfer t = external();
        t.markSuccess("provider-ref");

        assertThat(t.isSettlementEligible(Currency.NGN, Instant.now())).isTrue();
        assertThat(t.isSettlementEligible(Currency.NGN, t.getSucceededAt())).isTrue();
        assertThat(t.isSettlementEligible(Currency.NGN, t.getSucceededAt().minusSeconds(1))).isFalse();
    }

    @Test
    void pendingFailedInternalAndWrongCurrencyTransfersAreNotEligible() {
        Instant now = Instant.now();

        Transfer pending = external();
        assertThat(pending.isSettlementEligible(Currency.NGN, now)).isFalse();

        Transfer failed = external();
        failed.markFailed("rejected");
        assertThat(failed.isSettlementEligible(Currency.NGN, now)).isFalse();

        Transfer internal = internal();
        internal.markSuccess(null);
        assertThat(internal.isSettlementEligible(Currency.NGN, now)).isFalse();

        Transfer ngn = external();
        ngn.markSuccess("provider-ref");
        assertThat(ngn.isSettlementEligible(Currency.USD, now)).isFalse();
    }

    @Test
    void aTransferCanBeMarkedSettledOnlyOnce() {
        Transfer t = external();
        t.markSuccess("provider-ref");

        t.markSettled("stl-1");

        assertThat(t.getSettlementBatchReference()).isEqualTo("stl-1");
        assertThat(t.isSettlementEligible(Currency.NGN, Instant.now())).isFalse();
        assertThatThrownBy(() -> t.markSettled("stl-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stl-1");
        assertThat(t.getSettlementBatchReference()).isEqualTo("stl-1");
    }

    @Test
    void onlySuccessfulExternalTransfersCanBeMarkedSettled() {
        assertThatThrownBy(() -> external().markSettled("stl-1"))
                .isInstanceOf(IllegalStateException.class);

        Transfer failed = external();
        failed.markFailed("rejected");
        assertThatThrownBy(() -> failed.markSettled("stl-1"))
                .isInstanceOf(IllegalStateException.class);

        Transfer internal = internal();
        internal.markSuccess(null);
        assertThatThrownBy(() -> internal.markSettled("stl-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
