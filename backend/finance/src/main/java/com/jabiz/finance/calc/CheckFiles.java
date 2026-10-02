package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The files of check payments (FIN-AP-013): the print file a check printer takes, one row per check with the amount in
 * words, and the positive pay file the bank compares presented checks with, one row per check issued or voided.
 * Both are CSV with a header row, fields quoted when they hold a comma, a quote or a line break.
 */
public final class CheckFiles {

    /** A check: its number, date, payee, amount and what it pays. */
    public record Check(String checkNo, LocalDate date, String payee, BigDecimal amount, String paymentNo,
        String memo, boolean voided) {}

    public static String printFile(List<Check> checks) {
        StringBuilder out = new StringBuilder("check_number,check_date,payee,amount,amount_in_words,payment_no,memo\n");
        for (Check check : checks) {
            row(out, check.checkNo(), check.date().toString(), check.payee(), money(check.amount()),
                words(check.amount()), check.paymentNo(), check.memo());
        }
        return out.toString();
    }

    /** Issued checks are {@code I}, voided ones {@code V}, so the bank refuses a stopped check. */
    public static String positivePay(String accountNumber, List<Check> checks) {
        StringBuilder out = new StringBuilder("account_number,check_number,issue_date,amount,payee,status\n");
        for (Check check : checks) {
            row(out, accountNumber, check.checkNo(), check.date().toString(), money(check.amount()), check.payee(),
                check.voided() ? "V" : "I");
        }
        return out.toString();
    }

    /** "Two thousand five hundred and 00/100": what a check writes out. */
    public static String words(BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.UNNECESSARY);
        long dollars = value.longValue();
        int cents = value.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        String text = dollars == 0 ? "Zero" : spell(dollars);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + " and "
            + String.format(Locale.ROOT, "%02d", cents) + "/100";
    }

    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
        "ninety"};
    private static final String[] GROUPS = {"", " thousand", " million", " billion"};

    private static String spell(long value) {
        StringBuilder out = new StringBuilder();
        int group = 0;
        while (value > 0) {
            int part = (int) (value % 1000);
            if (part != 0) {
                String words = hundreds(part) + GROUPS[group];
                out.insert(0, out.isEmpty() ? words : words + " ");
            }
            value /= 1000;
            group++;
        }
        return out.toString();
    }

    private static String hundreds(int value) {
        StringBuilder out = new StringBuilder();
        if (value >= 100) {
            out.append(ONES[value / 100]).append(" hundred");
            value %= 100;
            if (value > 0) {
                out.append(' ');
            }
        }
        if (value >= 20) {
            out.append(TENS[value / 10]);
            if (value % 10 > 0) {
                out.append('-').append(ONES[value % 10]);
            }
        } else if (value > 0) {
            out.append(ONES[value]);
        }
        return out.toString();
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static void row(StringBuilder out, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(field(values[i]));
        }
        out.append('\n');
    }

    /**
     * Quoted when needed; a leading {@code = + - @}, tab or carriage return is prefixed so a spreadsheet does not run
     * it as a formula. Such a payee name prints with the quote: a vendor's legal name hardly starts so.
     */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        String text = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private CheckFiles() {}
}
