package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What a bank statement must satisfy before any of it is stored (FIN-BK-003), and the key each line is stored under:
 * <ul>
 *   <li>the opening balance plus the lines equals the closing balance;</li>
 *   <li>every line lies within the statement's days;</li>
 *   <li>a line is the bank's reference where the bank gives one ({@code REF:BNK-0001}), so the same line sent again,
 *       in the same or another layout, is the same line; without one, a hash of its day, amount and description and
 *       its place among the lines alike ({@code H:…}), so two equal fees on one day stay two lines.</li>
 * </ul>
 */
public final class StatementCheck {

    /** A statement line as read; the amount is signed as the bank sees it (deposits positive). */
    public record Line(LocalDate valueDate, String bankReference, String description, BigDecimal amount) {
        public Line {
            Objects.requireNonNull(valueDate, "valueDate");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** What is wrong with a statement as a whole; empty when nothing. */
    public record Problem(String code, String message, Integer line) {}

    public static final String NOT_BALANCED = "FIN_BANK_STATEMENT_NOT_BALANCED";
    public static final String OUTSIDE = "FIN_BANK_STATEMENT_LINE_OUTSIDE";
    public static final String DAYS = "FIN_BANK_STATEMENT_DAYS";
    public static final String DUPLICATE = "FIN_BANK_STATEMENT_DUPLICATE_REFERENCE";

    private StatementCheck() {}

    /** The sum of the lines. */
    public static BigDecimal net(List<Line> lines) {
        return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** How far the lines miss the closing balance: zero for a statement that adds up. */
    public static BigDecimal difference(BigDecimal opening, List<Line> lines, BigDecimal closing) {
        return opening.add(net(lines)).subtract(closing);
    }

    public static List<Problem> check(LocalDate from, LocalDate to, BigDecimal opening, List<Line> lines,
        BigDecimal closing) {
        List<Problem> problems = new ArrayList<>();
        if (to.isBefore(from)) {
            problems.add(new Problem(DAYS, "A statement ends on or after the day it starts", null));
        }
        BigDecimal difference = difference(opening, lines, closing);
        if (difference.signum() != 0) {
            problems.add(new Problem(NOT_BALANCED, "The opening balance " + opening.toPlainString() + " and the lines "
                + net(lines).toPlainString() + " do not add up to the closing balance " + closing.toPlainString()
                + " (difference " + difference.toPlainString() + ")", null));
        }
        Map<String, Integer> references = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.valueDate().isBefore(from) || line.valueDate().isAfter(to)) {
                problems.add(new Problem(OUTSIDE, "Line " + (i + 1) + " of " + line.valueDate() + " lies outside the "
                    + "statement's days " + from + " to " + to, i));
            }
            String reference = reference(line.bankReference());
            if (reference != null && references.putIfAbsent(reference, i) != null) {
                problems.add(new Problem(DUPLICATE, "The bank reference " + line.bankReference() + " is on two lines",
                    i));
            }
        }
        return List.copyOf(problems);
    }

    /** The key of each line, in order. */
    public static List<String> keys(List<Line> lines) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> keys = new ArrayList<>(lines.size());
        for (Line line : lines) {
            String reference = reference(line.bankReference());
            if (reference != null) {
                keys.add("REF:" + reference);
                continue;
            }
            String content = line.valueDate() + "|" + line.amount().stripTrailingZeros().toPlainString() + "|"
                + normalize(line.description());
            int ordinal = seen.merge(content, 1, Integer::sum);
            keys.add("H:" + sha256(content + "|" + ordinal).substring(0, 40));
        }
        return List.copyOf(keys);
    }

    /** The last four digits of an account number, to compare a file's account with the bank account's. */
    public static String last4(String accountNumber) {
        if (accountNumber == null) {
            return null;
        }
        String digits = accountNumber.replaceAll("[^0-9A-Za-z]", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private static String reference(String bankReference) {
        if (bankReference == null || bankReference.isBlank()) {
            return null;
        }
        return bankReference.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalize(String description) {
        return description == null ? "" : description.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
