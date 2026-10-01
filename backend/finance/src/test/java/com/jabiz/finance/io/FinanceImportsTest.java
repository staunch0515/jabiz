package com.jabiz.finance.io;

import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.gl.OpeningProcesses;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The opening file's own checks and its input to FIN_OPENING_POST (FIN-PC-002). */
class FinanceImportsTest {

    private static ImportRow row(int number, String date, String account, String debit, String credit) {
        Map<String, Object> values = new HashMap<>();
        values.put("date", LocalDate.parse(date));
        values.put("account", account);
        values.put("debit", debit == null ? null : new BigDecimal(debit));
        values.put("credit", credit == null ? null : new BigDecimal(credit));
        return new ImportRow(number, "row " + number, values, Set.of());
    }

    private static List<String> check(List<ImportRow> rows) {
        List<String> found = new ArrayList<>();
        FinanceImports.checkOpening(new ImportDefinition.FileContent<>(rows, Map.of(), null),
            new ImportDefinition.Issues() {
                @Override
                public void file(String code, String message, Map<String, Object> params) {
                    found.add("0:" + code + ":" + params.getOrDefault("difference", ""));
                }

                @Override
                public void row(ImportRow row, String field, String code, String message,
                    Map<String, Object> params) {
                    found.add(row.number() + ":" + code);
                }
            });
        return found;
    }

    @Test
    void aBalancedFileOfOneDatePasses() {
        assertThat(check(List.of(row(1, "2025-12-31", "1010", "100.00", null),
            row(2, "2025-12-31", "3000", null, "100.00")))).isEmpty();
    }

    @Test
    void theDifferenceIsReported() {
        assertThat(check(List.of(row(1, "2025-12-31", "1010", "100.00", null),
            row(2, "2025-12-31", "3000", null, "99.50"))))
            .containsExactly("0:" + JournalValidator.UNBALANCED + ":0.50");
    }

    @Test
    void everyLineHasOneSideAndTheEntryOneDate() {
        assertThat(check(List.of(row(1, "2025-12-31", "1010", "100.00", "100.00"),
            row(2, "2025-12-30", "3000", null, null), row(3, "2025-12-31", "1050", "-5.00", null))))
            .contains("1:" + JournalValidator.LINE_AMOUNT, "2:" + JournalValidator.LINE_AMOUNT,
                "3:" + JournalValidator.LINE_AMOUNT, "0:" + FinanceImports.OPENING_DATES + ":");
    }

    @Test
    void theWholeFileIsOneOpeningEntry() {
        OpeningProcesses.OpeningInput input = FinanceImports.opening(List.of(
            row(1, "2025-12-31", "1010", "250000.00", null), row(2, "2025-12-31", "3000", null, "250000.00")),
            new FinanceImports.OpeningParams(null));
        assertThat(input.postingDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(input.description()).isEqualTo(FinanceImports.OPENING_DESCRIPTION);
        assertThat(input.lines()).hasSize(2);
        assertThat(FinanceImports.opening(List.of(row(1, "2025-12-31", "1010", "1.00", null)),
            new FinanceImports.OpeningParams(" Legacy TB ")).description()).isEqualTo("Legacy TB");
    }
}
