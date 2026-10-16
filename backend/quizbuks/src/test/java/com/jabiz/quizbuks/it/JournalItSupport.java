package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The journal export's integration tests: finance's calls, postings by hand and the kept CSV files read back. */
abstract class JournalItSupport extends PayoutItSupport {

    String finance() {
        return tokenOf(user("j-finance"), QbRoles.ADMIN_FINANCE);
    }

    Map<String, Object> preview() {
        return run("QB_JOURNAL_PREVIEW", finance(), Map.of());
    }

    Map<String, Object> export(String month) {
        return run("QB_JOURNAL_EXPORT", finance(), body("month", month));
    }

    List<Object> refusedExport(String month) {
        return codes(refused("QB_JOURNAL_EXPORT", finance(), body("month", month), 422));
    }

    /** Runs the daily reconciliation at {@code at}, after delivering what events are pending; its status. */
    String reconcile(Instant at) {
        deliverEvents();
        Reconciliation.ReconcileOutput output = (Reconciliation.ReconcileOutput) systemRun("QB_RECONCILE_RUN",
            body("scheduledTime", at.toString()));
        return output.status();
    }

    /** Posts straight in the ledger, as finance's corrections would; {@code party} on the 2110 side. */
    String post(Instant bookingTime, String debit, String credit, long amount, String party, String reference) {
        Map<String, Object> debitLine = body("accountCode", debit, "direction", "DEBIT", "amount", amount);
        Map<String, Object> creditLine = body("accountCode", credit, "direction", "CREDIT", "amount", amount);
        if (party != null) {
            (debit.equals("2110") ? debitLine : creditLine).put("dimensions", Map.of("party", party));
        }
        return post(bookingTime, reference, List.of(debitLine, creditLine));
    }

    String post(Instant bookingTime, String reference, List<Map<String, Object>> entries) {
        Map<String, Object> input = body("bookingTime", bookingTime.toString(), "description", "Posted by hand (test)",
            "entries", entries);
        if (reference != null) {
            input.put("reference", reference);
        }
        return (String) run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), input)
            .get("transactionId");
    }

    /** Reverses a transaction, booked at {@code bookingTime} (null: now); the reversing transaction. */
    String reverse(String transactionId, Instant bookingTime) {
        Map<String, Object> input = body("transactionId", transactionId, "reason", "Reversed (test)");
        if (bookingTime != null) {
            input.put("bookingTime", bookingTime.toString());
        }
        return (String) run("LEDGER_REVERSE", TestTokens.bearer(tokens, "ledger-test", "ledger.reverse"), input)
            .get("transactionId");
    }

    byte[] download(String fileId, String token) {
        return client.get().uri("/api/generated-files/{id}", fileId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** The rows of a CSV whose every cell is quoted (as the export writes it), without the byte order mark. */
    static List<List<String>> csv(byte[] bytes) {
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\r\n")) {
            List<String> cells = new ArrayList<>();
            int i = 0;
            while (i < line.length()) {
                assertThat(line.charAt(i)).as("every cell is quoted: " + line).isEqualTo('"');
                StringBuilder cell = new StringBuilder();
                i++;
                while (true) {
                    char c = line.charAt(i);
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                    } else if (c == '"') {
                        i++;
                        break;
                    } else {
                        cell.append(c);
                        i++;
                    }
                }
                cells.add(cell.toString());
                if (i < line.length()) {
                    assertThat(line.charAt(i)).isEqualTo(',');
                    i++;
                }
            }
            rows.add(cells);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> detailFiles(Map<String, Object> export) {
        return (List<Map<String, Object>>) export.get("detailFiles");
    }

    /** Each part of an export's detail, exactly as kept (its hash), in order. */
    List<byte[]> detailParts(Map<String, Object> export) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        int partNo = 1;
        for (Map<String, Object> part : detailFiles(export)) {
            assertThat(part).containsEntry("partNo", partNo++);
            byte[] bytes = download((String) part.get("fileId"), finance());
            assertThat(sha256(bytes)).isEqualTo(part.get("sha256"));
            assertThat(csv(bytes)).hasSize(((Number) part.get("rows")).intValue() + 1);
            parts.add(bytes);
        }
        return parts;
    }

    /** The rows of an export's detail, every part's, without their headers. */
    List<List<String>> detailRows(Map<String, Object> export) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (byte[] part : detailParts(export)) {
            List<List<String>> csv = csv(part);
            assertThat(csv.getFirst()).startsWith("voucher_no", "line_no", "transaction_id");
            rows.addAll(csv.subList(1, csv.size()));
        }
        return rows;
    }

    /** The text of an export's detail, every part's. */
    String detailText(Map<String, Object> export) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte[] part : detailParts(export)) {
            text.append(new String(part, StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    /** Each account's debit less credit on the voucher, from its lines. */
    @SuppressWarnings("unchecked")
    static Map<String, BigDecimal> net(Map<String, Object> preview) {
        Map<String, BigDecimal> net = new java.util.TreeMap<>();
        for (Map<String, Object> line : (List<Map<String, Object>>) preview.get("lines")) {
            BigDecimal amount = money(line.get("amount"));
            net.merge((String) line.get("accountCode"), "DEBIT".equals(line.get("side")) ? amount : amount.negate(),
                BigDecimal::add);
        }
        return net;
    }

    Map<String, BigDecimal> accountBalances(Instant asOf, Instant knownAt) {
        return items("jabiz.ledger.account_balances", admin(), Map.of("params", Map.of("asOf", asOf.toString(),
            "knownAt", knownAt.toString()), "limit", 100)).stream()
            .filter(row -> money(row.get("balance")).signum() != 0)
            .collect(Collectors.toMap(row -> (String) row.get("accountCode"), row -> money(row.get("balance")),
                (a, b) -> a, java.util.TreeMap::new));
    }

    void mapAll(String... except) {
        for (String key : QbParams.JOURNAL_ACCOUNTS) {
            if (!List.of(except).contains(key)) {
                param(key, "X" + key.substring(QbParams.JOURNAL_ACCOUNT_PREFIX.length()));
            }
        }
    }

    /** The voucher numbers drawn so far. */
    static long vouchersDrawn() {
        return ((Number) query("SELECT count(*) AS n FROM sys_number_assignment WHERE sequence_name = 'qb.journal'")
            .getFirst().get("n")).longValue();
    }

    /** No row of these tables was ever updated or deleted, and each is guarded against it. */
    static void onlyInserted(List<String> tables) {
        for (String table : tables) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
    }
}
