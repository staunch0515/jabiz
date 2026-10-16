package com.jabiz.quizbuks.journal;

import com.jabiz.quizbuks.wallet.QbLedger;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The summary voucher of an export (Q9d, D-Q9d-2, D-Q9d-4, D-Q9d-6), from the postings it takes: one line per
 * account and side, the sponsor prepayments (2110) one per sponsor besides (the accounting system's customer
 * sub-ledger); the takers' accounts only per account (D-2). Its two CSV files: the voucher for the accounting system to
 * import (summary lines only) and the detail, each posting of each line, for the audit trail (D-3).
 */
public final class JournalLines {

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";

    /** An account code of the accounting system (the mapping's value). */
    public static final Pattern ACCOUNT_CODE = Pattern.compile("[A-Za-z0-9._-]{1,20}");
    public static final int EXTERNAL_VOUCHER_LENGTH = 50;

    /** The accounts carrying a line per sponsor. */
    private static final String PER_SPONSOR = QbLedger.SPONSOR_PREPAYMENTS;

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

    /**
     * @param lines            the voucher's lines, by account, debit before credit, then by sponsor
     * @param lineOfEntry      each entry's line number, in the order of {@code entries}
     * @param transactionCount the ledger transactions taken
     */
    public record Summary(List<Line> lines, List<Entry> entries, List<Integer> lineOfEntry, int transactionCount,
        BigDecimal debitTotal, BigDecimal creditTotal, List<String> accounts) {

        public boolean balanced() {
            return debitTotal.compareTo(creditTotal) == 0;
        }
    }

    private record Key(String accountCode, String side, String sponsorId) {}

    private static final Comparator<Key> ORDER = Comparator.comparing(Key::accountCode)
        .thenComparing(key -> DEBIT.equals(key.side()) ? 0 : 1)
        .thenComparing(Key::sponsorId, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** The entries of the template's rows. */
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

    /** The voucher of {@code entries}, with each account's code of the accounting system from {@code mapping}. */
    public static Summary summarize(List<Entry> entries, Map<String, String> mapping) {
        Map<Key, BigDecimal> amounts = new java.util.TreeMap<>(ORDER);
        Map<Key, java.util.Set<String>> transactions = new java.util.HashMap<>();
        java.util.Set<String> allTransactions = new java.util.HashSet<>();
        TreeSet<String> accounts = new TreeSet<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Entry entry : entries) {
            Key key = new Key(entry.accountCode(), entry.direction(), entry.sponsor());
            amounts.merge(key, entry.amount(), BigDecimal::add);
            transactions.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(entry.transactionId());
            allTransactions.add(entry.transactionId());
            accounts.add(entry.accountCode());
            if (DEBIT.equals(entry.direction())) {
                debit = debit.add(entry.amount());
            } else {
                credit = credit.add(entry.amount());
            }
        }
        List<Line> lines = new ArrayList<>();
        Map<Key, Integer> numbers = new LinkedHashMap<>();
        for (Map.Entry<Key, BigDecimal> line : amounts.entrySet()) {
            Key key = line.getKey();
            int lineNo = lines.size() + 1;
            numbers.put(key, lineNo);
            lines.add(new Line(lineNo, key.side(), key.accountCode(), blankToNull(mapping.get(key.accountCode())),
                key.sponsorId(), line.getValue(), transactions.get(key).size()));
        }
        List<Integer> lineOfEntry = entries.stream()
            .map(entry -> numbers.get(new Key(entry.accountCode(), entry.direction(), entry.sponsor()))).toList();
        return new Summary(List.copyOf(lines), List.copyOf(entries), lineOfEntry, allTransactions.size(), debit,
            credit, List.copyOf(accounts));
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
                line.amount().toPlainString(), "QuizBuks " + period.label() + " " + line.accountCode() + " "
                    + line.side()));
        }
        return bytes(csv);
    }

    /** The detail: every posting of every line. */
    public static byte[] detailCsv(String voucherNo, Summary summary) {
        StringBuilder csv = new StringBuilder();
        row(csv, DETAIL_COLUMNS);
        List<Entry> entries = summary.entries();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, Integer>comparing(summary.lineOfEntry()::get)
            .thenComparing(i -> entries.get(i).bookingTime(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(i -> entries.get(i).transactionId()));
        for (int i : order) {
            Entry entry = entries.get(i);
            row(csv, List.of(voucherNo, String.valueOf(summary.lineOfEntry().get(i)), entry.transactionId(),
                String.valueOf(entry.bookingTime()), String.valueOf(entry.recordedAt()), entry.accountCode(),
                entry.direction(), nullToEmpty(entry.sponsor()), entry.amount().toPlainString(),
                nullToEmpty(entry.sourceEntity()), nullToEmpty(entry.sourceId()), nullToEmpty(entry.reference())));
        }
        return bytes(csv);
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

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal decimal(Object value) {
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
