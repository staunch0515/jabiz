package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance's pages and the monthly reports (docs/quizbuks/plans/Q9c-admin-finance.md, F-08). A month of every kind of
 * posting QuizBuks makes, through its processes (a top-up with Stripe's fee and a refund, one lost to a dispute, a
 * reward with a commission, a transfer sent and settled then partly reversed, a transfer Stripe refused, a payout to
 * the bank and one that failed, and postings of no known kind), between a posting before the month and one after it: the monthly report
 * has every kind on its row with what it moved on each account, the balance sheet opens and closes on the platform's
 * account balances and its checks hold; an issued report is archived and issued again with the same hash. Only
 * finance reads the reports, the finance templates and the attempts' amounts.
 */
class AdminFinanceIT extends PayoutItSupport {

    static final String MONTHLY = "qb.admin.monthly";
    static final String BALANCES = "qb.admin.balances";
    static final String SUMMARY = "qb.admin.finance-summary";

    /** The accounts of the chart (QbLedger) and the monthly report's column for each. */
    static final Map<String, String> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("1110", "stripeBalance");
        COLUMNS.put("1120", "bank");
        COLUMNS.put("2110", "sponsorWallets");
        COLUMNS.put("2120", "takerWallets");
        COLUMNS.put("2130", "inTransit");
        COLUMNS.put("4110", "revenue");
        COLUMNS.put("5110", "paymentFees");
    }

    @BeforeEach
    void transfers() {
        param(QbParams.TRANSFER_FEE, "100");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
    }

    String finance() {
        return tokenOf(user("f-finance"), QbRoles.ADMIN_FINANCE);
    }

    Map<String, Object> period(Instant from, Instant to) {
        return Map.of("from", from.toString(), "to", to.toString());
    }

    Map<String, Object> period(Instant from, Instant to, Instant knownAt) {
        return Map.of("from", from.toString(), "to", to.toString(), "knownAt", knownAt.toString());
    }

    List<Map<String, Object>> report(String id, Instant from, Instant to) {
        return items(id, finance(), Map.of("params", period(from, to), "limit", 100));
    }

    List<Map<String, Object>> report(String id, Instant from, Instant to, Instant knownAt) {
        return items(id, finance(), Map.of("params", period(from, to, knownAt), "limit", 100));
    }

    /** The platform's account balances of everything booked up to {@code asOf}, as recorded at {@code knownAt}. */
    Map<String, BigDecimal> accountBalances(Instant asOf, Instant knownAt) {
        return items("jabiz.ledger.account_balances", admin(), Map.of("params", Map.of("asOf", asOf.toString(),
            "knownAt", knownAt.toString()), "limit", 100)).stream().collect(Collectors.toMap(
            row -> (String) row.get("accountCode"), row -> money(row.get("balance"))));
    }

    /** Posts straight in the ledger, as no process does (no source): debit one account, credit another. */
    void post(Instant bookingTime, String debit, String credit, long amount, String debitParty, String creditParty) {
        Map<String, Object> debitLine = body("accountCode", debit, "direction", "DEBIT", "amount", amount);
        if (debitParty != null) {
            debitLine.put("dimensions", Map.of("party", debitParty));
        }
        Map<String, Object> creditLine = body("accountCode", credit, "direction", "CREDIT", "amount", amount);
        if (creditParty != null) {
            creditLine.put("dimensions", Map.of("party", creditParty));
        }
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            bookingTime.toString(), "description", "Posted by hand (test)", "entries", List.of(debitLine, creditLine)));
    }

    /** The CSV line of a kind in a monthly report's export. */
    static String line(String csv, String category) {
        return csv.lines().filter(l -> l.contains("," + category + ",")).findFirst().orElseThrow();
    }

    String exportCsv(String id, Map<String, Object> params) {
        return new String(client.post().uri("/api/queries/" + id + "/export?format=csv")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, finance())
            .bodyValue(Map.of("params", params)).exchange().expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody(), StandardCharsets.UTF_8);
    }

    Map<String, Object> reversed(String transferId, long amountReversed) {
        Map<String, Object> transfer = new LinkedHashMap<>(StripeSim.TRANSFERS.get("qb-" + transferId));
        transfer.put("amount_reversed", amountReversed);
        transfer.put("reversed", amountReversed > 0);
        return event(eventId(), "transfer.reversed", transfer);
    }

    /** A dispute of a top-up's charge, as Stripe reports it. */
    static Map<String, Object> dispute(String topUpId, long amount, String status) {
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("id", "dp_f08");
        dispute.put("object", "dispute");
        dispute.put("charge", "ch_" + topUpId.replace("-", ""));
        dispute.put("payment_intent", paymentOf(topUpId));
        dispute.put("amount", amount);
        dispute.put("currency", "jpy");
        dispute.put("status", status);
        dispute.put("livemode", false);
        return dispute;
    }

    static Map<String, Object> payout(String id, long amount, String status) {
        Map<String, Object> payout = new LinkedHashMap<>();
        payout.put("id", id);
        payout.put("object", "payout");
        payout.put("amount", amount);
        payout.put("currency", "jpy");
        payout.put("status", status);
        payout.put("livemode", false);
        return payout;
    }

    /** A month's expected row: the kind, how many transactions, their amount and each account's movement. */
    record Expected(String category, long count, long amount, Map<String, Long> moves) {}

    static Expected row(String category, long count, long amount, Object... moves) {
        Map<String, Long> byAccount = new LinkedHashMap<>();
        for (int i = 0; i < moves.length; i += 2) {
            byAccount.put((String) moves[i], ((Number) moves[i + 1]).longValue());
        }
        return new Expected(category, count, amount, byAccount);
    }

    @Test
    void theMonthIsTheLedgerKindByKindAndTheBalancesAddUp() {
        feeRate("0.2");
        String sponsor = newSponsor("s-f08");
        // Before the month: the opening balances.
        credit(sponsor, 700);
        ZonedDateTime month = now().atZone(ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS);
        Instant from = month.toInstant();
        Instant to = month.plusMonths(1).toInstant();
        clock.set(from.plus(Duration.ofHours(1)));

        // A top-up of 10,000 with Stripe's fee of 360, of which 1,000 is refunded.
        String topUpId = openTopUp(sponsor, 10_000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        STRIPE.script("/v1/payment_intents/" + paymentOf(topUpId), StubHttpServer.Answer.json(200, """
            {"id": "%s", "latest_charge": {"id": "ch_f08", "balance_transaction": {"id": "txn_f08", "amount": 10000,
             "fee": 360, "net": 9640, "currency": "jpy"}}}""".formatted(paymentOf(topUpId))));
        runJobs();
        assertThat(money(topUp(topUpId).get("stripe_fee"))).isEqualByComparingTo("360");
        deliver(event(eventId(), "charge.refunded", Map.of("id", "ch_f08", "payment_intent", paymentOf(topUpId),
            "amount", 10_000, "amount_refunded", 1_000, "currency", "jpy", "livemode", false)));
        assertThat(money(topUp(topUpId).get("refunded_amount"))).isEqualByComparingTo("1000");
        // A second top-up of 2,000 whose dispute is lost: taken back.
        String disputed = openTopUp(sponsor, 2_000);
        deliver(event(eventId(), "checkout.session.completed", completed(disputed)));
        deliver(event(eventId(), "charge.dispute.created", dispute(disputed, 2_000, "needs_response")));
        deliver(event(eventId(), "charge.dispute.closed", dispute(disputed, 2_000, "lost")));
        assertThat(money(topUp(disputed).get("dispute_clawback"))).isEqualByComparingTo("2000");
        // The development credit: no source, so of no known kind; as is a posting booked exactly at the month's start.
        credit(sponsor, 300);
        post(from, "1120", "1110", 70, null, null);

        // A reward of 100 with a commission of 20.
        String publicationId = livePublication(sponsor, "F08 capitals", Map.of("regions", List.of("GLOBAL")));
        assertThat(play(u1(), publicationId, true, true)).containsEntry("rewardStatus", "PAID");

        // A transfer of 2,000 (fee 100) sent and settled, then 500 of it reversed by Stripe.
        String first = payee("t-f08-1");
        enabledPayee(first);
        earn(first, 2_000);
        runTransfers();
        String sent = (String) transfersOf(first).getLast().get("transfer_id");
        runJobs();
        assertThat(transfer(sent)).containsEntry("status", "SENT");
        deliver(reversed(sent, 500));
        assertThat(money(transfer(sent).get("reversed_amount"))).isEqualByComparingTo("500");

        // A transfer of 1,500 that Stripe refuses: its sending is reversed.
        String second = payee("t-f08-2");
        enabledPayee(second);
        earn(second, 1_500);
        runTransfers();
        String refused = (String) transfersOf(second).getLast().get("transfer_id");
        StripeSim.target = "qb-" + refused;
        StripeSim.REFUSE.set(1);
        runJobs();
        assertThat(transfer(refused)).containsEntry("status", "FAILED");

        // Two payouts to the bank; the second fails.
        deliver(event(eventId(), "payout.paid", payout("po_f08a", 5_000, "paid")));
        deliver(event(eventId(), "payout.paid", payout("po_f08b", 1_000, "paid")));
        deliver(event(eventId(), "payout.failed", payout("po_f08b", 1_000, "failed")));

        // After the month: not in it, nor is a posting booked exactly at its end. A posting booked in the month
        // but recorded after it is in it as the ledger is now, not as it was known at the month's end.
        clock.set(to.plus(Duration.ofHours(1)));
        credit(sponsor, 50);
        post(to, "1120", "1110", 90, null, null);
        post(from.plus(Duration.ofHours(2)), "1120", "1110", 40, null, null);

        List<Expected> expected = List.of(
            row("TOPUP", 2, 12_000, "1110", 12_000, "2110", -12_000),
            row("BANK_TOPUP", 0, 0),
            row("REFUND", 1, 1_000, "1110", -1_000, "2110", 1_000),
            row("DISPUTE", 1, 2_000, "1110", -2_000, "2110", 2_000),
            row("STRIPE_FEE", 1, 360, "1110", -360, "5110", 360),
            row("REWARD", 1, 100, "2110", 100, "2120", -80, "4110", -20),
            row("TRANSFER_SENT", 2, 3_500, "2120", 3_500, "2130", -3_300, "4110", -200),
            row("TRANSFER_SETTLED", 1, 1_900, "1110", -1_900, "2130", 1_900),
            row("TRANSFER_FAILED", 1, 1_500, "2120", -1_500, "2130", 1_400, "4110", 100),
            row("TRANSFER_REVERSED", 1, 500, "1110", 500, "2120", -500),
            row("PAYOUT", 2, 6_000, "1110", -6_000, "1120", 6_000),
            row("PAYOUT_FAILED", 1, 1_000, "1110", 1_000, "1120", -1_000),
            row("OTHER", 4, 3_870, "1110", 3_730, "1120", 70, "2110", -300, "2120", -3_500));
        // As recorded at the month's end: the late posting is not in it.
        List<Map<String, Object>> monthly = report(MONTHLY, from, to, to);
        assertThat(monthly).extracting(r -> r.get("category"))
            .containsExactlyElementsOf(expected.stream().map(Expected::category).toList());
        for (int i = 0; i < expected.size(); i++) {
            Expected want = expected.get(i);
            Map<String, Object> got = monthly.get(i);
            assertThat(((Number) got.get("transactionCount")).longValue()).as(want.category() + " count")
                .isEqualTo(want.count());
            assertThat(money(got.get("amount"))).as(want.category() + " amount")
                .isEqualByComparingTo(BigDecimal.valueOf(want.amount()));
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, String> column : COLUMNS.entrySet()) {
                BigDecimal moved = money(got.get(column.getValue()));
                assertThat(moved).as(want.category() + " " + column.getKey())
                    .isEqualByComparingTo(BigDecimal.valueOf(want.moves().getOrDefault(column.getKey(), 0L)));
                sum = sum.add(moved);
            }
            assertThat(sum).as(want.category() + " balances").isEqualByComparingTo(BigDecimal.ZERO);
        }
        // OTHER holds the postings of no source only: every posting of a process is of its kind.
        assertThat(((Number) query("SELECT count(*) AS n FROM ledger_transaction_version WHERE booking_time >= ? "
            + "AND booking_time < ? AND created_time <= ? AND source_entity IS NULL", java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(to), java.sql.Timestamp.from(to)).getFirst().get("n")).longValue())
            .isEqualTo(4);
        // As the ledger is now: the late posting is in the month.
        Map<String, Object> otherNow = report(MONTHLY, from, to).getLast();
        assertThat(otherNow).containsEntry("category", "OTHER");
        assertThat(((Number) otherNow.get("transactionCount")).longValue()).isEqualTo(5);
        assertThat(money(otherNow.get("amount"))).isEqualByComparingTo("3910");

        // The balance sheet: opening and closing are the platform's balances, the month's movement is the monthly
        // report's column; the checks hold.
        Map<String, BigDecimal> opening = accountBalances(from.minusNanos(1_000), to);
        Map<String, BigDecimal> closing = accountBalances(to.minusNanos(1_000), to);
        assertThat(opening.get("2110")).isEqualByComparingTo("-700");
        Map<String, Long> debits = Map.of("1110", 17_300L, "1120", 6_070L, "2110", 3_100L, "2120", 3_500L,
            "2130", 3_300L, "4110", 100L, "5110", 360L);
        Map<String, Long> credits = Map.of("1110", 11_330L, "1120", 1_000L, "2110", 12_300L, "2120", 5_580L,
            "2130", 3_300L, "4110", 220L, "5110", 0L);
        List<Map<String, Object>> balances = report(BALANCES, from, to, to);
        Map<String, Map<String, Object>> accounts = balances.stream().filter(r -> "ACCOUNT".equals(r.get("rowKind")))
            .collect(Collectors.toMap(r -> (String) r.get("code"), Function.identity()));
        assertThat(accounts).containsOnlyKeys(COLUMNS.keySet());
        for (String code : COLUMNS.keySet()) {
            Map<String, Object> account = accounts.get(code);
            assertThat(money(account.get("opening"))).as(code + " opening").isEqualByComparingTo(opening.get(code));
            assertThat(money(account.get("closing"))).as(code + " closing").isEqualByComparingTo(closing.get(code));
            assertThat(money(account.get("debit"))).as(code + " debit")
                .isEqualByComparingTo(BigDecimal.valueOf(debits.get(code)));
            assertThat(money(account.get("credit"))).as(code + " credit")
                .isEqualByComparingTo(BigDecimal.valueOf(credits.get(code)));
            BigDecimal movement = monthly.stream().map(r -> money(r.get(COLUMNS.get(code))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(money(account.get("debit")).subtract(money(account.get("credit")))).as(code + " movement")
                .isEqualByComparingTo(movement);
        }
        assertThat(balances).filteredOn(r -> "CHECK".equals(r.get("rowKind")))
            .extracting(r -> r.get("code"), r -> r.get("matches"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("SPONSOR_WALLETS", true),
                org.assertj.core.groups.Tuple.tuple("TAKER_WALLETS", true),
                org.assertj.core.groups.Tuple.tuple("NO_OVERDRAWN_WALLET", true),
                org.assertj.core.groups.Tuple.tuple("BALANCED", true));
        Map<String, Object> sponsors = balances.stream().filter(r -> "SPONSOR_WALLETS".equals(r.get("code")))
            .findFirst().orElseThrow();
        assertThat(money(sponsors.get("closing"))).isEqualByComparingTo(closing.get("2110"));
        assertThat(money(sponsors.get("partyTotal"))).isEqualByComparingTo(closing.get("2110"));

        // Issued: archived with its hash; issued again (after more postings outside the month) the same hash, and
        // the archive reproduces it.
        Map<String, Object> issued = run("REPORT_ISSUE", finance(), body("templateId", MONTHLY, "params",
            period(from, to)));
        assertThat(issued).containsEntry("rowCount", 13).containsEntry("recomputable", true);
        advance(Duration.ofMinutes(5));
        credit(sponsor, 25);
        Map<String, Object> again = run("REPORT_ISSUE", finance(), body("templateId", MONTHLY, "params",
            period(from, to)));
        assertThat(again.get("runId")).isNotEqualTo(issued.get("runId"));
        assertThat(again.get("contentHash")).isEqualTo(issued.get("contentHash"));
        Map<String, Object> verdict = client.post().uri("/api/reports/runs/{id}/verify", issued.get("runId"))
            .header(HttpHeaders.AUTHORIZATION, finance()).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(verdict).containsEntry("verdict", "identical");
        List<Map<String, Object>> runs = client.get().uri("/api/reports/runs?template=" + MONTHLY)
            .header(HttpHeaders.AUTHORIZATION, finance()).exchange().expectStatus().isOk()
            .expectBodyList(MAP).returnResult().getResponseBody();
        assertThat(runs).extracting(r -> r.get("runId")).contains(issued.get("runId"), again.get("runId"));
        assertThat(run("REPORT_ISSUE", finance(), body("templateId", BALANCES, "params", period(from, to))))
            .containsEntry("rowCount", 11);

        // The export is the platform's, of the same rows; as known at the month's end, without the late posting,
        // and so is the run issued as known then (its archived rows, not run again).
        String csv = exportCsv(MONTHLY, period(from, to));
        assertThat(csv.lines().count()).isEqualTo(14);
        assertThat(csv).contains("TRANSFER_REVERSED");
        String known = exportCsv(MONTHLY, period(from, to, to));
        assertThat(line(known, "OTHER")).isNotEqualTo(line(csv, "OTHER")).contains(",4,");
        Map<String, Object> asKnown = run("REPORT_ISSUE", finance(), body("templateId", MONTHLY, "params",
            period(from, to, to)));
        String archived = new String(client.get().uri("/api/reports/runs/{id}/export?format=csv", asKnown.get("runId"))
            .header(HttpHeaders.AUTHORIZATION, finance()).exchange().expectStatus().isOk().expectBody(byte[].class)
            .returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(line(archived, "OTHER")).isEqualTo(line(known, "OTHER"));
        assertBooksBalance();

        // The checks fail when the ledger is not consistent with itself: in the next month, a sponsor wallet line
        // without its sponsor, and a user overdrawn (debits on 2120). The ledger still balances.
        Instant next = to.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        clock.set(to.plus(Duration.ofHours(3)));
        post(to.plus(Duration.ofHours(2)), "1110", "2110", 20, null, null);
        post(to.plus(Duration.ofHours(2)), "2120", "1110", 30, user("t-f08-overdrawn"), null);
        assertThat(report(BALANCES, to, next)).filteredOn(r -> "CHECK".equals(r.get("rowKind")))
            .extracting(r -> r.get("code"), r -> r.get("matches"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("SPONSOR_WALLETS", false),
                org.assertj.core.groups.Tuple.tuple("TAKER_WALLETS", true),
                org.assertj.core.groups.Tuple.tuple("NO_OVERDRAWN_WALLET", false),
                org.assertj.core.groups.Tuple.tuple("BALANCED", true));
        assertThat(report(BALANCES, to, next)).filteredOn(r -> "NO_OVERDRAWN_WALLET".equals(r.get("code")))
            .singleElement().satisfies(r -> assertThat(money(r.get("closing"))).isEqualByComparingTo("30"));
    }

    @Test
    void onlyFinanceReadsTheMoney() {
        String content = tokenOf(user("f-content"), QbRoles.ADMIN_CONTENT);
        String sponsor = sponsorToken(newSponsor("s-f08-matrix"));
        String finance = finance();
        Map<String, Object> month = Map.of("params", period(Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z")));
        Map<String, Map<String, Object>> templates = Map.of(MONTHLY, month, BALANCES, month, SUMMARY,
            Map.of("params", Map.of("staleBefore", now().minus(Duration.ofHours(1)).toString())),
            "qb.admin.transfers", Map.of(), "qb.admin.topups", Map.of(), "qb.admin.attempt-rewards", Map.of());
        templates.forEach((id, request) -> {
            for (String refused : List.of(content, sponsor)) {
                client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, refused).bodyValue(request).exchange()
                    .expectStatus().isForbidden();
            }
            template(id, finance, request);
        });
        client.post().uri("/api/queries/" + MONTHLY + "/export?format=xlsx").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, content).bodyValue(month).exchange().expectStatus().isForbidden();
        // The attempts' amounts: finance's dataset only; the default dataset is read by nobody (C-2).
        queryDataset(QbPlay.FINANCE_ATTEMPTS, content).expectStatus().isForbidden();
        queryDataset(QbPlay.FINANCE_ATTEMPTS, sponsor).expectStatus().isForbidden();
        queryDataset(QbPlay.FINANCE_ATTEMPTS, finance).expectStatus().isOk();
        for (String token : List.of(content, sponsor, finance)) {
            queryDataset(QbPlay.defaultDataset(QbPlay.ATTEMPT), token).expectStatus().isForbidden();
        }
        // Issuing needs the template's permissions as well: the content administrator has neither.
        assertThat(client.post().uri("/api/processes/REPORT_ISSUE/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, content).bodyValue(body("templateId", MONTHLY, "params",
                month.get("params"))).exchange().returnResult(String.class).getStatus().value()).isEqualTo(403);
        client.get().uri("/api/reports/runs?template=" + MONTHLY).header(HttpHeaders.AUTHORIZATION, content)
            .exchange().expectStatus().isForbidden();

        // The overview's numbers: one row, the transfers' new columns. The reconciliation and the money tasks are
        // qb.reconcile's (and the tasks task.read's): finance-read alone is refused them, and the release too.
        Map<String, Object> summary = items(SUMMARY, finance, Map.of("params", Map.of("staleBefore",
            now().minus(Duration.ofHours(1)).toString()))).getFirst();
        assertThat(summary).containsKeys("sponsorBalance", "transfersInReview", "transfersInDoubt", "heldTakers")
            .doesNotContainKeys("lastRunStatus", "openTasks");
        assertThat(items("qb.admin.recon-summary", finance, Map.of())).singleElement()
            .satisfies(r -> assertThat(r).containsKeys("lastRunStatus", "openTasks"));
        String readOnly = TestTokens.bearer(tokens, "f-read-only", "qb.admin.finance.read", "qb.admin.users.read");
        template(SUMMARY, readOnly, Map.of());
        for (String id : List.of("qb.admin.recon-summary", "qb.admin.recon-runs", "qb.admin.recon-items")) {
            for (String refused : List.of(readOnly, content)) {
                client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, refused).bodyValue(Map.of()).exchange()
                    .expectStatus().isForbidden();
            }
        }
        assertThat(client.post().uri("/api/processes/QB_TRANSFER_HOLD_RELEASE/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, readOnly)
            .bodyValue(body("userId", user("t-f08-matrix"))).exchange().returnResult(String.class).getStatus()
            .value()).isEqualTo(403);
        assertThat(items("qb.admin.transfers", finance, Map.of("filters", List.of(Map.of("field", "held", "op", "eq",
            "value", true))))).allSatisfy(r -> assertThat(r).containsEntry("held", true));
    }

    @Test
    void theOverviewCountsWhatWaits() {
        param(QbParams.TRANSFER_REVIEW_ABOVE, "1000");
        try {
            String taker = payee("t-f08-review");
            enabledPayee(taker);
            earn(taker, 1_500);
            runTransfers();
            String transferId = (String) transfersOf(taker).getLast().get("transfer_id");
            assertThat(transfer(transferId)).containsEntry("status", "REVIEW");
            String stale = now().minus(Duration.ofHours(1)).toString();
            Map<String, Object> summary = items(SUMMARY, finance(), Map.of("params", Map.of("staleBefore", stale)))
                .getFirst();
            assertThat(((Number) summary.get("transfersInReview")).longValue()).isGreaterThanOrEqualTo(1);
            assertThat(money(summary.get("takerBalance"))).isGreaterThanOrEqualTo(new BigDecimal("1500"));
            // The review page reads the transfer by its key.
            assertThat(items("qb.admin.transfers", finance(), Map.of("params", Map.of("staleBefore", stale),
                "filters", List.of(Map.of("field", "transferId", "op", "eq", "value", transferId)))))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("status", "REVIEW")
                    .containsEntry("stale", false).containsEntry("held", false)
                    .containsEntry("approvalRequestId", transfer(transferId).get("approval_request_id")));
            // Rejected: the taker is held until released.
            run("APPROVAL_DECIDE", transferReviewer(), body("requestId", transfer(transferId)
                .get("approval_request_id"), "decision", "REJECT", "reason", "Not now"));
            deliverEvents();
            assertThat(items("qb.admin.transfers", finance(), Map.of("filters", List.of(Map.of("field", "userId",
                "op", "eq", "value", taker))))).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("status", "REJECTED").containsEntry("held", true));
            releaseHold(taker);
            assertThat(items("qb.admin.transfers", finance(), Map.of("filters", List.of(Map.of("field", "userId",
                "op", "eq", "value", taker))))).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("held", false));
        } finally {
            param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        }
    }
}
