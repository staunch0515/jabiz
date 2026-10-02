package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.calc.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The checks of a journal entry (FIN-GL-010, 011, 003, 004, 005, 006; docs/finance/00-design.md section 6.3), pure
 * and all at once so the preparer sees every problem together. A draft only needs well-formed lines
 * ({@link #checkLines}); an entry to submit must also balance, use postable and active accounts, keep off control
 * accounts unless the controller granted the exception, and name the dimensions its accounts require with active
 * values ({@link #checkForPosting}).
 */
public final class JournalValidator {

    /** Most lines one entry has (FIN-GL-020: the grid takes 50 and more). */
    public static final int MAX_LINES = 500;

    public static final String TOO_FEW_LINES = "FIN_JOURNAL_TOO_FEW_LINES";
    public static final String TOO_MANY_LINES = "FIN_JOURNAL_TOO_MANY_LINES";
    public static final String LINE_AMOUNT = "FIN_JOURNAL_LINE_AMOUNT";
    public static final String UNBALANCED = "FIN_JOURNAL_UNBALANCED";
    public static final String UNBALANCED_IN_CURRENCY = "FIN_JOURNAL_UNBALANCED_IN_CURRENCY";
    public static final String ACCOUNT_UNKNOWN = "FIN_JOURNAL_ACCOUNT_UNKNOWN";
    public static final String ACCOUNT_INACTIVE = "FIN_JOURNAL_ACCOUNT_INACTIVE";
    public static final String ACCOUNT_SUMMARY = "FIN_JOURNAL_ACCOUNT_SUMMARY";
    public static final String CONTROL_ACCOUNT = "FIN_JOURNAL_CONTROL_ACCOUNT";
    public static final String DIMENSION_REQUIRED = "FIN_JOURNAL_DIMENSION_REQUIRED";
    public static final String DIMENSION_INVALID = "FIN_JOURNAL_DIMENSION_INVALID";

    /**
     * One line as entered: exactly one of debit and credit, positive, in cents of US dollars. A line in a foreign
     * currency (F7 plan decision D5) also keeps that currency, its amount in it and the rate it was converted at.
     */
    public record Line(String accountCode, BigDecimal debit, BigDecimal credit, String memo, String department,
        String location, String currency, BigDecimal foreignAmount, BigDecimal exchangeRate) {

        public Line(String accountCode, BigDecimal debit, BigDecimal credit, String memo, String department,
            String location) {
            this(accountCode, debit, credit, memo, department, location, null, null, null);
        }

        /** The same line on the other side, as a reversal has it. */
        public Line reversed() {
            return new Line(accountCode, credit, debit, memo, department, location, currency, foreignAmount,
                exchangeRate);
        }

        /** The amount on its side, debit positive and credit negative. */
        public BigDecimal signed() {
            return debit != null ? debit : credit == null ? BigDecimal.ZERO : credit.negate();
        }
    }

    /**
     * What the checks need to know of an account.
     *
     * @param controlClass      null for an ordinary account
     * @param requiredDimension null, {@code department} or {@code location}
     */
    public record Account(String code, boolean active, boolean summary, String controlClass,
        String requiredDimension) {}

    /** The active values of the dimensions, by dimension name. */
    public record Dimensions(Set<String> departments, Set<String> locations) {
        public Dimensions {
            departments = Set.copyOf(departments);
            locations = Set.copyOf(locations);
        }
    }

    /** Debit and credit totals of an entry and their difference (debit minus credit). */
    public record Totals(BigDecimal debit, BigDecimal credit) {
        public BigDecimal difference() {
            return debit.subtract(credit);
        }

        public boolean balanced() {
            return difference().signum() == 0;
        }
    }

    private JournalValidator() {}

    public static Totals totals(List<Line> lines) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Line line : lines) {
            debit = debit.add(line.debit() == null ? BigDecimal.ZERO : line.debit());
            credit = credit.add(line.credit() == null ? BigDecimal.ZERO : line.credit());
        }
        return new Totals(Money.usd(debit), Money.usd(credit));
    }

    /** The form of the lines, as a draft must have it. */
    public static List<Violation> checkLines(List<Line> lines) {
        List<Violation> problems = new ArrayList<>();
        if (lines.size() > MAX_LINES) {
            problems.add(new Violation("lines", TOO_MANY_LINES, "An entry has at most " + MAX_LINES + " lines",
                Map.of("max", MAX_LINES)));
            return problems;
        }
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean hasDebit = line.debit() != null && line.debit().signum() != 0;
            boolean hasCredit = line.credit() != null && line.credit().signum() != 0;
            BigDecimal amount = hasDebit ? line.debit() : line.credit();
            if (hasDebit == hasCredit || amount.signum() < 0 || !Money.fits(amount, Money.USD_SCALE)) {
                problems.add(new Violation(field(i, hasDebit ? "debit" : "credit"), LINE_AMOUNT,
                    "Line " + (i + 1) + " needs either a debit or a credit, more than zero, in cents",
                    Map.of("line", i + 1)));
            }
        }
        return problems;
    }

    /**
     * Everything an entry must satisfy to be submitted and posted.
     *
     * @param accounts          the accounts by code; a code missing here is unknown
     * @param controlException  whether the controller granted this entry the exception for control accounts
     */
    public static List<Violation> checkForPosting(List<Line> lines, Map<String, Account> accounts,
        Dimensions dimensions, boolean controlException) {
        List<Violation> problems = new ArrayList<>(checkLines(lines));
        if (lines.size() < 2) {
            problems.add(new Violation("lines", TOO_FEW_LINES, "An entry has at least two lines", Map.of()));
        }
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String code = line.accountCode() == null ? "" : line.accountCode().trim();
            Account account = accounts.get(code);
            Map<String, Object> params = Map.of("line", i + 1, "accountCode", code);
            if (account == null) {
                problems.add(new Violation(field(i, "accountCode"), ACCOUNT_UNKNOWN, "There is no account " + code,
                    params));
                continue;
            }
            if (account.summary()) {
                problems.add(new Violation(field(i, "accountCode"), ACCOUNT_SUMMARY, "Account " + code
                    + " is a summary account and takes no postings", params));
            } else if (!account.active()) {
                problems.add(new Violation(field(i, "accountCode"), ACCOUNT_INACTIVE, "Account " + code
                    + " is inactive", params));
            }
            if (account.controlClass() != null && line.currency() != null) {
                // A control account's subledger converts its own documents (F7 plan decision D5).
                problems.add(new Violation(field(i, "currency"), CONTROL_ACCOUNT, "Account " + code
                    + " is a control account: a line in a foreign currency goes to an account that is none",
                    withEntry(params, "controlClass", account.controlClass())));
            } else if (account.controlClass() != null && !controlException) {
                problems.add(new Violation(field(i, "accountCode"), CONTROL_ACCOUNT, "Account " + code
                    + " is a control account: only its subledger posts to it, unless the controller grants this "
                    + "entry an exception", withEntry(params, "controlClass", account.controlClass())));
            }
            checkDimension(problems, i, "department", line.department(), dimensions.departments(), account);
            checkDimension(problems, i, "location", line.location(), dimensions.locations(), account);
        }
        Totals totals = totals(lines);
        if (!totals.balanced()) {
            problems.add(new Violation("lines", UNBALANCED, "Debits " + totals.debit().toPlainString()
                + " and credits " + totals.credit().toPlainString() + " differ by "
                + totals.difference().abs().toPlainString(), Map.of("debit", totals.debit(), "credit",
                totals.credit(), "difference", totals.difference().abs())));
        }
        // The ledger balances each foreign currency by itself: so must the entry.
        Map<String, BigDecimal> foreign = new java.util.TreeMap<>();
        for (Line line : lines) {
            if (line.currency() != null && line.foreignAmount() != null) {
                boolean debit = line.debit() != null && line.debit().signum() != 0;
                foreign.merge(line.currency(), debit ? line.foreignAmount() : line.foreignAmount().negate(),
                    BigDecimal::add);
            }
        }
        foreign.forEach((currency, difference) -> {
            if (difference.signum() != 0) {
                problems.add(new Violation("lines", UNBALANCED_IN_CURRENCY, "In " + currency + " debits and credits"
                    + " differ by " + difference.abs().toPlainString(), Map.of("currency", currency,
                    "difference", difference.abs())));
            }
        });
        return List.copyOf(problems);
    }

    private static void checkDimension(List<Violation> problems, int i, String dimension, String value,
        Set<String> active, Account account) {
        boolean blank = value == null || value.isBlank();
        if (blank && dimension.equals(account.requiredDimension())) {
            problems.add(new Violation(field(i, dimension), DIMENSION_REQUIRED, "Account " + account.code()
                + " needs a " + dimension, Map.of("line", i + 1, "dimension", dimension)));
        } else if (!blank && !active.contains(value.trim())) {
            problems.add(new Violation(field(i, dimension), DIMENSION_INVALID, value.trim() + " is not an active "
                + dimension, Map.of("line", i + 1, "dimension", dimension, "value", value.trim())));
        }
    }

    private static Map<String, Object> withEntry(Map<String, Object> params, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.put(key, Objects.requireNonNull(value));
        return copy;
    }

    private static String field(int index, String name) {
        return "lines[" + index + "]." + name;
    }
}
