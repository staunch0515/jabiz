package com.jabiz.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckFilesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 2, 5);

    @Test
    void amountsAreWrittenOutAsOnACheck() {
        assertThat(CheckFiles.words(new BigDecimal("1200.00"))).isEqualTo("One thousand two hundred and 00/100");
        assertThat(CheckFiles.words(new BigDecimal("980.5"))).isEqualTo("Nine hundred eighty and 50/100");
        assertThat(CheckFiles.words(new BigDecimal("0.07"))).isEqualTo("Zero and 07/100");
        assertThat(CheckFiles.words(new BigDecimal("28300.00"))).isEqualTo("Twenty-eight thousand three hundred and 00/100");
        assertThat(CheckFiles.words(new BigDecimal("1000001.01"))).isEqualTo("One million one and 01/100");
        assertThat(CheckFiles.words(new BigDecimal("219019.99")))
            .isEqualTo("Two hundred nineteen thousand nineteen and 99/100");
    }

    @Test
    void thePrintFileHasAChecksRow() {
        String file = CheckFiles.printFile(List.of(new CheckFiles.Check("10001", DAY, "CloudStack, Inc.",
            new BigDecimal("1200.00"), "PMT-0005", "PAY-RUN-04, 1 bill", false)));
        assertThat(file.lines().toList()).containsExactly(
            "check_number,check_date,payee,amount,amount_in_words,payment_no,memo",
            "10001,2026-02-05,\"CloudStack, Inc.\",1200.00,One thousand two hundred and 00/100,PMT-0005,"
                + "\"PAY-RUN-04, 1 bill\"");
    }

    @Test
    void positivePayMarksVoidedChecks() {
        String file = CheckFiles.positivePay("000123456789", List.of(
            new CheckFiles.Check("10001", DAY, "Say \"Hi\" Ltd", new BigDecimal("1.50"), "PMT-1", null, true),
            new CheckFiles.Check("10002", DAY, "=cmd()", new BigDecimal("2"), "PMT-2", null, false)));
        assertThat(file.lines().skip(1).toList()).containsExactly(
            "000123456789,10001,2026-02-05,1.50,\"Say \"\"Hi\"\" Ltd\",V",
            "000123456789,10002,2026-02-05,2.00,'=cmd(),I");
    }

    @Test
    void fieldsAreQuotedOnlyWhenNeeded() {
        assertThat(CheckFiles.field(null)).isEmpty();
        assertThat(CheckFiles.field("plain")).isEqualTo("plain");
        assertThat(CheckFiles.field("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(CheckFiles.field("+1")).isEqualTo("'+1");
        assertThat(CheckFiles.field("\t=1")).isEqualTo("'\t=1");
    }
}
