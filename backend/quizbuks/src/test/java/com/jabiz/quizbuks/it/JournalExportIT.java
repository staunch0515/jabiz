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
 * next export, once; so do one booked in the next month before the export (review M3 a), one backdated before the
 * first exported month (M3 e) and reversals, late ones of exported months included (M3 b). Of two exports at once,
 * one exports and the other draws no number (M3 c). Only finance previews, exports (with a second factor) and records
 * the accounting system's voucher number, once. The detail in several parts: {@link JournalLimitsIT}.
 */
class JournalExportIT extends JournalItSupport {

    static final String FORMULA = "=HYPERLINK(\"http://evil.example\",\"x\")";

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
        Instant reconciled = one.end().plus(Duration.ofHours(10));
        clock.set(reconciled);
        // Booked in the second month (on its first UTC day too, for the monthly report below), recorded before the
        // first export's cut-off: the second export's (M3 a).
        String earlyTwo = post(two.start().plus(Duration.ofMinutes(9 * 60 + 30)), "1120", "1110", 11, null,
            "early two");
        assertThat(reconcile(reconciled)).isEqualTo("OK");
        param(QbParams.JOURNAL_ACCOUNT_2120, "bad code");
        assertThat(refusedExport(first.toString())).containsExactly(JournalCodes.ACCOUNT_INVALID);
        param(QbParams.JOURNAL_ACCOUNT_2120, "X2120");
        // Only the next month, and its number is drawn only for a voucher kept (none was drawn by the refusals).
        assertThat(refusedExport(first.plusMonths(1).toString())).containsExactly(JournalCodes.PERIOD_NOT_NEXT);

        advance(JournalPeriods.SETTLE.plusMinutes(1));
        Map<String, Object> ready = preview();
        assertThat(ready).containsEntry("ready", true).containsEntry("reconOk", true).containsEntry("balanced", true)
            .containsEntry("periodEnd", one.end().toString()).containsEntry("detailParts", 1)
            .containsEntry("withinLimits", true).containsEntry("nearLimit", false)
            .containsEntry("openTransactions", 0);
        assertThat((List<Object>) ready.get("unmapped")).isEmpty();
        Instant exportedAt = clock.instant();
        Map<String, Object> exported = export(first.toString());
        String year = String.valueOf(first.getYear());
        assertThat(exported).containsEntry("voucherNo", "QBJ-" + year + "-00001")
            .containsEntry("periodFrom", one.from().toString()).containsEntry("periodTo", one.to().toString());
        assertThat(Instant.parse((String) exported.get("knownAt"))).isEqualTo(exportedAt.minus(JournalPeriods.SETTLE));
        assertThat(money(exported.get("debitTotal"))).isEqualByComparingTo(money(exported.get("creditTotal")));
        assertThat(detailFiles(exported)).singleElement().satisfies(part -> assertThat(part)
            .containsEntry("partNo", 1).containsEntry("rows", ready.get("detailRows")));

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
        String detail = detailText(exported);
        assertThat(detail).contains("\"'" + FORMULA.replace("\"", "\"\"") + "\"").contains(formula)
            .doesNotContain(earlyTwo);
        assertThat(detailRows(exported).stream().map(r -> new BigDecimal(r.get(8)))
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(money(exported.get("debitTotal"))
            .add(money(exported.get("creditTotal"))));
        // In Japan's time (review L4).
        assertThat(detailRows(exported)).allSatisfy(r -> assertThat(r.get(3)).endsWith("+09:00"));
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
        // Backdated before the first exported month, recorded after its export (M3 e).
        String backdated = post(one.start().minus(Duration.ofDays(3)), "1120", "1110", 9, null, "backdated");
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
        assertThat(detailText(second)).contains(late, unbalancing, correcting, earlyTwo, backdated)
            .doesNotContain(withinMargin, formula);
        assertThat(detail).doesNotContain(late).doesNotContain(backdated);

        // The third month: the posting recorded within the margin, once, and reversals (M3 b) of postings exported
        // before: one booked now, one booked back in the first month - both recorded after its export.
        clock.set(three.start().plus(Duration.ofDays(2)));
        String lateReversal = reverse(late, null);
        String backReversal = reverse(formula, one.start().plus(Duration.ofDays(6)));
        clock.set(three.end().plus(Duration.ofHours(4)));
        assertThat(reconcile(clock.instant())).isEqualTo("OK");
        advance(Duration.ofMinutes(1));
        // Two exports at once (M3 c): one waits for the other's lock, then finds the month exported; no number is
        // drawn for it.
        long drawn = vouchersDrawn();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<org.springframework.test.web.reactive.server.EntityExchangeResult<
            Map<String, Object>>>> both = new ArrayList<>();
        String token = finance();
        try {
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> call("QB_JOURNAL_EXPORT", token, body("month", three.label()))
                    .expectBody(MAP).returnResult()));
            }
            List<org.springframework.test.web.reactive.server.EntityExchangeResult<Map<String, Object>>> results =
                new ArrayList<>();
            for (var future : both) {
                results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(results).extracting(r -> r.getStatus().value()).containsExactlyInAnyOrder(200, 422);
            assertThat(results).filteredOn(r -> r.getStatus().value() == 422).singleElement()
                .satisfies(r -> assertThat(codes(r.getResponseBody())).startsWith(JournalCodes.PERIOD_NOT_NEXT));
        } finally {
            pool.shutdownNow();
        }
        assertThat(vouchersDrawn()).isEqualTo(drawn + 1);
        Map<String, Object> third = items("qb.admin.journal-exports", finance(), Map.of()).getFirst();
        assertThat(third).containsEntry("periodFrom", three.from().toString());
        third = new LinkedHashMap<>(third);
        third.put("detailFiles", items("qb.admin.journal-export-parts", finance(), Map.of("params",
            Map.of("exportIds", List.of(third.get("exportId"))))).stream().map(part -> Map.<String, Object>of(
                "partNo", ((Number) part.get("partNo")).intValue(), "fileId", part.get("fileId"),
                "sha256", part.get("sha256"), "rows", part.get("rowCount"))).toList());
        assertThat(detailRows(third).stream().map(r -> r.get(2)).distinct().toList())
            .containsExactlyInAnyOrder(withinMargin, lateReversal, backReversal);
        // 1110 and 1120 each on both sides: the posting within the margin and the two reversals.
        assertThat(third).containsEntry("lineCount", 4).containsEntry("transactionCount", 3);
        // The fourth month has not ended: nothing more.
        assertThat(preview()).containsEntry("month", first.plusMonths(3).toString()).containsEntry("periodEnded",
            false).containsEntry("previousVoucher", third.get("voucherNo"));

        // Every posting so far is in exactly one export: the details together hold each transaction once.
        List<String> taken = new ArrayList<>();
        for (Map<String, Object> each : List.of(exported, second, third)) {
            detailRows(each).stream().map(r -> r.get(2)).distinct().forEach(taken::add);
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
        assertThat(Instant.parse((String) listed.getLast().get("exportedAt"))).isEqualTo(exportedAt);
        assertThat(listed).allSatisfy(r -> assertThat(r).containsEntry("detailPartCount", 1));
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
        assertThat(vouchersDrawn()).isEqualTo(3L);
        // The previous period's end is the one each export kept (review L1).
        assertThat(query("SELECT period_end FROM qb_journal_export_version ORDER BY period_from").stream()
            .map(r -> ((java.sql.Timestamp) r.get("period_end")).toInstant()).toList())
            .containsExactly(one.end(), two.end(), three.end());
        onlyInserted(List.of("qb_journal_export_version", "qb_journal_export_part_version",
            "qb_journal_export_confirmation_version"));
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
            for (String id : List.of("qb.admin.journal-exports", "qb.admin.journal-mapping",
                "qb.admin.journal-export-parts")) {
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
        assertThat(mapping).containsOnlyKeys("1110", "1120", "2110", "2120", "2130", "2140", "4110", "5110",
            "5120", "5130")
            .containsEntry("2110", QbParams.JOURNAL_ACCOUNT_2110);
    }
}
