package com.academy.paybridge.transfer;

import com.academy.paybridge.transfer.service.FeeCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeeCalculatorTest {

    private final FeeCalculator calculator = new FeeCalculator(500_000, 5_000_000, 1_000, 2_500, 5_000, 750);

    @Test
    void tierOneCoversUpToFiveThousandNaira() {
        assertThat(calculator.forExternal(1).feeMinor()).isEqualTo(1_000);
        assertThat(calculator.forExternal(500_000).feeMinor()).isEqualTo(1_000);
    }

    @Test
    void tierTwoCoversUpToFiftyThousandNaira() {
        assertThat(calculator.forExternal(500_001).feeMinor()).isEqualTo(2_500);
        assertThat(calculator.forExternal(5_000_000).feeMinor()).isEqualTo(2_500);
    }

    @Test
    void tierThreeCoversEverythingAbove() {
        assertThat(calculator.forExternal(5_000_001).feeMinor()).isEqualTo(5_000);
    }

    @Test
    void taxIsSevenPointFivePercentOfTheFeeRoundedHalfUp() {
        assertThat(calculator.forExternal(100_000).taxMinor()).isEqualTo(75);      // 1,000 x 7.5%
        assertThat(calculator.forExternal(600_000).taxMinor()).isEqualTo(188);     // 2,500 x 7.5% = 187.5
        assertThat(calculator.forExternal(6_000_000).taxMinor()).isEqualTo(375);   // 5,000 x 7.5%
    }
}