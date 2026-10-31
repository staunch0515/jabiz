package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sponsor's transactions (docs/quizbuks/plans/Q9b-sponsor-transactions.md; M-50 – M-52): the rewards their
 * publications paid or owe and their top-ups, through {@code qb.sponsor.transactions}, the totals of the same rows
 * through {@code qb.sponsor.transaction-summary} and the platform's export of the list. A sponsor sees their own rows
 * only, however they filter; a taker is shown by the name of their profile, never by anything of their answers; nobody
 * without {@code qb.sponsor.finance.read} reads them.
 */
class SponsorTransactionsIT extends TopUpItSupport {

    static final String LIST = "qb.sponsor.transactions";
    static final String SUMMARY = "qb.sponsor.transaction-summary";

    /** The columns of the list, and nothing else (no answers, result, address). */
    static final Set<String> COLUMNS = Set.of("entryId", "type", "status", "amount", "refunded", "occurredAt",
        "publicationId", "publicationName", "userKey", "userName", "score", "fullScore");

    String sponsor;
    String other;
    String paidTopUp;
    String openTopUp;
    String expiredTopUp;
    String failedTopUp;
    String big;
    String small;
    String otherPublication;
    String mia;
    String noah;
    String olga;
    String paul;
    String stranger;
    String miaName;
    Instant mark;

    @BeforeEach
    void transactions() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        sponsor = newSponsor("s-tx-" + run);
        other = newSponsor("s-tx-other-" + run);

        // Top-ups: one paid, one open, one expired at Stripe, one whose checkout failed.
        paidTopUp = openTopUp(sponsor, 1000);
        deliver(event(eventId(), "checkout.session.completed", completed(paidTopUp)));
        openTopUp = openTopUp(sponsor, 2000);
        expiredTopUp = openTopUp(sponsor, 3000);
        deliver(event(eventId(), "checkout.session.expired", completed(expiredTopUp, s -> {
            s.put("status", "expired");
            s.put("payment_status", "unpaid");
        })));
        failedTopUp = openTopUp(sponsor, 4000);
        execute("UPDATE qb_topup SET status = 'FAILED', failure_code = 'test' WHERE topup_id = ?", failedTopUp);
        // Part of the paid one refunded (as Stripe's refund would record it): the totals count it net.
        execute("UPDATE qb_topup SET refunded_amount = 300 WHERE topup_id = ?", paidTopUp);
        assertThat(topUp(paidTopUp)).containsEntry("status", "PAID");
        assertThat(topUp(expiredTopUp)).containsEntry("status", "EXPIRED");

        advance(Duration.ofMinutes(1));
        mark = now();
        advance(Duration.ofMinutes(1));

        // Rewards: 1,000 Kudos pay Paul's 10 and Mia's 600; Noah's 600 waits for a top-up; Olga earns nothing.
        miaName = "Mia " + run;
        mia = namedTaker("t-tx-mia-" + run, miaName);
        noah = namedTaker("t-tx-noah-" + run, "Noah " + run);
        olga = namedTaker("t-tx-olga-" + run, "Olga " + run);
        paul = namedTaker("t-tx-paul-" + run, "Paul " + run);
        big = livePublication(sponsor, "Big prize " + run, Map.of("tiers", List.of(
            body("fromScore", 0, "toScore", 2, "reward", 0), body("fromScore", 3, "toScore", 4, "reward", 10),
            body("fromScore", 5, "toScore", 5, "reward", 600))));
        small = livePublication(sponsor, "Small prize " + run);
        assertThat(play(paul, small, false, true)).containsEntry("rewardStatus", "PAID");
        String miaAttempt = (String) start(taker(mia), big).get("attemptId");
        String noahAttempt = (String) start(taker(noah), big).get("attemptId");
        String olgaAttempt = (String) start(taker(olga), big).get("attemptId");
        assertThat(submit(taker(mia), miaAttempt, answers(true, true))).containsEntry("rewardStatus", "PAID");
        assertThat(submit(taker(noah), noahAttempt, answers(true, true))).containsEntry("rewardStatus", "PENDING");
        assertThat(submit(taker(olga), olgaAttempt, answers(false, false)).get("rewardStatus"))
            .isIn("NONE", null);

        // Another sponsor's reward and top-up.
        credit(other, 100);
        otherPublication = livePublication(other, "Theirs " + run);
        stranger = namedTaker("t-tx-stranger-" + run, "Stranger " + run);
        String strangerAttempt = (String) start(taker(stranger), otherPublication).get("attemptId");
        String miaThere = (String) start(taker(mia), otherPublication).get("attemptId");
        assertThat(submit(taker(stranger), strangerAttempt, answers(true, true))).containsEntry("rewardStatus", "PAID");
        assertThat(submit(taker(mia), miaThere, answers(true, true))).containsEntry("rewardStatus", "PENDING");
        openTopUp(other, 1000);
    }

    /** A verified taker in Japan with a profile of that name. */
    String namedTaker(String userName, String displayName) {
        String userId = user(userName);
        run("QB_PROFILE_SAVE", com.jabiz.runtime.test.TestTokens.bearer(tokens, userId, QbPermissions.ME),
            body("displayName", displayName, "countryCode", "JP", "preferredCurrency", "JPY"));
        return userId;
    }

    List<Map<String, Object>> list(String token, Map<String, Object> params) {
        return items(LIST, token, Map.of("params", params, "limit", 200));
    }

    Map<String, Object> summary(String token, Map<String, Object> params) {
        List<Map<String, Object>> rows = items(SUMMARY, token, Map.of("params", params));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    static Map<String, Object> rowOf(List<Map<String, Object>> rows, String entryId) {
        return rows.stream().filter(row -> entryId.equals(row.get("entryId"))).findFirst().orElseThrow();
    }

    static List<Object> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("entryId")).toList();
    }

    /** The key of the taker whose name starts with {@code name} in the rows. */
    static String keyOf(List<Map<String, Object>> rows, String name) {
        return rows.stream().filter(row -> String.valueOf(row.get("userName")).startsWith(name)).findFirst()
            .map(row -> String.valueOf(row.get("userKey"))).orElseThrow();
    }

    String attemptOf(String userId, String publicationId) {
        return String.valueOf(query("SELECT attempt_id FROM qb_attempt WHERE user_id = ? AND publication_id = ?::uuid",
            userId, publicationId).getFirst().get("attempt_id"));
    }

    @Test
    void theSponsorSeesTheirRewardsAndTopUpsWithTheirStates() {
        List<Map<String, Object>> rows = list(sponsorToken(sponsor), Map.of());
        String miaEntry = attemptOf(mia, big);
        String noahEntry = attemptOf(noah, big);
        String paulEntry = attemptOf(paul, small);
        assertThat(ids(rows)).containsExactlyInAnyOrder(paidTopUp, openTopUp, expiredTopUp, failedTopUp, miaEntry,
            noahEntry, paulEntry);
        // Newest first.
        assertThat(rows.stream().map(row -> Instant.parse(String.valueOf(row.get("occurredAt")))).toList())
            .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(rowOf(rows, paidTopUp)).containsEntry("type", "TOPUP").containsEntry("status", "COMPLETED");
        assertThat(money(rowOf(rows, paidTopUp).get("amount"))).isEqualByComparingTo("1000");
        assertThat(money(rowOf(rows, paidTopUp).get("refunded"))).isEqualByComparingTo("300");
        assertThat(rowOf(rows, openTopUp)).containsEntry("status", "PROCESSING");
        assertThat(rowOf(rows, expiredTopUp)).containsEntry("status", "FAILED");
        assertThat(rowOf(rows, failedTopUp)).containsEntry("status", "FAILED");
        Map<String, Object> miaRow = rowOf(rows, miaEntry);
        assertThat(miaRow).containsEntry("type", "REWARD").containsEntry("status", "COMPLETED")
            .containsEntry("publicationId", big).containsEntry("userName", miaName);
        // The taker's key is the sponsor's own: not the platform's user id.
        assertThat(String.valueOf(miaRow.get("userKey"))).matches("[0-9a-f]{32}").isNotEqualTo(mia);
        assertThat(rowOf(rows, paidTopUp).get("userKey")).isNull();
        assertThat(money(miaRow.get("amount"))).isEqualByComparingTo("600");
        assertThat(money(miaRow.get("score"))).isEqualByComparingTo("5");
        assertThat(money(miaRow.get("fullScore"))).isEqualByComparingTo("5");
        assertThat(String.valueOf(miaRow.get("publicationName"))).startsWith("Big prize ");
        assertThat(rowOf(rows, noahEntry)).containsEntry("status", "PROCESSING");
        assertThat(money(rowOf(rows, noahEntry).get("amount"))).isEqualByComparingTo("600");
        assertThat(money(rowOf(rows, paulEntry).get("amount"))).isEqualByComparingTo("10");
        // Only the declared columns: neither the answers, the result nor the taker's address.
        assertThat(rows).allSatisfy(row -> assertThat(row.keySet()).isSubsetOf(COLUMNS));
        assertThat(rows.toString()).doesNotContain("@example.com", "Paris", "Tokyo", mia, noah, paul, olga);
    }

    @Test
    void anotherSponsorsRowsNeverAppearHoweverTheSponsorFilters() {
        String token = sponsorToken(sponsor);
        List<Map<String, Object>> theirs = list(sponsorToken(other), Map.of());
        assertThat(theirs).hasSize(3);
        List<Object> theirIds = ids(theirs);
        assertThat(ids(list(token, Map.of()))).doesNotContainAnyElementsOf(theirIds);
        // Asking for the other sponsor's publication or taker finds nothing.
        assertThat(list(token, Map.of("publicationId", otherPublication))).isEmpty();
        assertThat(list(token, Map.of("userKey", keyOf(theirs, "Stranger ")))).isEmpty();
        // The same taker has another key with each sponsor (a hash, not a secret: not the user id either).
        String miaHere = keyOf(list(token, Map.of()), "Mia ");
        String miaThere = keyOf(theirs, "Mia ");
        assertThat(miaHere).isNotEqualTo(miaThere);
        assertThat(list(token, Map.of("userKey", miaThere))).isEmpty();
        assertThat(list(token, Map.of("q", "Stranger"))).isEmpty();
        assertThat(((Number) summary(token, Map.of("publicationId", otherPublication)).get("totalCount")).intValue())
            .isZero();
        // Mine are not theirs either.
        assertThat(ids(list(sponsorToken(other), Map.of("publicationId", big)))).isEmpty();
        // An administrator holding every permission reads as themselves: no sponsor's rows.
        assertThat(list(admin(), Map.of())).isEmpty();
    }

    @Test
    void everyFilterNarrowsTheRows() {
        String token = sponsorToken(sponsor);
        String miaEntry = attemptOf(mia, big);
        String noahEntry = attemptOf(noah, big);
        String paulEntry = attemptOf(paul, small);
        assertThat(ids(list(token, Map.of("type", "REWARD")))).containsExactlyInAnyOrder(miaEntry, noahEntry,
            paulEntry);
        assertThat(ids(list(token, Map.of("type", "TOPUP")))).containsExactlyInAnyOrder(paidTopUp, openTopUp,
            expiredTopUp, failedTopUp);
        assertThat(ids(list(token, Map.of("status", "FAILED")))).containsExactlyInAnyOrder(expiredTopUp,
            failedTopUp);
        assertThat(ids(list(token, Map.of("status", "PROCESSING")))).containsExactlyInAnyOrder(openTopUp, noahEntry);
        assertThat(ids(list(token, Map.of("type", "REWARD", "status", "COMPLETED"))))
            .containsExactlyInAnyOrder(miaEntry, paulEntry);
        assertThat(ids(list(token, Map.of("publicationId", small)))).containsExactly(paulEntry);
        assertThat(ids(list(token, Map.of("publicationId", big)))).containsExactlyInAnyOrder(miaEntry, noahEntry);
        assertThat(ids(list(token, Map.of("userKey", keyOf(list(token, Map.of()), "Mia ")))))
            .containsExactly(miaEntry);
        assertThat(ids(list(token, Map.of("q", miaName.toUpperCase())))).containsExactly(miaEntry);
        assertThat(ids(list(token, Map.of("q", "small prize")))).containsExactly(paulEntry);
        assertThat(ids(list(token, Map.of("from", mark.toString())))).containsExactlyInAnyOrder(miaEntry, noahEntry,
            paulEntry);
        assertThat(ids(list(token, Map.of("to", mark.toString())))).containsExactlyInAnyOrder(paidTopUp, openTopUp,
            expiredTopUp, failedTopUp);
        // A text is taken literally.
        assertThat(list(token, Map.of("q", "%"))).isEmpty();
        // Sorted by amount when asked.
        List<Map<String, Object>> byAmount = items(LIST, token, Map.of("params", Map.of(), "limit", 200,
            "sorts", List.of(Map.of("field", "amount", "asc", true))));
        assertThat(byAmount.stream().map(row -> money(row.get("amount"))).toList())
            .isSortedAccordingTo(BigDecimal::compareTo);
    }

    @Test
    void theSummaryIsTheSumOfTheListForTheSameParameters() {
        String token = sponsorToken(sponsor);
        String miaKey = keyOf(list(token, Map.of()), "Mia ");
        for (Map<String, Object> params : List.<Map<String, Object>>of(Map.of(), Map.of("type", "REWARD"),
            Map.of("type", "TOPUP"), Map.of("status", "FAILED"), Map.of("publicationId", big),
            Map.of("userKey", miaKey), Map.of("from", mark.toString()), Map.of("q", "prize"))) {
            List<Map<String, Object>> rows = list(token, params);
            Map<String, Object> summary = summary(token, params);
            assertThat(((Number) summary.get("totalCount")).intValue()).as("count %s", params).isEqualTo(rows.size());
            // Type by type: rewards and top-ups are never added together.
            for (String type : List.of("REWARD", "TOPUP")) {
                String prefix = "REWARD".equals(type) ? "reward" : "topUp";
                for (String status : List.of("COMPLETED", "PROCESSING", "FAILED")) {
                    String name = prefix + status.charAt(0) + status.substring(1).toLowerCase();
                    List<Map<String, Object>> of = rows.stream()
                        .filter(row -> type.equals(row.get("type")) && status.equals(row.get("status"))).toList();
                    if (!summary.containsKey(name + "Count")) {
                        assertThat(of).as("%s %s %s", type, status, params).isEmpty();
                        continue;
                    }
                    assertThat(((Number) summary.get(name + "Count")).intValue()).as("%s %s", name, params)
                        .isEqualTo(of.size());
                    BigDecimal amount = sum(of, "amount");
                    BigDecimal refunded = sum(of, "refunded");
                    boolean net = "TOPUP".equals(type) && "COMPLETED".equals(status);
                    assertThat(money(summary.get(name + "Amount"))).as("%s %s", name, params)
                        .isEqualByComparingTo(net ? amount.subtract(refunded) : amount);
                    if (net) {
                        assertThat(money(summary.get("topUpRefundedAmount"))).as("refunded %s", params)
                            .isEqualByComparingTo(refunded);
                    }
                }
            }
        }
        Map<String, Object> all = summary(token, Map.of());
        assertThat(((Number) all.get("totalCount")).intValue()).isEqualTo(7);
        assertThat(money(all.get("rewardCompletedAmount"))).isEqualByComparingTo("610");
        assertThat(money(all.get("rewardProcessingAmount"))).isEqualByComparingTo("600");
        assertThat(money(all.get("topUpCompletedAmount"))).isEqualByComparingTo("700");
        assertThat(money(all.get("topUpRefundedAmount"))).isEqualByComparingTo("300");
        assertThat(money(all.get("topUpProcessingAmount"))).isEqualByComparingTo("2000");
        assertThat(money(all.get("topUpFailedAmount"))).isEqualByComparingTo("7000");
        // No mixed total of money in and money out.
        assertThat(all).doesNotContainKeys("completedAmount", "processingAmount", "failedAmount");
    }

    static BigDecimal sum(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(row -> row.get(column) == null ? BigDecimal.ZERO : money(row.get(column)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void theExportHasTheFilteredRowsUnderTranslatedHeadings() {
        String token = sponsorToken(sponsor);
        String csv = export(token, "csv", Map.of("params", Map.of()), 200);
        List<String> lines = csv.lines().toList();
        assertThat(lines.getFirst()).contains("Amount (Kudos)", "User", "Publication", "Status")
            .doesNotContain("JPY");
        assertThat(lines).hasSize(1 + 7);
        String body = String.join("\n", lines.subList(1, lines.size()));
        assertThat(body).contains(paidTopUp, miaName).doesNotContain("Stranger", otherPublication, "@example.com");
        // The export follows the same parameters as the list.
        String topUps = export(token, "csv", Map.of("params", Map.of("type", "TOPUP")), 200);
        assertThat(topUps.lines().count()).isEqualTo(1 + 4);
        // In the sponsor's language.
        String chinese = new String(client.post().uri("/api/queries/" + LIST + "/export?format=csv")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "zh").bodyValue(Map.of()).exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(chinese.lines().findFirst().orElseThrow()).contains("金额（学分）");
        // XLSX is offered as well.
        client.post().uri("/api/queries/" + LIST + "/export?format=xlsx").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of()).exchange().expectStatus().isOk();
        // The other sponsor's export has their rows only.
        String theirs = export(sponsorToken(other), "csv", Map.of(), 200);
        assertThat(theirs.lines().count()).isEqualTo(1 + 3);
        assertThat(theirs).doesNotContain(paidTopUp, "Big prize", "Small prize");
    }

    @Test
    void nobodyWithoutTheSponsorsFinancePermissionReadsThem() {
        List<String> refused = List.of(taker(mia), tokenOf(user("tx-finance"), QbRoles.ADMIN_FINANCE),
            tokenOf(user("tx-super"), QbRoles.ADMIN_SUPER), tokenOf(user("tx-content"), QbRoles.ADMIN_CONTENT));
        for (String token : refused) {
            for (String id : List.of(LIST, SUMMARY)) {
                client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of()).exchange()
                    .expectStatus().isForbidden();
            }
            export(token, "csv", Map.of(), 403);
        }
        // Not signed in.
        client.post().uri("/api/queries/" + LIST).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isUnauthorized();
    }

    String export(String token, String format, Map<String, Object> request, int status) {
        byte[] bytes = client.post().uri("/api/queries/" + LIST + "/export?format=" + format)
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(new HashMap<>(request)).exchange().expectStatus().isEqualTo(status).expectBody()
            .returnResult().getResponseBody();
        String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        // The byte order mark a spreadsheet needs.
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
