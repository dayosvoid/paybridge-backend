package com.academy.paybridge.fx;

import com.academy.paybridge.fx.api.FxQuote;
import com.academy.paybridge.fx.service.FxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The conversion rules, with no Spring and no database. NGN and USD both have two decimals. */
class FxServiceTest {

    private final FxService fx = new FxService("0.00065", "1500");

    @Test
    void convertsNgnToUsdAtTheConfiguredRate() {
        // NGN 100,000.00 = 10,000,000 minor; at 0.00065 that is USD 65.00 = 6,500 minor
        FxQuote q = fx.quote(NGN, USD, 10_000_000);

        assertThat(q.sourceCurrency()).isEqualTo(NGN);
        assertThat(q.destinationCurrency()).isEqualTo(USD);
        assertThat(q.sourceAmountMinor()).isEqualTo(10_000_000);
        assertThat(q.destinationAmountMinor()).isEqualTo(6_500);
        assertThat(q.rate()).isEqualByComparingTo(new BigDecimal("0.00065"));
        assertThat(q.rateType()).isEqualTo("DEMO_CONFIGURED");
    }

    @Test
    void roundsTheDestinationAmountDown() {
        // 12,345 minor * 0.00065 = 8.02425 -> 8
        assertThat(fx.quote(NGN, USD, 12_345).destinationAmountMinor()).isEqualTo(8);
        // 1,999 * 0.00065 = 1.29935 -> 1, never 2
        assertThat(fx.quote(NGN, USD, 1_999).destinationAmountMinor()).isEqualTo(1);
    }

    @Test
    void anAmountThatConvertsToLessThanOneMinorUnitIsRejected() {
        // 1,000 * 0.00065 = 0.65 -> 0
        assertThatThrownBy(() -> fx.quote(NGN, USD, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void unsupportedPairsAreRejected() {
        assertThatThrownBy(() -> fx.quote(NGN, NGN, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fx.quote(USD, USD, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fx.quote(null, USD, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fx.quote(NGN, null, 10_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsUsdToNgnAtItsOwnConfiguredRate() {
        // USD 100.00 = 10,000 minor; at 1500 that is NGN 150,000.00 = 15,000,000 minor
        FxQuote q = fx.quote(USD, NGN, 10_000);

        assertThat(q.sourceCurrency()).isEqualTo(USD);
        assertThat(q.destinationCurrency()).isEqualTo(NGN);
        assertThat(q.destinationAmountMinor()).isEqualTo(15_000_000);
        assertThat(q.rate()).isEqualByComparingTo(new BigDecimal("1500"));
    }

    @Test
    void theTwoDirectionsHaveIndependentRates() {
        // 1 / 0.00065 is about 1538.46, so PayBridge pays 1500: the gap is its margin
        FxService withMargin = new FxService("0.00065", "1500");
        long usdBought = withMargin.quote(NGN, USD, 1_500_000).destinationAmountMinor();
        long ngnBack = withMargin.quote(USD, NGN, usdBought).destinationAmountMinor();
        assertThat(ngnBack).isLessThanOrEqualTo(1_500_000);
    }

    @Test
    void aBadUsdToNgnRateAlsoStopsTheServiceFromStarting() {
        assertThatThrownBy(() -> new FxService("0.00065", "abc")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FxService("0.00065", "0")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() {
        assertThatThrownBy(() -> fx.quote(NGN, USD, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fx.quote(NGN, USD, -5_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBadConfiguredRateStopsTheServiceFromStarting() {
        assertThatThrownBy(() -> new FxService("abc", "1500")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FxService("0", "1500")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FxService("-0.5", "1500")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aHugeAmountDoesNotOverflow() {
        FxQuote q = new FxService("1", "1").quote(NGN, USD, Long.MAX_VALUE);
        assertThat(q.destinationAmountMinor()).isEqualTo(Long.MAX_VALUE);
    }
}
