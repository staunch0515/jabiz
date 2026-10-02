package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a NACHA file back and checks it as a bank would (FIN-AP-013 acceptance): every record 94 printable
 * characters, a multiple of ten records, the records in order, the routing numbers' check digits, ascending trace
 * numbers, and each batch's and the file's counts, entry hash and totals as the entries add them up. It shares no
 * code with {@link NachaWriter}, so a mistake in one shows in the other.
 */
public final class NachaValidator {

    /**
     * @param problems    what is wrong, by record; empty when the file is well formed
     * @param batches     the batches read
     * @param entries     the entries read
     * @param totalCredit the credits, in dollars
     * @param totalDebit  the debits, in dollars
     */
    public record Result(List<String> problems, int batches, int entries, BigDecimal totalCredit,
        BigDecimal totalDebit) {

        public Result {
            problems = List.copyOf(problems);
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }

    public static Result validate(String content) {
        List<String> problems = new ArrayList<>();
        List<String> lines = new ArrayList<>(List.of(content.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() != 94) {
                problems.add("record " + (i + 1) + " has " + line.length() + " characters, not 94");
            }
            if (!line.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
                problems.add("record " + (i + 1) + " has characters other than printable ASCII");
            }
        }
        if (lines.size() % 10 != 0) {
            problems.add(lines.size() + " records are not whole blocks of ten");
        }
        if (!problems.isEmpty() || lines.isEmpty()) {
            if (lines.isEmpty()) {
                problems.add("the file is empty");
            }
            return new Result(problems, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int at = 0;
        String header = lines.get(at++);
        if (header.charAt(0) != '1') {
            problems.add("record 1 is not a file header");
            return new Result(problems, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        } else {
            date(problems, field(header, 24, 29), "file creation date");
            expect(problems, header, 35, 37, "094", "record size");
            expect(problems, header, 38, 39, "10", "blocking factor");
            expect(problems, header, 40, 40, "1", "format code");
            if (!routing(field(header, 5, 13))) {
                problems.add("the immediate destination " + field(header, 5, 13) + " is no routing number");
            }
        }

        int batches = 0;
        int entries = 0;
        long hash = 0;
        long credit = 0;
        long debit = 0;
        long lastTrace = 0;
        int lastBatch = 0;
        while (at < lines.size() && lines.get(at).charAt(0) == '5') {
            String batchHeader = lines.get(at++);
            batches++;
            int batchNo = Integer.parseInt(digits(problems, batchHeader, 88, 94, "batch number"));
            if (batchNo <= lastBatch) {
                problems.add("batch " + batchNo + " does not follow batch " + lastBatch);
            }
            lastBatch = batchNo;
            String serviceClass = field(batchHeader, 2, 4);
            if (!List.of("200", "220", "225").contains(serviceClass)) {
                problems.add("batch " + batchNo + " has the service class " + serviceClass);
            }
            date(problems, field(batchHeader, 70, 75), "effective entry date of batch " + batchNo);
            String sec = field(batchHeader, 51, 53);
            if (!List.of("CCD", "PPD", "CTX", "WEB").contains(sec)) {
                problems.add("batch " + batchNo + " has the entry class " + sec);
            }
            String odfi = field(batchHeader, 80, 87);
            int count = 0;
            long batchHash = 0;
            long batchCredit = 0;
            long batchDebit = 0;
            while (at < lines.size() && lines.get(at).charAt(0) == '6') {
                String entry = lines.get(at++);
                count++;
                String code = field(entry, 2, 3);
                String routing = field(entry, 4, 12);
                if (!routing(routing)) {
                    problems.add("an entry of batch " + batchNo + " goes to " + routing + ", no routing number");
                } else {
                    batchHash += Long.parseLong(routing.substring(0, 8));
                }
                long amount = Long.parseLong(digits(problems, entry, 30, 39, "amount"));
                // A prenote (23, 28, 33, 38) tests the account and carries nothing; any other entry carries money.
                boolean prenote = List.of("23", "28", "33", "38").contains(code);
                if (prenote != (amount == 0)) {
                    problems.add("an entry of batch " + batchNo + " with the code " + code + " has the amount "
                        + amount);
                }
                if (field(entry, 55, 76).isBlank()) {
                    problems.add("an entry of batch " + batchNo + " names no receiver");
                }
                switch (code) {
                    case "22", "23", "32", "33" -> batchCredit += amount;
                    case "27", "28", "37", "38" -> batchDebit += amount;
                    default -> problems.add("an entry of batch " + batchNo + " has the transaction code " + code);
                }
                if (field(entry, 13, 29).isBlank()) {
                    problems.add("an entry of batch " + batchNo + " names no account");
                }
                String trace = field(entry, 80, 94);
                if (!trace.startsWith(odfi)) {
                    problems.add("the trace number " + trace + " is not of the originating bank " + odfi);
                }
                long sequence = Long.parseLong(digits(problems, entry, 80, 94, "trace number"));
                if (sequence <= lastTrace) {
                    problems.add("the trace number " + trace + " is not ascending");
                }
                lastTrace = sequence;
                if (field(entry, 79, 79).equals("1")) {
                    problems.add("entry " + trace + " says it has addenda; none are written");
                }
            }
            if (count == 0) {
                problems.add("batch " + batchNo + " has no entries");
            }
            if (at >= lines.size() || lines.get(at).charAt(0) != '8') {
                problems.add("batch " + batchNo + " has no control record");
                break;
            }
            String control = lines.get(at++);
            expect(problems, control, 2, 4, serviceClass, "service class of batch " + batchNo);
            expectNumber(problems, control, 5, 10, count, "entry count of batch " + batchNo);
            expectNumber(problems, control, 11, 20, batchHash % 10_000_000_000L, "entry hash of batch " + batchNo);
            expectNumber(problems, control, 21, 32, batchDebit, "debits of batch " + batchNo);
            expectNumber(problems, control, 33, 44, batchCredit, "credits of batch " + batchNo);
            expect(problems, control, 45, 54, field(batchHeader, 41, 50), "company of batch " + batchNo);
            expect(problems, control, 80, 87, odfi, "originating bank of batch " + batchNo);
            expect(problems, control, 88, 94, field(batchHeader, 88, 94), "number of batch " + batchNo);
            if ("220".equals(serviceClass) && batchDebit != 0) {
                problems.add("batch " + batchNo + " is of credits only and has debits");
            }
            if ("225".equals(serviceClass) && batchCredit != 0) {
                problems.add("batch " + batchNo + " is of debits only and has credits");
            }
            entries += count;
            hash += batchHash;
            credit += batchCredit;
            debit += batchDebit;
        }

        if (at >= lines.size() || lines.get(at).charAt(0) != '9' || lines.get(at).equals("9".repeat(94))) {
            problems.add("record " + (at + 1) + " is not the file control");
        } else {
            String control = lines.get(at++);
            expectNumber(problems, control, 2, 7, batches, "batch count");
            expectNumber(problems, control, 8, 13, lines.size() / 10, "block count");
            expectNumber(problems, control, 14, 21, entries, "entry count");
            expectNumber(problems, control, 22, 31, hash % 10_000_000_000L, "entry hash");
            expectNumber(problems, control, 32, 43, debit, "total debits");
            expectNumber(problems, control, 44, 55, credit, "total credits");
            for (; at < lines.size(); at++) {
                if (!lines.get(at).equals("9".repeat(94))) {
                    problems.add("record " + (at + 1) + " follows the file control and is not padding");
                }
            }
        }
        return new Result(problems, batches, entries, BigDecimal.valueOf(credit, 2), BigDecimal.valueOf(debit, 2));
    }

    /** The ABA check: 3, 7 and 1 times the digits in turn add up to a multiple of ten. */
    static boolean routing(String value) {
        if (value == null || !value.matches("\\d{9}")) {
            return false;
        }
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += weights[i] * (value.charAt(i) - '0');
        }
        return sum % 10 == 0;
    }

    private static void date(List<String> problems, String value, String what) {
        try {
            java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        } catch (java.time.format.DateTimeParseException e) {
            problems.add("the " + what + " '" + value + "' is no date");
        }
    }

    /** Positions as NACHA numbers them: from 1, both ends included. */
    private static String field(String line, int from, int to) {
        return line.substring(from - 1, to);
    }

    private static String digits(List<String> problems, String line, int from, int to, String what) {
        String value = field(line, from, to);
        if (!value.matches("\\d+")) {
            problems.add("the " + what + " '" + value + "' is not digits");
            return "0";
        }
        return value;
    }

    private static void expect(List<String> problems, String line, int from, int to, String expected, String what) {
        String value = field(line, from, to);
        if (!value.equals(expected)) {
            problems.add("the " + what + " is '" + value + "', not '" + expected + "'");
        }
    }

    private static void expectNumber(List<String> problems, String line, int from, int to, long expected,
        String what) {
        String value = digits(problems, line, from, to, what);
        if (Long.parseLong(value) != expected) {
            problems.add("the " + what + " is " + Long.parseLong(value) + ", the records add up to " + expected);
        }
    }

    private NachaValidator() {}
}
