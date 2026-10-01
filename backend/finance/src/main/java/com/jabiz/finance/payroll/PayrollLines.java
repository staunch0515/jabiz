package com.jabiz.finance.payroll;

import com.jabiz.finance.calc.Money;
import com.jabiz.finance.gl.JournalValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A payroll provider's results as journal lines (FIN-DI-004): every provider code is read through its mapping to an
 * account, a side and, if the mapping names one, a department (otherwise the provider line's department); amounts of
 * the same account, side and department add up into one line, a negative amount counting on the other side. Codes
 * without a mapping are reported, not guessed. Pure computation, for the import process and its tests.
 */
public final class PayrollLines {

    /** One line of the provider's file. */
    public record ProviderLine(String code, String department, BigDecimal amount) {}

    /** @param side {@code DEBIT} or {@code CREDIT} */
    public record Mapping(String code, String accountCode, String side, String department) {}

    /** @param unmapped the provider codes no mapping covers, in the order they first appear */
    public record Result(List<JournalValidator.Line> lines, List<String> unmapped) {}

    /** Longest memo of a line: the codes it sums. */
    static final int MEMO = 200;

    private PayrollLines() {}

    public static Result lines(List<ProviderLine> provider, Map<String, Mapping> mappings) {
        Map<List<String>, BigDecimal> net = new LinkedHashMap<>();
        Map<List<String>, TreeSet<String>> codes = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (ProviderLine line : provider) {
            String code = line.code() == null ? "" : line.code().trim();
            Mapping mapping = mappings.get(code);
            if (mapping == null) {
                if (!unmapped.contains(code)) {
                    unmapped.add(code);
                }
                continue;
            }
            String department = blankToNull(mapping.department() != null ? mapping.department() : line.department());
            List<String> key = java.util.Arrays.asList(mapping.accountCode(), department);
            BigDecimal signed = "CREDIT".equals(mapping.side()) ? line.amount().negate() : line.amount();
            net.merge(key, signed, BigDecimal::add);
            codes.computeIfAbsent(key, k -> new TreeSet<>()).add(code);
        }
        List<JournalValidator.Line> lines = new ArrayList<>();
        net.forEach((key, amount) -> {
            if (amount.signum() == 0) {
                return;
            }
            BigDecimal cents = Money.usd(amount.abs());
            String memo = String.join(", ", codes.get(key));
            lines.add(new JournalValidator.Line(key.get(0), amount.signum() > 0 ? cents : null,
                amount.signum() < 0 ? cents : null, memo.length() > MEMO ? memo.substring(0, MEMO) : memo,
                key.get(1), null));
        });
        // Debits before credits, each by account: the order a payroll summary is read in.
        lines.sort(Comparator.comparing((JournalValidator.Line l) -> l.debit() == null)
            .thenComparing(JournalValidator.Line::accountCode)
            .thenComparing(l -> Objects.toString(l.department(), "")));
        return new Result(List.copyOf(lines), List.copyOf(unmapped));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
