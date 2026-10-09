package com.academy.paybridge.shared.money;

import org.junit.jupiter.api.Test;

import static com.academy.paybridge.shared.money.Currency.NGN;
import static com.academy.paybridge.shared.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesMajorUnitsIntoMinorUnits() {
        assertThat(Money.of("1500.50", NGN).minorUnits()).isEqualTo(150050L);
        assertThat(Money.of("100", NGN).minorUnits()).isEqualTo(10000L);
    }

    @Test
    void addsSameCurrency() {
        Money result = Money.of("100.00", NGN).add(Money.of("50.00", NGN));
        assertThat(result).isEqualTo(Money.of("150.00", NGN));
    }

    @Test
    void subtractCanGoNegative() {
        Money result = Money.of("10", NGN).subtract(Money.of("25", NGN));
        assertThat(result.isNegative()).isTrue();
        assertThat(result.minorUnits()).isEqualTo(-1500L);
    }

    @Test
    void differentCurrenciesThrow() {
        assertThatThrownBy(() -> Money.of("1", NGN).add(Money.of("1", USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void roundsHalfEvenWhenParsing() {
        assertThat(Money.of("10.005", NGN).minorUnits()).isEqualTo(1000L); // rounds down to even
        assertThat(Money.of("10.015", NGN).minorUnits()).isEqualTo(1002L);
    }

    @Test
    void isGreaterThanWorks() {
        assertThat(Money.of("20", NGN).isGreaterThan(Money.of("10", NGN))).isTrue();
        assertThat(Money.of("10", NGN).isGreaterThan(Money.of("10", NGN))).isFalse();
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        Money max = Money.ofMinor(Long.MAX_VALUE, NGN);
        assertThatThrownBy(() -> max.add(Money.ofMinor(1, NGN)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void toStringShowsMajorUnits() {
        assertThat(Money.of("1500.5", NGN)).hasToString("NGN 1500.50");
    }

}
