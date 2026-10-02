package com.jabiz.finance.io;

import com.jabiz.finance.calc.StatementCheck;
import com.jabiz.imports.ImportFileException;
import com.jabiz.imports.ImportParser;
import com.jabiz.imports.ParseLimits;
import com.jabiz.imports.RawRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a BAI2 cash management file (BAI, "Cash Management Balance Reporting Specifications", version 2) of one bank
 * account into statement lines (FIN-BK-003; F5 plan decision D3).
 * <ul>
 *   <li>Records are {@code 01} file, {@code 02} group (the as-of date), {@code 03} account (number, currency and the
 *       summary amounts: {@code 010} opening ledger, {@code 015} closing ledger), {@code 16} transaction detail,
 *       {@code 88} continuation, {@code 49}, {@code 98} and {@code 99} trailers; each ends with {@code /} but a detail's
 *       text, which runs to the end of the record.</li>
 *   <li>Amounts are in cents without a sign: detail type codes 100–399 are credits (deposits), 400–699 debits.</li>
 *   <li>A BAI2 report is of one day, its as-of date: every line is of that day (a value date, funds type
 *       {@code V}, says when the money is available, not when it was booked) and so is the statement.</li>
 *   <li>A line's reference is the bank's reference only: the customer reference (a check number, a company ID) is
 *       not unique and goes with the text.</li>
 *   <li>The trailers' control totals and record counts are checked; a file of more than one group or account is
 *       refused (the import is for one bank account), as is one without lines (nothing to record: its balances carry
 *       over to the next).</li>
 * </ul>
 * The text is ASCII; anything else, a control character or a malformed record refuses the whole file.
 */
public final class Bai2Parser implements ImportParser {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("uuMMdd")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    @Override
    public void parse(Path file, ParseLimits limits, Sink sink) throws IOException {
        if (Files.size(file) > limits.maxUncompressedBytes()) {
            throw new ImportFileException("file", "The file is larger than " + limits.maxUncompressedBytes()
                + " bytes");
        }
        String text = decode(Files.readAllBytes(file));
        List<String[]> records = records(text, limits);
        new Reader(records, limits, sink).read();
    }

    private static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.US_ASCII.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                    throw new ImportFileException("file", "The file holds a control character");
                }
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new ImportFileException("file", "A BAI2 file is plain ASCII text");
        }
    }

    /** The physical lines, each split into its record code and the rest. */
    private static List<String[]> records(String text, ParseLimits limits) {
        List<String[]> records = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\r?\n", -1)) {
            number++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > limits.maxCellLength()) {
                throw new ImportFileException("line " + number, "A record is longer than " + limits.maxCellLength()
                    + " characters");
            }
            int comma = line.indexOf(',');
            if (comma < 0) {
                throw new ImportFileException("line " + number, "A record starts with its code and a comma");
            }
            records.add(new String[] {line.substring(0, comma), line.substring(comma + 1), String.valueOf(number)});
        }
        return records;
    }

    /** One pass over the records; the state of the account being read. */
    private static final class Reader {
        private final List<String[]> records;
        private final ParseLimits limits;
        private final Sink sink;

        private LocalDate asOf;
        private String account;
        private String currency;
        private BigDecimal opening;
        private BigDecimal closing;
        private int accounts;
        private long accountTotal;
        private int accountRecords;
        private int groups;
        private long groupTotal;
        private long fileTotal;
        /** The record an {@code 88} continues: {@code 03} (more summary amounts) or {@code 16} (more text). */
        private String continuing;
        private final List<Map<String, String>> lines = new ArrayList<>();
        private final List<String> locations = new ArrayList<>();

        Reader(List<String[]> records, ParseLimits limits, Sink sink) {
            this.records = records;
            this.limits = limits;
            this.sink = sink;
        }

        void read() {
            if (records.isEmpty() || !"01".equals(records.getFirst()[0])) {
                throw new ImportFileException("line 1", "A BAI2 file starts with a 01 file header");
            }
            boolean ended = false;
            for (int i = 0; i < records.size(); i++) {
                String[] record = records.get(i);
                String where = "line " + record[2];
                if (ended) {
                    throw new ImportFileException(where, "Nothing follows the 99 file trailer");
                }
                switch (record[0]) {
                    case "01" -> fields(record[1], where);
                    case "98" -> trailer(record[1], where, groupTotal, "group");
                    case "02" -> group(record[1], where);
                    case "03" -> account(record[1], where);
                    case "16" -> detail(withContinuations(i, record[1]), where);
                    case "88" -> continuation(record[1], where);
                    case "49" -> accountTrailer(record[1], where);
                    case "99" -> {
                        trailer(record[1], where, fileTotal, "file");
                        ended = true;
                    }
                    default -> throw new ImportFileException(where, "Unknown BAI2 record " + record[0]);
                }
                if (!"88".equals(record[0])) {
                    continuing = record[0];
                }
            }
            if (!ended) {
                throw new ImportFileException("file", "A BAI2 file ends with a 99 file trailer");
            }
            if (accounts != 1) {
                throw new ImportFileException("file", "A statement file is of one bank account; this one has "
                    + accounts);
            }
            emit();
        }

        private String withContinuations(int index, String body) {
            StringBuilder text = new StringBuilder(body);
            for (int j = index + 1; j < records.size() && "88".equals(records.get(j)[0]); j++) {
                String more = records.get(j)[1];
                text.append(' ').append(more.endsWith("/") ? more.substring(0, more.length() - 1) : more);
            }
            return text.toString();
        }

        private void group(String body, String where) {
            groups++;
            if (groups > 1) {
                throw new ImportFileException(where, "A statement file holds one group");
            }
            List<String> f = fields(body, where);
            if (f.size() < 4) {
                throw new ImportFileException(where, "A 02 group header names the as-of date");
            }
            asOf = date(f.get(3), where);
            if (f.size() > 5 && !f.get(5).isBlank()) {
                currency = f.get(5).trim();
            }
        }

        private void account(String body, String where) {
            if (asOf == null) {
                throw new ImportFileException(where, "An account record follows its 02 group header");
            }
            accounts++;
            if (accounts > 1) {
                throw new ImportFileException(where, "A statement file is of one bank account");
            }
            List<String> f = fields(body, where);
            if (f.isEmpty() || f.getFirst().isBlank()) {
                throw new ImportFileException(where, "An account record names the account");
            }
            account = StatementCheck.last4(f.getFirst());
            if (f.size() > 1 && !f.get(1).isBlank()) {
                currency = f.get(1).trim();
            }
            accountRecords = 1;
            accountTotal = 0;
            summaries(f, 2, where);
        }

        /** Groups of type code, amount, item count and funds type (with the funds type's own fields). */
        private void summaries(List<String> f, int start, String where) {
            int i = start;
            while (i < f.size() && !f.get(i).isBlank()) {
                String type = f.get(i);
                String amount = i + 1 < f.size() ? f.get(i + 1) : "";
                String funds = i + 3 < f.size() ? f.get(i + 3) : "";
                if (!amount.isBlank()) {
                    long cents = cents(amount, where, true);
                    accountTotal = add(accountTotal, cents, where);
                    if ("010".equals(type)) {
                        opening = BigDecimal.valueOf(cents, 2);
                    } else if ("015".equals(type)) {
                        closing = BigDecimal.valueOf(cents, 2);
                    }
                }
                i += 4 + fundsFields(funds, f, i + 4, where);
            }
        }

        private void continuation(String body, String where) {
            if (account != null) {
                accountRecords++;
            }
            // A detail's continued text is read with the detail.
            if ("03".equals(continuing)) {
                summaries(fields(body, where), 0, where);
            } else if (!"16".equals(continuing)) {
                fields(body, where);
            }
        }

        private void detail(String body, String where) {
            if (account == null) {
                throw new ImportFileException(where, "A detail follows its account record");
            }
            accountRecords++;
            // Type, amount and funds type; the funds type's own fields; then bank and customer references and text.
            String[] head = body.split(",", 4);
            if (head.length < 3) {
                throw new ImportFileException(where, "A 16 detail has a type code, an amount and a funds type");
            }
            String type = head[0].trim();
            int code;
            try {
                code = Integer.parseInt(type);
            } catch (NumberFormatException e) {
                throw new ImportFileException(where, "A detail's type code is a number: " + type);
            }
            if (code < 100 || code > 699) {
                throw new ImportFileException(where, "Detail type code " + type + " is neither a credit (100-399) "
                    + "nor a debit (400-699)");
            }
            long cents = cents(head[1], where, false);
            accountTotal = add(accountTotal, cents, where);
            String funds = head[2].trim();
            String rest = head.length > 3 ? head[3] : "";
            int extra = switch (funds) {
                case "", "0", "1", "2", "Z" -> 0;
                case "V" -> 2;
                case "S" -> 3;
                default -> throw new ImportFileException(where, "Funds type " + funds + " is not read");
            };
            String[] tail = rest.split(",", extra + 3);
            if (tail.length < extra + 2) {
                throw new ImportFileException(where, "A 16 detail has its bank and customer references");
            }
            if ("V".equals(funds) && !tail[0].isBlank()) {
                // Checked as a date, kept as none: the line is of the report's day.
                date(tail[0], where);
            }
            String bankReference = tail[extra].trim();
            String customerReference = tail[extra + 1].trim();
            String text = tail.length > extra + 2 ? tail[extra + 2].trim() : "";
            if (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            if (lines.size() >= limits.maxRecords()) {
                throw new ImportFileException(where, "The file has more than " + limits.maxRecords() + " lines");
            }
            BigDecimal amount = BigDecimal.valueOf(code < 400 ? cents : -cents, 2);
            Map<String, String> cells = new LinkedHashMap<>();
            cells.put(StatementColumns.DATE, asOf.toString());
            cells.put(StatementColumns.REFERENCE, bankReference);
            cells.put(StatementColumns.DESCRIPTION, customerReference.isEmpty() ? text
                : text.isEmpty() ? customerReference : text + " " + customerReference);
            cells.put(StatementColumns.AMOUNT, amount.toPlainString());
            cells.put(StatementColumns.TYPE, type);
            lines.add(cells);
            locations.add(where);
        }

        private void accountTrailer(String body, String where) {
            if (account == null) {
                throw new ImportFileException(where, "A 49 account trailer follows its account");
            }
            accountRecords++;
            List<String> f = fields(body, where);
            if (f.size() < 2) {
                throw new ImportFileException(where, "A 49 account trailer has a control total and a record count");
            }
            long control = cents(f.get(0), where, true);
            if (control != accountTotal) {
                throw new ImportFileException(where, "The account's control total " + f.get(0) + " is not the sum of "
                    + "its amounts " + accountTotal);
            }
            int count;
            try {
                count = Integer.parseInt(f.get(1).trim());
            } catch (NumberFormatException e) {
                throw new ImportFileException(where, "A record count is a number");
            }
            if (count != accountRecords) {
                throw new ImportFileException(where, "The account trailer counts " + count + " records, the account "
                    + "has " + accountRecords);
            }
            groupTotal = add(groupTotal, accountTotal, where);
            fileTotal = add(fileTotal, accountTotal, where);
        }

        /** A group's or the file's trailer: its control total is the sum of its accounts'. */
        private void trailer(String body, String where, long expected, String what) {
            List<String> f = fields(body, where);
            if (f.isEmpty() || cents(f.getFirst(), where, true) != expected) {
                throw new ImportFileException(where, "The " + what + " control total is not the sum of its "
                    + "accounts' " + expected);
            }
        }

        private void emit() {
            if (opening == null || closing == null) {
                throw new ImportFileException("file", "The account record gives the opening (010) and closing (015) "
                    + "ledger balances");
            }
            if (lines.isEmpty()) {
                throw new ImportFileException("file", "The statement has no lines: nothing to record, its balances "
                    + "carry over to the next");
            }
            LocalDate from = asOf;
            sink.columns(StatementColumns.ALL);
            sink.header(StatementColumns.OPENING, opening.toPlainString());
            sink.header(StatementColumns.CLOSING, closing.toPlainString());
            sink.header(StatementColumns.FROM, from.toString());
            sink.header(StatementColumns.TO, asOf.toString());
            for (int i = 0; i < lines.size(); i++) {
                Map<String, String> cells = new LinkedHashMap<>(lines.get(i));
                cells.put(StatementColumns.ACCOUNT, account);
                cells.put(StatementColumns.FROM, from.toString());
                cells.put(StatementColumns.TO, asOf.toString());
                cells.put(StatementColumns.OPENING, opening.toPlainString());
                cells.put(StatementColumns.CLOSING, closing.toPlainString());
                cells.put(StatementColumns.CURRENCY, currency == null ? "" : currency);
                sink.record(new RawRecord(i + 1, locations.get(i), cells));
            }
        }

        /** How many fields a funds type adds after it, in a summary. */
        private static int fundsFields(String funds, List<String> f, int at, String where) {
            return switch (funds.trim()) {
                case "", "0", "1", "2", "Z" -> 0;
                case "V" -> 2;
                case "S" -> 3;
                case "D" -> {
                    int count;
                    try {
                        count = Integer.parseInt(at < f.size() ? f.get(at).trim() : "");
                    } catch (NumberFormatException e) {
                        throw new ImportFileException(where, "Funds type D gives a number of distributions");
                    }
                    yield 1 + 2 * count;
                }
                default -> throw new ImportFileException(where, "Funds type " + funds + " is not read");
            };
        }
    }

    private static List<String> fields(String body, String where) {
        String text = body.endsWith("/") ? body.substring(0, body.length() - 1) : body;
        if (text.contains("/")) {
            throw new ImportFileException(where, "A record ends at its '/'");
        }
        List<String> fields = new ArrayList<>();
        for (String field : text.split(",", -1)) {
            fields.add(field.trim());
        }
        return fields;
    }

    private static long add(long total, long amount, String where) {
        try {
            return Math.addExact(total, amount);
        } catch (ArithmeticException e) {
            throw new ImportFileException(where, "The amounts add up beyond any statement");
        }
    }

    private static long cents(String text, String where, boolean signed) {
        String value = text.trim();
        if (!value.matches(signed ? "[+-]?[0-9]{1,15}" : "[0-9]{1,15}")) {
            throw new ImportFileException(where, "An amount is a whole number of cents: " + value);
        }
        return Long.parseLong(value);
    }

    private static LocalDate date(String text, String where) {
        try {
            return LocalDate.parse(text.trim(), YYMMDD);
        } catch (DateTimeParseException e) {
            throw new ImportFileException(where, "A date is YYMMDD: " + text.trim());
        }
    }
}
