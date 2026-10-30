package com.jabiz.quizbuks.journal;

import com.jabiz.quizbuks.setup.Brand;
import com.jabiz.quizbuks.wallet.QbLedger;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The summary voucher of an export (Q9d, D-Q9d-2, D-Q9d-4, D-Q9d-6): one line per account and side, the sponsor
 * prepayments (2110) one per sponsor besides (the accounting system's customer sub-ledger); the takers' accounts only
 * per account (D-2). The database sums the lines (template {@code qb.system.journal-summary}); this numbers them and
 * writes the files: the voucher for the accounting system to import (summary lines only) and the detail, each posting
 * of each line, in parts (D-3; review H1).
 */
public final class JournalLines {

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";

    /** An account code of the accounting system (the mapping's value): a letter or digit first. */
    public static final Pattern ACCOUNT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");
    public static final int EXTERNAL_VOUCHER_LENGTH = 50;

    /** The accounts carrying a line per sponsor. */
    private static final String PER_SPONSOR = QbLedger.SPONSOR_PREPAYMENTS;

    /** The detail's times, in Japan's time as the periods are. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** A sum of the summary template: an account, a side and (2110) a sponsor. */
    public record Group(String accountCode, String side, String sponsorId, BigDecimal amount, int transactions) {

        public Group {
            Objects.requireNonNull(accountCode, "accountCode");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** One posting of an account in one transaction, on one side (and for 2110 one sponsor), as the template sums it. */
    public record Entry(String transactionId, Instant bookingTime, Instant recordedAt, String accountCode,
        String direction, String party, BigDecimal amount, String sourceEntity, String sourceId, String reference) {

        public Entry {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(accountCode, "accountCode");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
        }

        /** The sponsor of a 2110 line; none on the other accounts. */
        String sponsor() {
            return PER_SPONSOR.equals(accountCode) ? party : null;
        }
    }

    /**
     * A line of the voucher.
     *
     * @param externalCode the accounting system's account code; null when not mapped
     * @param sponsorId    the sponsor (2110 only)
     * @param transactions how many ledger transactions it sums
     */
    public record Line(int lineNo, String side, String accountCode, String externalCode, String sponsorId,
        BigDecimal amount, int transactions) {}

    /** @param lines the voucher's lines, by account, debit before credit, then by sponsor */
    public record Summary(List<Line> lines, BigDecimal debitTotal, BigDecimal creditTotal, List<String> accounts) {

        public boolean balanced() {
            return debitTotal.compareTo(creditTotal) == 0;
        }

        /** Each line's number by its key ({@link #key}), for the detail. */
        public Map<String, Integer> lineNumbers() {
            Map<String, Integer> numbers = new HashMap<>();
            lines.forEach(line -> numbers.put(key(line.accountCode(), line.side(), line.sponsorId()), line.lineNo()));
            return numbers;
        }
    }

    /** The key of a line: account, side and sponsor. */
    public static String key(String accountCode, String side, String sponsorId) {
        return accountCode + "|" + side + "|" + (sponsorId == null ? "" : sponsorId);
    }

    private static final Comparator<Group> ORDER = Comparator.comparing(Group::accountCode)
        .thenComparing(group -> DEBIT.equals(group.side()) ? 0 : 1)
        .thenComparing(Group::sponsorId, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** The groups of the summary template's rows. */
    public static List<Group> groups(List<Map<String, Object>> rows) {
        List<Group> groups = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String account = text(row.get("accountCode"));
            groups.add(new Group(account, text(row.get("direction")),
                PER_SPONSOR.equals(account) ? text(row.get("party")) : null, decimal(row.get("amount")),
                decimal(row.get("transactions")).intValue()));
        }
        return groups;
    }

    /** The entries of the detail template's rows. */
    public static List<Entry> entries(List<Map<String, Object>> rows) {
        List<Entry> entries = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            entries.add(new Entry(text(row.get("transactionId")), instant(row.get("bookingTime")),
                instant(row.get("recordedAt")), text(row.get("accountCode")), text(row.get("direction")),
                text(row.get("party")), decimal(row.get("amount")), text(row.get("sourceEntity")),
                text(row.get("sourceId")), text(row.get("reference"))));
        }
        return entries;
    }

    /** The voucher of the summed {@code groups}, with each account's code of the accounting system from {@code mapping}. */
    public static Summary summarize(List<Group> groups, Map<String, String> mapping) {
        List<Group> ordered = new ArrayList<>(groups);
        ordered.sort(ORDER);
        List<Line> lines = new ArrayList<>();
        TreeSet<String> accounts = new TreeSet<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Group group : ordered) {
            lines.add(new Line(lines.size() + 1, group.side(), group.accountCode(),
                blankToNull(mapping.get(group.accountCode())), group.sponsorId(), group.amount(),
                group.transactions()));
            accounts.add(group.accountCode());
            if (DEBIT.equals(group.side())) {
                debit = debit.add(group.amount());
            } else {
                credit = credit.add(group.amount());
            }
        }
        return new Summary(List.copyOf(lines), debit, credit, List.copyOf(accounts));
    }

    /** The accounts the voucher moves that have no code of the accounting system. */
    public static List<String> unmapped(List<String> accounts, Map<String, String> mapping) {
        return accounts.stream().filter(code -> blankToNull(mapping.get(code)) == null).sorted().toList();
    }

    /** The accounts the voucher moves whose code is not one the accounting system takes. */
    public static List<String> invalid(List<String> accounts, Map<String, String> mapping) {
        return accounts.stream().filter(code -> {
            String external = blankToNull(mapping.get(code));
            return external != null && !ACCOUNT_CODE.matcher(external).matches();
        }).sorted().toList();
    }

    // ---- the files ---------------------------------------------------------------------------------------------------

    static final List<String> VOUCHER_COLUMNS = List.of("voucher_no", "date", "line_no", "side", "account_code",
        "platform_account", "sponsor_id", "amount", "memo");
    static final List<String> DETAIL_COLUMNS = List.of("voucher_no", "line_no", "transaction_id", "booking_time",
        "recorded_at", "platform_account", "side", "sponsor_id", "amount", "source", "source_id", "reference");

    /** The voucher for the accounting system: its summary lines only. */
    public static byte[] voucherCsv(String voucherNo, JournalPeriods.Period period, Summary summary) {
        StringBuilder csv = new StringBuilder();
        row(csv, VOUCHER_COLUMNS);
        for (Line line : summary.lines()) {
            row(csv, List.of(voucherNo, period.to().toString(), String.valueOf(line.lineNo()), line.side(),
                nullToEmpty(line.externalCode()), line.accountCode(), nullToEmpty(line.sponsorId()),
                line.amount().toPlainString(),
                Brand.current().name() + " " + period.label() + " " + line.accountCode() + " " + line.side()));
        }
        return bytes(csv);
    }

    /**
     * One part of the detail: every posting of the part's transactions with the number of its voucher line, by line,
     * then by booking time and transaction.
     */
    public static byte[] detailCsv(String voucherNo, List<Entry> entries, Map<String, Integer> lineNumbers) {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.<Entry, Integer>comparing(entry -> lineOf(entry, lineNumbers))
            .thenComparing(Entry::bookingTime, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Entry::transactionId));
        StringBuilder csv = new StringBuilder();
        row(csv, DETAIL_COLUMNS);
        for (Entry entry : ordered) {
            row(csv, List.of(voucherNo, String.valueOf(lineOf(entry, lineNumbers)), entry.transactionId(),
                time(entry.bookingTime()), time(entry.recordedAt()), entry.accountCode(), entry.direction(),
                nullToEmpty(entry.sponsor()), entry.amount().toPlainString(), nullToEmpty(entry.sourceEntity()),
                nullToEmpty(entry.sourceId()), nullToEmpty(entry.reference())));
        }
        return bytes(csv);
    }

    private static int lineOf(Entry entry, Map<String, Integer> lineNumbers) {
        Integer lineNo = lineNumbers.get(key(entry.accountCode(), entry.direction(), entry.sponsor()));
        if (lineNo == null) {
            // The summary and the detail read the same postings in one transaction: a posting of no line is a defect.
            throw new IllegalStateException("No voucher line for " + entry.accountCode() + " " + entry.direction());
        }
        return lineNo;
    }

    private static String time(Instant time) {
        return time == null ? "" : TIME.format(time.atZone(JournalPeriods.ZONE));
    }

    /**
     * A cell: every one quoted, and one beginning with {@code = + - @}, a tab or a carriage return gets a leading
     * {@code '} so that a spreadsheet never takes it for a formula (as the platform's example, docs/design/14 section
     * 10).
     */
    static String cell(String value) {
        String text = value == null ? "" : value;
        String safe = !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static void row(StringBuilder csv, List<String> cells) {
        csv.append(String.join(",", cells.stream().map(JournalLines::cell).toList())).append("\r\n");
    }

    /** UTF-8 with a byte order mark, which the spreadsheets used in Japan need to read it as UTF-8. */
    private static byte[] bytes(StringBuilder csv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }

    static BigDecimal decimal(Object value) {
        return switch (value) {
            case null -> BigDecimal.ZERO;
            case BigDecimal decimal -> decimal;
            case Number number -> new BigDecimal(number.toString());
            default -> new BigDecimal(value.toString());
        };
    }

    static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime time -> time.toInstant();
            default -> Instant.parse(value.toString());
        };
    }

    private JournalLines() {}
}
