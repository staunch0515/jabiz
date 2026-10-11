package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What takers see (U-10 – U-14, U-20 – U-22, U-40 – U-42, 5.3-3; docs/quizbuks/plans/Q5-answering.md, D-Q5-6,
 * D-Q5-11): the feed's visibility, search, filters and sorts; the detail page and its tiers; the completion counts of
 * the statistics job; the history; and that the feed reads none of the large temporal tables.
 */
class FeedIT extends PlayItSupport {

    Set<Object> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> item.get("publicationId")).collect(Collectors.toSet());
    }

    List<Map<String, Object>> feedAt(String token, String country, Instant at, Map<String, Object> request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("at", at.toString());
        if (country != null) {
            params.put("country", country);
        }
        Map<String, Object> body = new LinkedHashMap<>(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> more = (Map<String, Object>) body.remove("params");
        if (more != null) {
            params.putAll(more);
        }
        body.put("params", params);
        body.put("limit", 200);
        return items("qb.app.feed", token, body);
    }

    @Test
    void theFeedShowsOnlyWhatTheTakerCanStartNow() {
        String sponsor = newSponsor("s-feed");
        String poor = newSponsor("s-feed-poor");
        credit(sponsor, 10_000);
        Instant now = clock.instant();
        String jp = livePublication(sponsor, "Feed JP");
        String global = livePublication(sponsor, "Feed global", Map.of("regions", List.of("GLOBAL")));
        String us = livePublication(sponsor, "Feed US", Map.of("regions", List.of("US")));
        String paused = livePublication(sponsor, "Feed paused", Map.of("regions", List.of("GLOBAL")));
        run("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", paused));
        String later = livePublication(sponsor, "Feed later", Map.of("regions", List.of("GLOBAL"), "startAt",
            now.plus(Duration.ofDays(1)).toString(), "endAt", now.plus(Duration.ofDays(2)).toString()));
        String soon = livePublication(sponsor, "Feed ends soon", Map.of("regions", List.of("GLOBAL"), "endAt",
            now.plus(Duration.ofHours(1)).toString()));
        String quota = livePublication(sponsor, "Feed quota", Map.of("regions", List.of("GLOBAL"), "maxWinners", 1));
        start(taker(newTaker("t-feed-quota", null)), quota);
        String budget = livePublication(sponsor, "Feed budget", Map.of("regions", List.of("GLOBAL"), "budgetCap",
            100));
        start(taker(newTaker("t-feed-budget", null)), budget);
        String unpaid = livePublication(poor, "Feed unpaid", Map.of("regions", List.of("GLOBAL")));
        Set<String> all = Set.of(jp, global, us, paused, later, soon, quota, budget, unpaid);

        assertThat(ids(feed(taker(u1()), "JP", Map.of()))).filteredOn(all::contains)
            .containsExactlyInAnyOrder(jp, global, soon);
        assertThat(ids(feed(taker(u2()), "US", Map.of()))).filteredOn(all::contains)
            .containsExactlyInAnyOrder(global, us, soon);
        // Without a country: what is aimed at everyone.
        assertThat(ids(feed(taker(u1()), null, Map.of()))).filteredOn(all::contains)
            .containsExactlyInAnyOrder(global, soon);
        // Two hours on, the one that ended is gone. The one that starts tomorrow waits for the minute's job to put it
        // live (the state says it, as for starting an attempt), then it is there.
        assertThat(ids(feedAt(taker(u1()), "JP", now.plus(Duration.ofHours(2)), Map.of()))).filteredOn(all::contains)
            .containsExactlyInAnyOrder(jp, global);
        assertThat(ids(feedAt(taker(u1()), "JP", now.plus(Duration.ofDays(1)).plusSeconds(1), Map.of())))
            .filteredOn(all::contains).containsExactlyInAnyOrder(jp, global);
        clock.set(now.plus(Duration.ofDays(1)));
        runTick();
        assertThat(ids(feed(taker(u1()), "JP", Map.of()))).filteredOn(all::contains)
            .containsExactlyInAnyOrder(jp, global, later);

        // The card's fields (U-10).
        Map<String, Object> card = feed(taker(u1()), "JP", Map.of()).stream()
            .filter(item -> jp.equals(item.get("publicationId"))).findFirst().orElseThrow();
        assertThat(card).containsEntry("name", "Feed JP").containsEntry("quizTitle", "Feed JP quiz")
            .containsEntry("timeLimitSec", 300).containsEntry("questionCount", 2).containsEntry("fullScore", 5)
            .containsEntry("completions", 0).containsEntry("sponsorCompany", "Company of " + sponsor);
        assertThat(((Number) card.get("maxReward")).intValue()).isEqualTo(100);

        // The paused one comes back when resumed; the unpaid one once its sponsor has money.
        run("QB_PUBLICATION_RESUME", sponsorToken(sponsor), body("publicationId", paused));
        credit(poor, 100);
        assertThat(ids(feed(taker(u1()), "JP", Map.of()))).contains(paused, unpaid);
    }

    @Test
    void theFeedIsSearchedFilteredAndSorted() {
        String sponsor = newSponsor("s-search");
        credit(sponsor, 10_000);
        Instant now = clock.instant();
        String cheap = livePublication(sponsor, "Search alpha", Map.of("regions", List.of("GLOBAL"), "tiers",
            List.of(body("fromScore", 0, "toScore", 4, "reward", 0), body("fromScore", 5, "toScore", 5,
                "reward", 20))));
        String rich = livePublication(sponsor, "Search beta", Map.of("regions", List.of("GLOBAL"), "startAt",
            now.minus(Duration.ofDays(2)).toString()));
        play(newTaker("t-search-1", null), rich, true, false);
        Set<String> mine = Set.of(cheap, rich);
        String token = taker(u1());

        assertThat(ids(feed(token, "JP", Map.of("q", "ALPHA")))).filteredOn(mine::contains).containsExactly(cheap);
        assertThat(ids(feed(token, "JP", Map.of("q", "beta quiz")))).filteredOn(mine::contains).containsExactly(rich);
        assertThat(ids(feedAt(token, "JP", now, Map.of("filters", List.of(Map.of("field", "maxReward", "op",
            "between", "from", 50, "to", 150)))))).filteredOn(mine::contains).containsExactly(rich);
        assertThat(ids(feedAt(token, "JP", now, Map.of("filters", List.of(Map.of("field", "completions", "op",
            "gte", "value", 1)))))).filteredOn(mine::contains).containsExactly(rich);
        assertThat(ids(feedAt(token, "JP", now, Map.of("filters", List.of(Map.of("field", "startAt", "op",
            "lt", "value", now.minus(Duration.ofDays(1)).toString())))))).filteredOn(mine::contains)
            .containsExactly(rich);
        assertThat(ids(feedAt(token, "JP", now, Map.of("filters", List.of(Map.of("field", "sponsorCompany", "op",
            "eq", "value", "Company of " + sponsor)))))).containsExactlyInAnyOrder(cheap, rich);
        List<Object> byReward = feedAt(token, "JP", now, Map.of("sorts", List.of(Map.of("field", "maxReward",
            "asc", true)))).stream().map(item -> item.get("publicationId")).filter(mine::contains).toList();
        assertThat(byReward).containsExactly(cheap, rich);
        List<Object> newest = feedAt(token, "JP", now, Map.of()).stream().map(item -> item.get("publicationId"))
            .filter(mine::contains).toList();
        assertThat(newest).containsExactly(cheap, rich);
        List<Object> mostDone = feedAt(token, "JP", now, Map.of("sorts", List.of(Map.of("field", "completions",
            "asc", false)))).stream().map(item -> item.get("publicationId")).filter(mine::contains).toList();
        assertThat(mostDone).containsExactly(rich, cheap);
    }

    Map<String, Object> detail(String token, String publicationId, String country, Instant at) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("publicationId", publicationId);
        params.put("at", at.toString());
        if (country != null) {
            params.put("country", country);
        }
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", params));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Map<String, Object>> tiers(String token, String publicationId, String country) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("publicationId", publicationId);
        params.put("at", clock.instant().toString());
        params.put("country", country);
        return items("qb.app.publication-tiers", token, Map.of("params", params));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDetailPageAndItsCounts() {
        String sponsor = newSponsor("s-detail");
        credit(sponsor, 10_000);
        String publicationId = livePublication(sponsor, "Detail");
        Map<String, Object> page = detail(taker(u1()), publicationId, "JP", clock.instant());
        assertThat(page).containsEntry("name", "Detail").containsEntry("quizTitle", "Detail quiz")
            .containsEntry("fullScore", 5).containsEntry("passScore", 3).containsEntry("timeLimitSec", 300)
            .containsEntry("questionCount", 2).containsEntry("canStart", true).containsEntry("myAttemptId", null)
            .containsEntry("completions", 0).containsEntry("last24h", 0);
        assertThat((String) page.get("materials")).isEqualTo("[]");
        assertThat(tiers(taker(u1()), publicationId, "JP")).extracting(row -> row.get("seq"),
            row -> row.get("fromScore"), row -> row.get("toScore"), row -> ((Number) row.get("reward")).intValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 0, 2, 0),
                org.assertj.core.groups.Tuple.tuple(2, 3, 4, 10), org.assertj.core.groups.Tuple.tuple(3, 5, 5, 100));
        // Another region sees neither.
        assertThat(detail(taker(u2()), publicationId, "US", clock.instant())).isNull();
        assertThat(tiers(taker(u2()), publicationId, "US")).isEmpty();

        // Completions over time: one now, one 2 hours on, one 3 days on, one 10 days on; counted 10 days on.
        Instant t0 = clock.instant();
        String[] takers = {"t-detail-1", "t-detail-2", "t-detail-3", "t-detail-4"};
        long[] offsets = {0, 2 * 3600, 4 * 86400, 10 * 86400 - 600};
        String longRunning = livePublication(sponsor, "Detail long", Map.of("regions", List.of("GLOBAL"), "endAt",
            t0.plus(Duration.ofDays(30)).toString()));
        for (int i = 0; i < takers.length; i++) {
            clock.set(t0.plusSeconds(offsets[i]));
            play(newTaker(takers[i], null), longRunning, true, true);
        }
        Instant counted = t0.plus(Duration.ofDays(10));
        clock.set(counted);
        assertThat(runStats(counted).toString()).contains("SUCCEEDED");
        Map<String, Object> stats = query("SELECT * FROM qb_publication_stats WHERE publication_id = ?::uuid",
            longRunning).getFirst();
        assertThat(stats).containsEntry("last_1h", BigDecimal.ONE).containsEntry("last_24h", BigDecimal.ONE)
            .containsEntry("last_7d", new BigDecimal("2")).containsEntry("last_30d", new BigDecimal("4"));
        Map<String, Object> counts = detail(taker(newTaker("t-detail-5", null)), longRunning, null, counted);
        assertThat(counts).containsEntry("completions", 4).containsEntry("last1h", 1).containsEntry("last24h", 1)
            .containsEntry("last7d", 2).containsEntry("last30d", 4);
        // A run that finds nothing changed writes nothing.
        long version = ((Number) stats.get("version")).longValue();
        runStats(counted.plusSeconds(1));
        assertThat(((Number) query("SELECT version FROM qb_publication_stats WHERE publication_id = ?::uuid",
            longRunning).getFirst().get("version")).longValue()).isEqualTo(version);

        // A taker who answered reads the page even when they could not start any more.
        String taker1 = user("t-detail-1");
        Map<String, Object> mine = detail(taker(taker1), longRunning, null, counted);
        assertThat(mine).containsEntry("canStart", false).containsEntry("myStatus", "SUBMITTED");
        assertThat(mine.get("myAttemptId")).isNotNull();
    }

    @Test
    void theHistoryHasItsCategoriesAndFilters() {
        String sponsor = newSponsor("s-history");
        credit(sponsor, 10_000);
        String taker = newTaker("t-history", null);
        String token = taker(taker);
        String passed = livePublication(sponsor, "History passed", Map.of("regions", List.of("GLOBAL")));
        String failed = livePublication(sponsor, "History failed", Map.of("regions", List.of("GLOBAL")));
        String open = livePublication(sponsor, "History open", Map.of("regions", List.of("GLOBAL")));
        String timedOut = livePublication(sponsor, "History timed out", Map.of("regions", List.of("GLOBAL"),
            "timeLimitSec", 60));
        submit(token, (String) start(token, passed).get("attemptId"), answers(true, true));
        clock.advance(Duration.ofSeconds(1));
        submit(token, (String) start(token, failed).get("attemptId"), answers(false, false));
        clock.advance(Duration.ofSeconds(1));
        start(token, timedOut);
        clock.advance(Duration.ofSeconds(120));
        start(token, open);
        Map<String, Object> params = Map.of("at", clock.instant().toString());
        List<Map<String, Object>> history = items("qb.app.history", token, Map.of("params", params));
        assertThat(history).extracting(row -> row.get("name"), row -> row.get("category"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("History open", "IN_PROGRESS"),
                org.assertj.core.groups.Tuple.tuple("History timed out", "TIMED_OUT"),
                org.assertj.core.groups.Tuple.tuple("History failed", "FAILED"),
                org.assertj.core.groups.Tuple.tuple("History passed", "PASSED"));
        Map<String, Object> first = history.getLast();
        assertThat(first).containsEntry("rewardStatus", "PAID").containsEntry("score", 5)
            .containsEntry("fullScore", 5).containsEntry("passed", true);
        assertThat(items("qb.app.history", token, Map.of("params", Map.of("q", "failed"))))
            .extracting(row -> row.get("name")).containsExactly("History failed");
        assertThat(items("qb.app.history", token, Map.of("filters", List.of(Map.of("field", "category", "op", "eq",
            "value", "PASSED"))))).extracting(row -> row.get("name")).containsExactly("History passed");
        assertThat(items("qb.app.history", token, Map.of("filters", List.of(Map.of("field", "reward", "op", "gte",
            "value", 50))))).extracting(row -> row.get("name")).containsExactly("History passed");
        assertThat(items("qb.app.history", token, Map.of("filters", List.of(Map.of("field", "status", "op", "eq",
            "value", "IN_PROGRESS"))))).hasSize(2);
        // Only one's own.
        assertThat(items("qb.app.history", taker(u1()), Map.of())).noneMatch(row -> String.valueOf(row.get("name"))
            .startsWith("History"));
        // The administrators' view of the same attempts.
        assertThat(items("qb.admin.attempts", tokenOf("reader", QbRoles.ADMIN_CONTENT), Map.of("filters",
            List.of(Map.of("field", "userId", "op", "eq", "value", taker))))).hasSize(4);
        assertThat(items("qb.admin.wallets", tokenOf("finance", QbRoles.ADMIN_FINANCE), Map.of("filters",
            List.of(Map.of("field", "ownerId", "op", "eq", "value", sponsor))))).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("balance")).intValue()).isEqualTo(9_900));
    }

    @Test
    void theSponsorsListShowsCountsAndFundsShort() {
        String sponsor = newSponsor("s-list");
        String publicationId = livePublication(sponsor, "List counts", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 100);
        String a = (String) start(taker(newTaker("t-list-1", null)), publicationId).get("attemptId");
        String b = (String) start(taker(newTaker("t-list-2", null)), publicationId).get("attemptId");
        submit(taker(user("t-list-1")), a, answers(true, true));
        submit(taker(user("t-list-2")), b, answers(true, true));
        Map<String, Object> row = items("qb.sponsor.publications", sponsorToken(sponsor), Map.of()).stream()
            .filter(item -> publicationId.equals(item.get("publicationId"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("status", "FUNDS_SHORT").containsEntry("fundsShort", true)
            .containsEntry("starts", 2).containsEntry("completions", 2).containsEntry("winners", 2)
            .containsEntry("pendingCount", 1);
        assertThat(((Number) row.get("pendingAmount")).intValue()).isEqualTo(100);
        assertThat(((Number) row.get("spent")).intValue()).isEqualTo(200);
    }

    @Test
    void theFeedReadsNoLargeTemporalTable() throws Exception {
        String sponsor = newSponsor("s-plan");
        credit(sponsor, 1_000);
        livePublication(sponsor, "Plan", Map.of("regions", List.of("GLOBAL")));
        // A backend reports its table statistics within ten seconds of going idle: let the setup's arrive first.
        Thread.sleep(11_000);
        List<String> large = List.of("qb_publication_version", "qb_reward_tier_version", "qb_quiz_version_version",
            "qb_profile_version", "qb_attempt");
        Map<String, Long> before = scans(large);
        long cards = scans(List.of("qb_publication_card")).get("qb_publication_card");
        assertThat(feed(taker(u1()), "JP", Map.of())).isNotEmpty();
        // Table statistics reach the view after the transaction: wait for the card's, then compare the others.
        long deadline = System.currentTimeMillis() + 20_000;
        while (scans(List.of("qb_publication_card")).get("qb_publication_card") == cards
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(scans(List.of("qb_publication_card")).get("qb_publication_card")).isGreaterThan(cards);
        assertThat(scans(large)).isEqualTo(before);
    }

    /** Sequential and index scans of each table so far. */
    static Map<String, Long> scans(List<String> tables) {
        Map<String, Long> scans = new LinkedHashMap<>();
        for (String table : tables) {
            scans.put(table, ((Number) query("SELECT coalesce(seq_scan, 0) + coalesce(idx_scan, 0) AS n "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table).getFirst()
                .get("n")).longValue());
        }
        return scans;
    }
}
