package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.journal.JournalCodes;
import com.jabiz.quizbuks.journal.JournalPeriods;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal export (docs/quizbuks/plans/Q9d-journal-receipts.md, F-01 – F-03): three months exported in order, each
 * once, as summary vouchers numbered without gaps; nothing is exported while a month is open, the reconciliation has
 * not run cleanly after it or an account has no code of the accounting system; the voucher agrees with the ledger's
 * balances and the monthly report; its CSV files are kept exactly as made, quoted throughout and safe from formulas; a
 * posting recorded late (booked in an exported month) and one recorded within the cut-off's margin each come in the
 * next export, once. Only finance previews, exports (with a second factor) and records the accounting system's
 * voucher number, once.
 */
class JournalExportIT extends PayoutItSupport {

    static final String FORMULA = "=HYPERLINK(\"http://evil.example\",\"x\")";

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

    /** Posts straight in the ledger, as finance's corrections would: between the Stripe balance and the bank. */
    String post(Instant bookingTime, String debit, String credit, long amount, String party, String reference) {
        Map<String, Object> debitLine = body("accountCode", debit, "direction", "DEBIT", "amount", amount);
        Map<String, Object> creditLine = body("accountCode", credit, "direction", "CREDIT", "amount", amount);
        if (party != null) {
            (debit.equals("2110") ? debitLine : creditLine).put("dimensions", Map.of("party", party));
        }
        Map<String, Object> input = body("bookingTime", bookingTime.toString(), "description", "Posted by hand (test)",
            "entries", List.of(debitLine, creditLine));
        if (reference != null) {
            input.put("reference", reference);
        }
        return (String) run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), input)
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

    @Test
    @SuppressWarnings("unchecked")
    void everyMonthIsExportedOnceInOrderAndEveryPostingInExactlyOneExport() throws Exception {
        feeRate("0.2");
        // The first month: the one of the earliest posting, in Japan's dates. Its postings fall in the same UTC month.
        ZonedDateTime jst = now().atZone(JournalPeriods.ZONE).plusMonths(1).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS);
        YearMonth first = YearMonth.from(jst);
        JournalPeriods.Period one = JournalPeriods.month(first);
        JournalPeriods.Period two = JournalPeriods.month(first.plusMonths(1));
        JournalPeriods.Period three = JournalPeriods.month(first.plusMonths(2));
        clock.set(one.start().plus(Duration.ofDays(2)));

        String sponsor = newSponsor("s-j-one");
        String other = newSponsor("s-j-two");
        credit(sponsor, 3_000);
        credit(other, 1_000);
        String publicationId = livePublication(sponsor, "Journal capitals", Map.of("regions", List.of("GLOBAL")));
        assertThat(play(u1(), publicationId, true, true)).containsEntry("rewardStatus", "PAID");
        String formula = post(one.start().plus(Duration.ofDays(2)), "1120", "1110", 70, null, FORMULA);

        // Open, unreconciled and unmapped: the preview says so, and the export refuses all three at once. (A preview
        // or export takes what was recorded up to its cut-off, a margin before it.)
        advance(JournalPeriods.SETTLE.plusMinutes(1));
        Map<String, Object> early = preview();
        assertThat(early).containsEntry("month", first.toString()).containsEntry("periodEnded", false)
            .containsEntry("reconOk", false).containsEntry("ready", false).containsEntry("previousVoucher", null);
        assertThat((List<Object>) early.get("unmapped")).containsExactly("1110", "1120", "2110", "2120", "4110");
        assertThat(refusedExport(first.toString())).containsExactly(JournalCodes.PERIOD_OPEN,
            JournalCodes.RECON_NOT_OK, JournalCodes.ACCOUNT_UNMAPPED);

        // Ended, but the only reconciliation ran before its end: still refused; after it, mapped but for one account.
        Instant beforeEnd = one.end().minus(Duration.ofHours(1));
        clock.set(beforeEnd);
        assertThat(reconcile(beforeEnd)).isEqualTo("OK");
        clock.set(one.end().plus(Duration.ofHours(1)));
        mapAll(QbParams.JOURNAL_ACCOUNT_2120);
        assertThat(refusedExport(first.toString())).containsExactly(JournalCodes.RECON_NOT_OK,
            JournalCodes.ACCOUNT_UNMAPPED);
        Instant reconciled = one.end().plus(Duration.ofHours(4));
        clock.set(reconciled);
        assertThat(reconcile(reconciled)).isEqualTo("OK");
        param(QbParams.JOURNAL_ACCOUNT_2120, "bad code");
        assertThat(refusedExport(first.toString())).containsExactly(JournalCodes.ACCOUNT_INVALID);
        param(QbParams.JOURNAL_ACCOUNT_2120, "X2120");
        // Only the next month, and its number is drawn only for a voucher kept (none was drawn by the refusals).
        assertThat(refusedExport(first.plusMonths(1).toString())).containsExactly(JournalCodes.PERIOD_NOT_NEXT);

        Map<String, Object> ready = preview();
        assertThat(ready).containsEntry("ready", true).containsEntry("reconOk", true).containsEntry("balanced", true);
        assertThat((List<Object>) ready.get("unmapped")).isEmpty();
        Instant exportedAt = clock.instant();
        Map<String, Object> exported = export(first.toString());
        String year = String.valueOf(first.getYear());
        assertThat(exported).containsEntry("voucherNo", "QBJ-" + year + "-00001")
            .containsEntry("periodFrom", one.from().toString()).containsEntry("periodTo", one.to().toString());
        assertThat(Instant.parse((String) exported.get("knownAt"))).isEqualTo(exportedAt.minus(JournalPeriods.SETTLE));
        assertThat(money(exported.get("debitTotal"))).isEqualByComparingTo(money(exported.get("creditTotal")));

        // The voucher is the ledger: each account's net is its balance at the month's end, as recorded at the
        // cut-off (the first export takes everything before), and the monthly report's movement in the same month.
        Map<String, BigDecimal> voucher = net(ready);
        Map<String, BigDecimal> balances = accountBalances(one.end().minusNanos(1_000),
            exportedAt.minus(JournalPeriods.SETTLE));
        assertThat(voucher).isEqualTo(balances);
        Instant utcFrom = one.start().atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            .plusMonths(1).toInstant();
        List<Map<String, Object>> monthly = items("qb.admin.monthly", finance(), Map.of("params", Map.of("from",
            utcFrom.toString(), "to", utcFrom.atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toString()),
            "limit", 100));
        Map<String, String> columns = Map.of("1110", "stripeBalance", "1120", "bank", "2110", "sponsorWallets",
            "2120", "takerWallets", "4110", "revenue");
        columns.forEach((code, column) -> assertThat(monthly.stream().map(r -> money(r.get(column)))
            .reduce(BigDecimal.ZERO, BigDecimal::add)).as(code).isEqualByComparingTo(voucher.get(code)));
        // 2110 a line per sponsor and side; the takers' 2120 summed.
        List<Map<String, Object>> lines = (List<Map<String, Object>>) ready.get("lines");
        assertThat(lines).filteredOn(l -> "2110".equals(l.get("accountCode")))
            .extracting(l -> l.get("side"), l -> l.get("sponsorId"))
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("DEBIT", sponsor),
                org.assertj.core.groups.Tuple.tuple("CREDIT", sponsor),
                org.assertj.core.groups.Tuple.tuple("CREDIT", other));
        assertThat(lines).filteredOn(l -> "2120".equals(l.get("accountCode"))).singleElement()
            .satisfies(l -> assertThat(l).containsEntry("sponsorId", null).containsEntry("externalCode", "X2120"));

        // The files, exactly as kept: the voucher's lines only, the detail every posting; quoted and formula-safe.
        byte[] file = download((String) exported.get("fileId"), finance());
        assertThat(sha256(file)).isEqualTo(exported.get("fileSha256"));
        List<List<String>> rows = csv(file);
        assertThat(rows.getFirst()).containsExactly("voucher_no", "date", "line_no", "side", "account_code",
            "platform_account", "sponsor_id", "amount", "memo");
        assertThat(rows).hasSize(lines.size() + 1);
        assertThat(rows.subList(1, rows.size())).allSatisfy(row -> assertThat(row.subList(0, 2))
            .containsExactly("QBJ-" + year + "-00001", one.to().toString()));
        byte[] detail = download((String) exported.get("detailFileId"), finance());
        assertThat(sha256(detail)).isEqualTo(exported.get("detailSha256"));
        String detailText = new String(detail, StandardCharsets.UTF_8);
        assertThat(detailText).contains("\"'" + FORMULA.replace("\"", "\"\"") + "\"").contains(formula);
        List<List<String>> details = csv(detail);
        assertThat(details.stream().skip(1).map(r -> new BigDecimal(r.get(8)))
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(money(exported.get("debitTotal"))
            .add(money(exported.get("creditTotal"))));
        // Only finance reads the files: the content administrator may not read generated files at all, and one who
        // may but without finance's permission does not find them.
        client.get().uri("/api/generated-files/{id}", exported.get("fileId"))
            .header(HttpHeaders.AUTHORIZATION, tokenOf(user("j-content"), QbRoles.ADMIN_CONTENT)).exchange()
            .expectStatus().isForbidden();
        client.get().uri("/api/generated-files/{id}", exported.get("fileId"))
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "j-files", "file.generated.read")).exchange()
            .expectStatus().isNotFound();

        // The same month again is not the next one (which has not ended either).
        assertThat(refusedExport(first.toString())).startsWith(JournalCodes.PERIOD_NOT_NEXT);

        // The second month: a posting booked in the first but recorded now (late), the month's own postings, and
        // one recorded within the cut-off's margin before the export.
        clock.set(two.start().plus(Duration.ofDays(3)));
        String late = post(one.start().plus(Duration.ofDays(5)), "1120", "1110", 40, null, "late");
        credit(other, 500);
        // A difference: the reconciliation finds it after the month, and the export waits until it is gone.
        String unbalancing = post(two.start().plus(Duration.ofDays(3)), "1110", "2110", 25, other, "diff");
        clock.set(two.end().plus(Duration.ofHours(4)));
        assertThat(reconcile(clock.instant())).isEqualTo("DIFF");
        assertThat(refusedExport(two.label())).containsExactly(JournalCodes.RECON_NOT_OK);
        String correcting = post(two.start().plus(Duration.ofDays(3)), "2110", "1110", 25, other, "diff fixed");
        advance(Duration.ofHours(1));
        assertThat(reconcile(clock.instant())).isEqualTo("OK");
        String withinMargin = post(two.start().plus(Duration.ofDays(4)), "1120", "1110", 15, null, "margin");
        advance(Duration.ofMinutes(5));
        Map<String, Object> second = export(two.label());
        String secondNo = first.plusMonths(1).getYear() == first.getYear() ? "QBJ-" + year + "-00002"
            : "QBJ-" + first.plusMonths(1).getYear() + "-00001";
        assertThat(second).containsEntry("voucherNo", secondNo);
        String secondDetail = new String(download((String) second.get("detailFileId"), finance()),
            StandardCharsets.UTF_8);
        assertThat(secondDetail).contains(late, unbalancing, correcting).doesNotContain(withinMargin, formula);
        assertThat(new String(detail, StandardCharsets.UTF_8)).doesNotContain(late);

        // The third month: nothing of its own; the posting recorded within the margin, once.
        clock.set(three.end().plus(Duration.ofHours(4)));
        assertThat(reconcile(clock.instant())).isEqualTo("OK");
        Map<String, Object> third = export(three.label());
        String thirdDetail = new String(download((String) third.get("detailFileId"), finance()),
            StandardCharsets.UTF_8);
        assertThat(thirdDetail).contains(withinMargin).doesNotContain(late, formula);
        assertThat(third).containsEntry("lineCount", 2).containsEntry("transactionCount", 1);
        // The fourth month has not ended: nothing more.
        assertThat(preview()).containsEntry("month", first.plusMonths(3).toString()).containsEntry("periodEnded",
            false).containsEntry("previousVoucher", third.get("voucherNo"));

        // Every posting so far is in exactly one export: the details together hold each transaction once.
        List<String> taken = new ArrayList<>();
        for (Map<String, Object> each : List.of(exported, second, third)) {
            csv(download((String) each.get("detailFileId"), finance())).stream().skip(1)
                .map(r -> r.get(2)).distinct().forEach(taken::add);
        }
        List<Object> booked = query("SELECT DISTINCT transaction_id FROM ledger_transaction_version WHERE "
            + "booking_time < ?", java.sql.Timestamp.from(three.end())).stream().map(r -> r.get("transaction_id"))
            .map(String::valueOf).collect(Collectors.toList());
        assertThat(taken).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(booked.stream()
            .map(String::valueOf).toList());

        // The list, and the accounting system's voucher number, recorded once.
        List<Map<String, Object>> listed = items("qb.admin.journal-exports", finance(), Map.of());
        assertThat(listed).extracting(r -> r.get("voucherNo")).containsExactly(third.get("voucherNo"),
            second.get("voucherNo"), exported.get("voucherNo"));
        Map<String, Object> confirmed = run("QB_JOURNAL_EXPORT_CONFIRM", finance(), body("exportId",
            exported.get("exportId"), "externalVoucherNo", " MF-1001 "));
        assertThat(confirmed).containsEntry("externalVoucherNo", "MF-1001");
        assertThat(codes(refused("QB_JOURNAL_EXPORT_CONFIRM", finance(), body("exportId", exported.get("exportId"),
            "externalVoucherNo", "MF-1002"), 422))).containsExactly(JournalCodes.ALREADY_CONFIRMED);
        assertThat(items("qb.admin.journal-exports", finance(), Map.of()).getLast())
            .containsEntry("externalVoucherNo", "MF-1001");
        call("QB_JOURNAL_EXPORT_CONFIRM", finance(), body("exportId", java.util.UUID.randomUUID().toString(),
            "externalVoucherNo", "MF-1003")).expectStatus().isNotFound();

        // Written once, only inserted; the voucher numbers have no gap.
        assertThat(query("SELECT voucher_no FROM qb_journal_export_version ORDER BY period_from").stream()
            .map(r -> r.get("voucher_no")).toList()).containsExactly(exported.get("voucherNo"),
            second.get("voucherNo"), third.get("voucherNo"));
        assertThat(query("SELECT count(*) AS n FROM sys_number_assignment WHERE sequence_name = 'qb.journal'")
            .getFirst().get("n")).isEqualTo(3L);
        for (String table : List.of("qb_journal_export_version", "qb_journal_export_confirmation_version")) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
        assertBooksBalance();
    }

    @Test
    void onlyFinanceExportsWithASecondFactor() {
        String content = tokenOf(user("j-content-matrix"), QbRoles.ADMIN_CONTENT);
        String sponsor = sponsorToken(newSponsor("s-j-matrix"));
        for (String refused : List.of(content, sponsor)) {
            call("QB_JOURNAL_PREVIEW", refused, Map.of()).expectStatus().isForbidden();
            call("QB_JOURNAL_EXPORT", refused, body("month", "2026-01")).expectStatus().isForbidden();
            call("QB_JOURNAL_EXPORT_CONFIRM", refused, body("exportId", java.util.UUID.randomUUID().toString(),
                "externalVoucherNo", "X")).expectStatus().isForbidden();
            for (String id : List.of("qb.admin.journal-exports", "qb.admin.journal-mapping")) {
                client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, refused).bodyValue(Map.of()).exchange()
                    .expectStatus().isForbidden();
            }
        }
        // Finance without a recent second factor: refused before anything runs.
        String noMfa = TestTokens.withoutMfa(tokens, user("j-finance-no-mfa"),
            QbRoles.of(QbRoles.ADMIN_FINANCE).permissions().toArray(String[]::new));
        assertThat(String.valueOf(refused("QB_JOURNAL_EXPORT", noMfa, body("month", "2026-01"), 403)))
            .contains("MFA_REQUIRED");
        // The mapping: every account, with its parameter (empty until set).
        Map<String, Object> mapping = new LinkedHashMap<>();
        items("qb.admin.journal-mapping", finance(), Map.of()).forEach(r -> mapping.put((String) r.get("accountCode"),
            r.get("paramKey")));
        assertThat(mapping).containsOnlyKeys("1110", "1120", "2110", "2120", "2130", "4110", "5110")
            .containsEntry("2110", QbParams.JOURNAL_ACCOUNT_2110);
    }
}
