package com.jabiz.finance.gl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The opening entry's lines as posted: legacy codes read through the decisions, amounts in cents (FIN-DI-003). */
class OpeningLinesTest {

    @Test
    void legacyCodesAreReadAsDecidedAndAmountsKeptInCents() {
        OpeningProcesses.OpeningInput input = new OpeningProcesses.OpeningInput(LocalDate.of(2025, 12, 31), "Opening",
            List.of(new JournalProcesses.LineInput(" 1199 ", new BigDecimal("86500"), null, null, null, null),
                new JournalProcesses.LineInput("3000", null, new BigDecimal("86500.001"), "memo", " ADMIN ", "")));
        List<JournalValidator.Line> lines = OpeningProcesses.lines(input, Map.of("1199", "1200"));
        assertThat(lines.get(0).accountCode()).isEqualTo("1200");
        assertThat(lines.get(0).debit()).isEqualTo(new BigDecimal("86500.00"));
        // More decimals than cents stay as given: the line check refuses them rather than rounding.
        assertThat(lines.get(1).credit()).isEqualTo(new BigDecimal("86500.001"));
        assertThat(lines.get(1).department()).isEqualTo("ADMIN");
        assertThat(lines.get(1).location()).isNull();
        assertThat(JournalValidator.checkLines(lines)).extracting(v -> v.ruleCode())
            .containsExactly(JournalValidator.LINE_AMOUNT);
    }
}
