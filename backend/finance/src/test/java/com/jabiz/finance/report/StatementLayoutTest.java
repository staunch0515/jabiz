package com.jabiz.finance.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks of a statement layout before it is published (FIN-RP-011; ROADMAP F9b). */
class StatementLayoutTest {

    private static StatementProcesses.RowInput row(String code, String kind, String accounts) {
        return new StatementProcesses.RowInput(code, code, kind, accounts, 1, false, false, null);
    }

    @Test
    void theSampleLayoutsAreValid() {
        for (StatementProcesses.Sample sample : StatementProcesses.SAMPLES) {
            assertThat(StatementProcesses.validate(sample.code(), sample.statement(), sample.rows()))
                .as(sample.code()).isEmpty();
        }
    }

    @Test
    void aLayoutWithMistakesIsNamedForEach() {
        List<String> problems = StatementProcesses.validate("bs", "BALANCE", List.of(
            row("ASSETS", "HEADING", "1000-1999"),
            row("CASH", "LINE", null),
            row("CASH", "LINE", "1000"),
            row("AR", "LINE", "1299-1200"),
            row("TAX", "LINE", "2200;2300"),
            row("X", "WHATEVER", "1"),
            new StatementProcesses.RowInput("SIGNED", "S", "TOTAL", "1000", 2, true, false, null),
            new StatementProcesses.RowInput("NOTED", "Net of {note}", "LINE", "1200", 1, false, false, null)));
        assertThat(problems).anyMatch(p -> p.contains("layout code"))
            .anyMatch(p -> p.contains("statement is one of"))
            .anyMatch(p -> p.contains("ASSETS: a heading has no accounts"))
            .anyMatch(p -> p.contains("CASH: accounts are needed"))
            .anyMatch(p -> p.contains("CASH appears twice"))
            .anyMatch(p -> p.contains("ends before it starts"))
            .anyMatch(p -> p.contains("'2200;2300'"))
            .anyMatch(p -> p.contains("X: the kind is one of"))
            .anyMatch(p -> p.contains("SIGNED: the sign is 1 or -1"))
            .anyMatch(p -> p.contains("SIGNED: only a line shows each account"))
            .anyMatch(p -> p.contains("NOTED: the label has {note} but no note accounts"));
        assertThat(StatementProcesses.validate("BS", "BALANCE_SHEET", List.of(row("A", "LINE", "1000-1299"),
            row("B", "LINE", "1200,1300"), row("T", "TOTAL", "1000-1999")))).singleElement()
            .satisfies(p -> assertThat(p).isEqualTo("B: accounts 1200 are on line A too"));
        assertThat(StatementProcesses.validate("BS", "BALANCE_SHEET", List.of())).singleElement()
            .satisfies(p -> assertThat(p).contains("1 to 200 rows"));
    }
}
