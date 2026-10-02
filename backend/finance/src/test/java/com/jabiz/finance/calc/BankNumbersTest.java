package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Routing and account numbers of US bank accounts (FIN-AP-003, FIN-AP-013). */
class BankNumbersTest {

    @Test
    void routingNumbersCarryACheckDigit() {
        // Routing numbers of real banks; a changed last digit breaks the check.
        assertThat(BankNumbers.validRouting("111000025")).isTrue();
        assertThat(BankNumbers.validRouting("021000021")).isTrue();
        assertThat(BankNumbers.validRouting("091000019")).isTrue();
        assertThat(BankNumbers.validRouting("111000026")).isFalse();
        assertThat(BankNumbers.validRouting("11100002")).isFalse();
        assertThat(BankNumbers.validRouting("11100002A")).isFalse();
        assertThat(BankNumbers.validRouting(null)).isFalse();
    }

    @Test
    void accountNumbersAreFourToSeventeenDigits() {
        assertThat(BankNumbers.validAccount("000123456789")).isTrue();
        assertThat(BankNumbers.validAccount("1234")).isTrue();
        assertThat(BankNumbers.validAccount("12345678901234567")).isTrue();
        assertThat(BankNumbers.validAccount("123")).isFalse();
        assertThat(BankNumbers.validAccount("123456789012345678")).isFalse();
        assertThat(BankNumbers.validAccount("****6789")).isFalse();
        assertThat(BankNumbers.validAccount(null)).isFalse();
        assertThat(BankNumbers.compact("0001 2345-6789")).isEqualTo("000123456789");
    }

    @Property
    void exactlyOneCheckDigitCompletesEightDigits(@ForAll @IntRange(min = 0, max = 99_999_999) int prefix) {
        String eight = String.format("%08d", prefix);
        long valid = java.util.stream.IntStream.rangeClosed(0, 9)
            .filter(digit -> BankNumbers.validRouting(eight + digit)).count();
        assertThat(valid).isEqualTo(1);
    }
}
