package com.jabiz.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
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
        return validate(lines, scale, List.of());
    }

    /**
     * As {@link #validate(List, int)}, and each line's memo and dimensions: only the declared dimensions, values of
     * at most {@value LedgerDimension#MAX_VALUE_LENGTH} characters, memos of at most {@value PostingLine#MAX_MEMO}.
     * Whether a value is in its dimension's list needs the database and is checked where the lines are booked.
     */
    public static List<Violation> validate(List<PostingLine> lines, int scale, List<LedgerDimension> dimensions) {
        return validate(lines, scale, dimensions, null);
    }

    /**
     * As {@link #validate(List, int, List)}, and the entries in foreign currencies (docs/design/11-ledger-events-jobs.md
     * section 1.8): a known ISO 4217 code other than the ledger's {@code functionalCurrency}
     * ({@code LEDGER_CURRENCY_INVALID}); a positive amount within the currency's decimal places; a positive rate of at
     * most {@value ForeignAmount#RATE_SCALE} decimal places ({@code LEDGER_RATE_INVALID}); the ledger amount exactly
     * the converted one ({@code LEDGER_FX_AMOUNT_MISMATCH}); and debits equal to credits in every foreign currency
     * ({@code LEDGER_UNBALANCED_IN_CURRENCY}), besides the balance in the ledger currency.
     */
    public static List<Violation> validate(List<PostingLine> lines, int scale, List<LedgerDimension> dimensions,
        String functionalCurrency) {
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
        Set<String> declared = new HashSet<>();
        dimensions.forEach(dimension -> declared.add(dimension.name()));
        for (int i = 0; i < lines.size(); i++) {
            PostingLine posting = lines.get(i);
            int line = i + 1;
            if (posting.memo() != null && posting.memo().length() > PostingLine.MAX_MEMO) {
                violations.add(new Violation("entries", PlatformErrorCodes.TOO_LONG,
                    "Entry " + line + ": the memo is longer than " + PostingLine.MAX_MEMO + " characters",
                    Map.of("line", line, "field", "memo", "max", PostingLine.MAX_MEMO)));
            }
            for (Map.Entry<String, String> value : new TreeMap<>(posting.dimensions()).entrySet()) {
                if (!declared.contains(value.getKey())) {
                    violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_DIMENSION_UNKNOWN,
                        "Entry " + line + ": no ledger dimension " + value.getKey(),
                        Map.of("line", line, "dimension", value.getKey())));
                } else if (value.getValue() == null || value.getValue().isBlank()
                    || value.getValue().length() > LedgerDimension.MAX_VALUE_LENGTH) {
                    violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_DIMENSION_INVALID,
                        "Entry " + line + ": " + value.getKey() + " must be 1 to "
                            + LedgerDimension.MAX_VALUE_LENGTH + " characters",
                        Map.of("line", line, "dimension", value.getKey(), "value", String.valueOf(value.getValue()))));
                }
            }
        }
        violations.addAll(foreign(lines, scale, functionalCurrency));
        BigDecimal debit = total(lines, Direction.DEBIT);
        BigDecimal credit = total(lines, Direction.CREDIT);
        if (debit.compareTo(credit) != 0) {
            violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_UNBALANCED,
                "Debits " + debit.toPlainString() + " and credits " + credit.toPlainString() + " differ",
                Map.of("debit", debit.toPlainString(), "credit", credit.toPlainString(),
                    "difference", debit.subtract(credit).abs().toPlainString())));
        }
        return List.copyOf(violations);
    }

    private static List<Violation> foreign(List<PostingLine> lines, int scale, String functionalCurrency) {
        List<Violation> violations = new ArrayList<>();
        Map<String, BigDecimal[]> sides = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ForeignAmount foreign = lines.get(i).foreign();
            if (foreign == null) {
                continue;
            }
            int line = i + 1;
            int currencyScale = ForeignAmount.scaleOf(foreign.currency());
            if (currencyScale < 0 || foreign.currency().equals(functionalCurrency)) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_CURRENCY_INVALID,
                    "Entry " + line + ": " + foreign.currency() + " is not a foreign currency",
                    Map.of("line", line, "currency", foreign.currency())));
                continue;
            }
            boolean usable = true;
            if (foreign.amount().signum() <= 0) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_AMOUNT_NOT_POSITIVE,
                    "Entry " + line + ": amount " + foreign.amount().toPlainString() + " " + foreign.currency()
                        + " is not positive", Map.of("line", line, "currency", foreign.currency())));
                usable = false;
            } else if (foreign.amount().stripTrailingZeros().scale() > currencyScale) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_AMOUNT_SCALE,
                    "Entry " + line + ": amount " + foreign.amount().toPlainString() + " " + foreign.currency()
                        + " has more than " + currencyScale + " decimal places",
                    Map.of("line", line, "scale", currencyScale, "currency", foreign.currency())));
                usable = false;
            }
            if (foreign.rate().signum() <= 0 || foreign.rate().stripTrailingZeros().scale() > ForeignAmount.RATE_SCALE) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_RATE_INVALID,
                    "Entry " + line + ": rate " + foreign.rate().toPlainString() + " must be positive with at most "
                        + ForeignAmount.RATE_SCALE + " decimal places",
                    Map.of("line", line, "rate", foreign.rate().toPlainString(), "scale", ForeignAmount.RATE_SCALE)));
                usable = false;
            }
            if (usable) {
                BigDecimal expected = foreign.converted(scale);
                if (expected.compareTo(lines.get(i).amount()) != 0) {
                    violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_FX_AMOUNT_MISMATCH,
                        "Entry " + line + ": " + foreign.amount().toPlainString() + " " + foreign.currency() + " at "
                            + foreign.rate().toPlainString() + " is " + expected.toPlainString() + ", not "
                            + lines.get(i).amount().toPlainString(),
                        Map.of("line", line, "expected", expected.toPlainString(),
                            "amount", lines.get(i).amount().toPlainString())));
                }
                BigDecimal[] side = sides.computeIfAbsent(foreign.currency(),
                    c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                int index = lines.get(i).direction() == Direction.DEBIT ? 0 : 1;
                side[index] = side[index].add(foreign.amount());
            }
        }
        sides.forEach((currency, side) -> {
            if (side[0].compareTo(side[1]) != 0) {
                violations.add(new Violation("entries", PlatformErrorCodes.LEDGER_UNBALANCED_IN_CURRENCY,
                    "Debits " + side[0].toPlainString() + " and credits " + side[1].toPlainString() + " " + currency
                        + " differ",
                    Map.of("currency", currency, "debit", side[0].toPlainString(), "credit", side[1].toPlainString(),
                        "difference", side[0].subtract(side[1]).abs().toPlainString())));
            }
        });
        return violations;
    }

    /** Sum of the transaction-currency amounts of one currency on one side. */
    public static BigDecimal total(List<PostingLine> lines, String currency, Direction direction) {
        return lines.stream()
            .filter(line -> line.direction() == direction && line.foreign() != null
                && line.foreign().currency().equals(currency))
            .map(line -> line.foreign().amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
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
