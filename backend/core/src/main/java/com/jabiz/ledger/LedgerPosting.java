package com.jabiz.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rules of a double-entry transaction (docs/design/11-ledger-events-jobs.md section 1): at least two entries, every
 * amount positive and within the ledger's scale, debits equal to credits. Checked before anything is written;
 * the database checks the balance again when the transaction commits.
 */
public final class LedgerPosting {

    public static final int MIN_LINES = 2;
    public static final int MAX_LINES = 200;

    private LedgerPosting() {}

    /** All violations of the lines, together; empty when they can be posted. Entries are numbered from 1. */
    public static List<Violation> validate(List<PostingLine> lines, int scale) {
        List<Violation> violations = new ArrayList<>();
        if (lines.size() < MIN_LINES) {
            violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_TOO_FEW_LINES,
                "A transaction needs at least " + MIN_LINES + " entries", Map.of("min", MIN_LINES)));
        }
        if (lines.size() > MAX_LINES) {
            violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_TOO_MANY_LINES,
                "A transaction has at most " + MAX_LINES + " entries", Map.of("max", MAX_LINES)));
        }
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal amount = lines.get(i).amount();
            int line = i + 1;
            if (amount.signum() <= 0) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_AMOUNT_NOT_POSITIVE,
                    "Entry " + line + ": amount " + amount.toPlainString() + " is not positive",
                    Map.of("line", line)));
            } else if (amount.stripTrailingZeros().scale() > scale) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_AMOUNT_SCALE,
                    "Entry " + line + ": amount " + amount.toPlainString() + " has more than " + scale
                        + " decimal places", Map.of("line", line, "scale", scale)));
            }
        }
        BigDecimal debit = total(lines, Direction.DEBIT);
        BigDecimal credit = total(lines, Direction.CREDIT);
        if (debit.compareTo(credit) != 0) {
            violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_UNBALANCED,
                "Debits " + debit.toPlainString() + " and credits " + credit.toPlainString() + " differ",
                Map.of("debit", debit.toPlainString(), "credit", credit.toPlainString())));
        }
        return List.copyOf(violations);
    }

    /** Sum of the amounts on one side. */
    public static BigDecimal total(List<PostingLine> lines, Direction direction) {
        return lines.stream()
            .filter(line -> line.direction() == direction)
            .map(PostingLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The entries of the transaction that cancels {@code lines}: same accounts and amounts, sides swapped. */
    public static List<PostingLine> reversal(List<PostingLine> lines) {
        return lines.stream().map(PostingLine::reversed).toList();
    }
}
