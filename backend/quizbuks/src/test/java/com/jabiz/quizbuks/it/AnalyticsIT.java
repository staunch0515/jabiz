package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sponsors' analytics (docs/quizbuks/plans/Q9e-analytics.md, Q9e1): every figure as defined (E-1), live and as
 * aggregated by the job, the days of the deployment's zone (Asia/Tokyo, E-2), the minimum group and its "at least k
 * new completions" rule against two looks a completion apart (E-4), the sponsor's own data only, the permissions and
 * the export (aggregates only, E-7).
 */
class AnalyticsIT extends AnalyticsItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    String run() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Starts now, answers after {@code seconds} and submits; the attempt's time is {@code seconds}. */
    void timed(String userId, String publicationId, boolean first, boolean second, long seconds) {
        String attemptId = startAttempt(userId, publicationId);
        at(clock.instant().plusSeconds(seconds));
        submitAttempt(userId, attemptId, first, second);
    }

    /** How many buckets the trend gives (all pages). */
    long trendTotal(String publicationId, Instant from, Instant to, String bucket, String token) {
        Map<String, Object> page = template(TREND, token, Map.of("params", Map.of("publicationId", publicationId,
            "from", from.toString(), "to", to.toString(), "bucket", bucket), "limit", 10));
        return count(page.get("total"));
    }

    @Test
    void everyFigureFollowsItsDefinitionLiveAndAggregated() {
        minGroup("3");
        String run = run();
        String sponsor = newSponsor("s-an-metrics-" + run);
        // Enough for one top reward: the second winner's reward waits (pending), and the publication is short of funds.
        credit(sponsor, 100);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Metrics " + run);
        runAnalytics(h.plus(Duration.ofMinutes(2)));

        String t1 = newTaker("t-an-1-" + run, null);
        String t2 = newTaker("t-an-2-" + run, null);
        String t3 = newTaker("t-an-3-" + run, null);
        String t4 = newTaker("t-an-4-" + run, null);
        String t5 = newTaker("t-an-5-" + run, null);
        // T4 views and starts first (its time runs out first); T1 to T3 view (T1 twice: one view); T5 starts unseen.
        at(h.plus(Duration.ofSeconds(150)));
        view(t4, publication);
        String a4 = startAttempt(t4, publication);
        at(h.plus(Duration.ofMinutes(3)));
        for (String taker : List.of(t1, t2, t3)) {
            view(taker, publication);
        }
        view(t1, publication);
        String a1 = startAttempt(t1, publication);
        String a2 = startAttempt(t2, publication);
        String a3 = startAttempt(t3, publication);
        startAttempt(t5, publication);
        // T1 full marks after 60 s (100, paid); T2 the second only after 120 s (3 points, 10, pending); T3 nothing
        // right after 180 s; T4's time runs out (expired, no answer): passes 2, winners 2, spent 110.
        at(h.plus(Duration.ofMinutes(4)));
        assertThat(submitAttempt(t1, a1, true, true)).containsEntry("rewardStatus", "PAID");
        at(h.plus(Duration.ofMinutes(5)));
        assertThat(submitAttempt(t2, a2, false, true)).containsEntry("rewardStatus", "PENDING");
        at(h.plus(Duration.ofMinutes(6)));
        submitAttempt(t3, a3, false, false);
        at(h.plus(Duration.ofSeconds(7 * 60 + 40)));
        assertThat(runExpire(clock.instant()).items().succeeded()).isPositive();
        assertThat(attempt(a4)).containsEntry("status", "EXPIRED");

        // Before any run (review M1): the publication's figures are the job's snapshot, none yet; the buckets too.
        // Only the dashboard's sums over the sponsor's publications are live (E-3).
        Map<String, Object> before = overview(sponsorToken(sponsor), publication);
        assertThat(List.of(count(before.get("views")), count(before.get("starts")), count(before.get("completions")),
            count(before.get("passes")), count(before.get("winners")), count(before.get("spent")),
            count(before.get("inProgress")))).containsOnly(0L);
        assertThat(before.get("completionRate")).isNull();
        assertThat(trend(sponsorToken(sponsor), publication, h, h.plus(Duration.ofHours(1)), "HOUR"))
            .singleElement().satisfies(row -> assertThat(counts(row)).containsOnly(0L));
        Map<String, Object> board = dashboard(sponsorToken(sponsor), clock.instant());
        assertThat(count(board.get("fundsShort"))).isEqualTo(1);
        assertThat(count(board.get("live"))).isZero();
        assertThat(count(board.get("answering"))).isEqualTo(1);
        assertThat(count(board.get("spentToday"))).isEqualTo(110);
        assertThat(count(board.get("pendingCount"))).isEqualTo(1);
        assertThat(count(board.get("pendingAmount"))).isEqualTo(10);
        assertThat(count(board.get("available"))).isZero();

        // The job (five minutes behind: everything up to h + 10 min).
        runAnalytics(h.plus(Duration.ofMinutes(15)));
        Map<String, Object> after = overview(sponsorToken(sponsor), publication);
        assertThat(List.of(count(after.get("starts")), count(after.get("completions")), count(after.get("passes")),
            count(after.get("winners")), count(after.get("spent")), count(after.get("inProgress"))))
            .containsExactly(5L, 4L, 2L, 2L, 110L, 1L);
        assertThat(decimal(after.get("completionRate"))).isEqualByComparingTo("0.8");
        assertThat(count(after.get("views"))).isEqualTo(4);
        assertThat(after).containsEntry("viewsBelowStarts", true);
        assertThat(count(after.get("funnelViews"))).isEqualTo(5);
        assertThat(decimal(after.get("viewToStart"))).isEqualByComparingTo("1");
        assertThat(decimal(after.get("startToComplete"))).isEqualByComparingTo("0.8");
        assertThat(decimal(after.get("completeToPass"))).isEqualByComparingTo("0.5");
        assertThat(after).containsEntry("largestDrop", "COMPLETE_PASS").containsEntry("suppressed", false);
        // The time of the three submitted in time (60, 120, 180 s), not of the expired one (E-6).
        assertThat(count(after.get("basedOn"))).isEqualTo(4);
        assertThat(decimal(after.get("avgDurationSec"))).isEqualByComparingTo("120");
        assertThat(count(after.get("timeouts"))).isEqualTo(1);
        assertThat(instant(after.get("refreshedAt"))).isEqualTo(h.plus(Duration.ofMinutes(15)));

        // The bucket of the hour: the counters' increments since the publication went on.
        List<Map<String, Object>> buckets = trend(sponsorToken(sponsor), publication, h, h.plus(Duration.ofHours(2)), "HOUR");
        assertThat(buckets).hasSize(2);
        assertThat(instant(buckets.getFirst().get("bucketStart"))).isEqualTo(h);
        assertThat(counts(buckets.getFirst())).containsExactly(4L, 5L, 4L, 2L, 2L, 110L);
        assertThat(buckets.getFirst()).containsEntry("gap", false);
        assertThat(counts(buckets.get(1))).containsOnly(0L);
        assertThat(hours(publication)).singleElement().satisfies(row -> assertThat(row).containsEntry("gap", false));

        // Questions: the first right only by T1 (1 of 4: hard), the second by T1 and T2 (2 of 4: medium).
        List<Map<String, Object>> questions = questions(sponsorToken(sponsor), publication);
        assertThat(questions).extracting(row -> count(row.get("questionNo"))).containsExactly(1L, 2L);
        assertThat(questions.getFirst()).containsEntry("stem", "Capital of France?").containsEntry("difficulty", "HARD")
            .containsEntry("suppressed", false);
        assertThat(count(questions.getFirst().get("points"))).isEqualTo(2);
        assertThat(count(questions.getFirst().get("completions"))).isEqualTo(4);
        assertThat(decimal(questions.getFirst().get("correctRate"))).isEqualByComparingTo("0.25");
        assertThat(questions.get(1)).containsEntry("difficulty", "MEDIUM");
        assertThat(decimal(questions.get(1).get("correctRate"))).isEqualByComparingTo("0.5");
        // Nothing of the options or the right answers.
        assertThat(questions.toString()).doesNotContain("Paris", "Lyon", "Tokyo", "Osaka", "correct=[");

        // Today's spending: the bucket, nothing since the run; the top publications of the week.
        Map<String, Object> later = dashboard(sponsorToken(sponsor), h.plus(Duration.ofMinutes(16)));
        assertThat(count(later.get("spentToday"))).isEqualTo(110);
        assertThat(later).containsEntry("zone", "Asia/Tokyo");
        assertThat(rows(TOP, sponsorToken(sponsor), Map.of("at", h.plus(Duration.ofMinutes(16)).toString())))
            .singleElement().satisfies(row -> {
                assertThat(row).containsEntry("publicationId", publication);
                assertThat(count(row.get("completions7d"))).isEqualTo(4);
                assertThat(count(row.get("spent7d"))).isEqualTo(110);
            });
        assertThat(query("SELECT day_zone AS zone FROM qb_stats_run WHERE job = 'qb.analytics'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("zone", "Asia/Tokyo"));
    }

    @Test
    void aPublicationsFiguresMoveOnlyWithTheJobNotWithEachAttempt() {
        // Review M1: on a quiet publication, live starts and completions polled a minute apart would time one taker.
        String run = run();
        String sponsor = newSponsor("s-an-snap-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Snapshot " + run);
        complete("t-an-snap-1-" + run, publication, true, true);
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        Map<String, Object> first = overview(sponsorToken(sponsor), publication);
        assertThat(count(first.get("completions"))).isEqualTo(1);
        // One taker starts, a minute later finishes: the publication's figures do not move until the next run.
        at(h.plus(Duration.ofMinutes(11)));
        String taker = newTaker("t-an-snap-2-" + run, null);
        String attempt = startAttempt(taker, publication);
        Map<String, Object> started = overview(sponsorToken(sponsor), publication);
        at(h.plus(Duration.ofMinutes(12)));
        submitAttempt(taker, attempt, true, false);
        Map<String, Object> finished = overview(sponsorToken(sponsor), publication);
        for (String column : List.of("starts", "completions", "inProgress", "passes", "winners", "spent")) {
            assertThat(started.get(column)).as(column).isEqualTo(first.get(column));
            assertThat(finished.get(column)).as(column).isEqualTo(first.get(column));
        }
        // The dashboard's sums are live (E-3; residual 4 when the sponsor has only this one publication).
        assertThat(count(dashboard(sponsorToken(sponsor), clock.instant()).get("spentToday"))).isEqualTo(100);
        runAnalytics(h.plus(Duration.ofMinutes(20)));
        Map<String, Object> next = overview(sponsorToken(sponsor), publication);
        assertThat(count(next.get("starts"))).isEqualTo(2);
        assertThat(count(next.get("completions"))).isEqualTo(2);
    }

    @Test
    void todaysSpendingLeavesOutWhatAPublicationTheJobHasNotSeenSpentBefore() {
        // Review L1: a publication the job has not seen yet spent 100 yesterday (Tokyo); today it spent nothing.
        String run = run();
        String sponsor = newSponsor("s-an-old-" + run);
        credit(sponsor, 10_000);
        Instant midnight = clock.instant().atZone(TOKYO).toLocalDate().plusDays(2).atStartOfDay(TOKYO).toInstant();
        at(midnight.minus(Duration.ofHours(2)));
        String publication = analysedPublication(sponsor, "Old " + run);
        complete("t-an-old-1-" + run, publication, true, true);
        Instant today = midnight.plus(Duration.ofHours(1));
        at(today);
        assertThat(count(dashboard(sponsorToken(sponsor), today).get("spentToday"))).isZero();
        // The job's first sight puts the history in the hour it went on (yesterday, a gap), not in today's hour.
        runAnalytics(today.plus(Duration.ofMinutes(10)));
        assertThat(count(dashboard(sponsorToken(sponsor), today.plus(Duration.ofMinutes(11))).get("spentToday")))
            .isZero();
        assertThat(hours(publication)).singleElement().satisfies(row -> {
            assertThat(instant(row.get("hour_start"))).isEqualTo(midnight.minus(Duration.ofHours(2)));
            assertThat(row).containsEntry("gap", true);
            assertThat(count(row.get("spent"))).isEqualTo(100);
        });
        // Spending today counts at once, before the job sees it.
        complete("t-an-old-2-" + run, publication, true, true);
        assertThat(count(dashboard(sponsorToken(sponsor), clock.instant()).get("spentToday"))).isEqualTo(100);
    }

    @Test
    void aMinimumGroupThatIsNotAWholeNumberOrTooLargeIsRefusedOrHoldsEverythingBack() {
        // The parameter is numeric(3, 0): other values are refused when set.
        for (String value : List.of("abc", "1000", "2.5")) {
            assertThat(codes(refused("PARAM_SET", admin(), body("key", "qb.stats.min-group", "value", value), 422)))
                .as(value).contains("PARAM_VALUE_INVALID");
        }
        // A larger value stored some other way is read as it is, never as the default: everything is held back.
        String run = run();
        String sponsor = newSponsor("s-an-group-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Groups " + run);
        for (int i = 0; i < 5; i++) {
            complete("t-an-group-" + i + "-" + run, publication, true, true);
        }
        minGroup("5");
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        assertThat(questions(sponsorToken(sponsor), publication)).allSatisfy(row -> assertThat(row)
            .containsEntry("suppressed", false));
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sys_param_version' AND column_name NOT IN ('row_id') "
            + "ORDER BY ordinal_position").stream().map(row -> String.valueOf(row.get("column_name"))).toList();
        String select = String.join(", ", columns.stream().map(c -> switch (c) {
            case "param_value" -> "'1000'";
            case "version_no" -> "v.version_no + 1";
            default -> "v." + c;
        }).toList());
        execute("INSERT INTO sys_param_version (" + String.join(", ", columns) + ") SELECT " + select
            + " FROM sys_param_version v WHERE v.param_key = 'qb.stats.min-group' ORDER BY v.version_no DESC LIMIT 1");
        assertThat(count(overview(sponsorToken(sponsor), publication).get("minGroup"))).isEqualTo(1000);
        assertThat(questions(sponsorToken(sponsor), publication)).allSatisfy(row -> assertThat(row)
            .containsEntry("suppressed", true).containsEntry("correctRate", null));
        minGroup("5");
        assertThat(questions(sponsorToken(sponsor), publication)).allSatisfy(row -> assertThat(row)
            .containsEntry("suppressed", false));
    }

    @Test
    void theFunnelNamesItsLargestDrop() {
        minGroup("5");
        String run = run();
        String sponsor = newSponsor("s-an-funnel-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String quiet = analysedPublication(sponsor, "Quiet " + run);
        String publication = analysedPublication(sponsor, "Funnel " + run);
        // Four look, one answers and passes: three quarters are lost between view and start.
        for (int i = 0; i < 4; i++) {
            view(newTaker("t-an-funnel-" + i + "-" + run, null), publication);
        }
        play(user("t-an-funnel-0-" + run), publication, true, true);
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        Map<String, Object> row = overview(sponsorToken(sponsor), publication);
        assertThat(row).containsEntry("largestDrop", "VIEW_START").containsEntry("viewsBelowStarts", false);
        assertThat(decimal(row.get("viewToStart"))).isEqualByComparingTo("0.25");
        // One completion: under the minimum group, the time and the timeouts are held back.
        assertThat(row).containsEntry("suppressed", true).containsEntry("avgDurationSec", null)
            .containsEntry("timeouts", null);
        assertThat(count(row.get("minGroup"))).isEqualTo(5);
        // Nothing happened: no drop, no rate.
        Map<String, Object> nothing = overview(sponsorToken(sponsor), quiet);
        assertThat(nothing).containsEntry("largestDrop", null).containsEntry("completionRate", null)
            .containsEntry("viewToStart", null);
        assertThat(questions(sponsorToken(sponsor), quiet)).hasSize(2).allSatisfy(q -> assertThat(q)
            .containsEntry("suppressed", true).containsEntry("correctRate", null).containsEntry("difficulty", null));
    }

    @Test
    void daysAreTheDeploymentsDaysInTokyo() {
        String run = run();
        String sponsor = newSponsor("s-an-days-" + run);
        credit(sponsor, 10_000);
        // 14:00 UTC is 23:00 in Tokyo; 15:00 UTC the next day's midnight there.
        Instant fourteen = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(14));
        while (fourteen.isBefore(clock.instant().plus(Duration.ofHours(1)))) {
            fourteen = fourteen.plus(Duration.ofDays(1));
        }
        Instant fifteen = fourteen.plus(Duration.ofHours(1));
        at(fourteen.plus(Duration.ofMinutes(5)));
        String publication = analysedPublication(sponsor, "Days " + run);
        runAnalytics(fourteen.plus(Duration.ofMinutes(10)));
        at(fourteen.plus(Duration.ofMinutes(30)));
        complete("t-an-day-1-" + run, publication, true, true);
        runAnalytics(fourteen.plus(Duration.ofMinutes(40)));
        runAnalytics(fifteen);
        at(fifteen.plus(Duration.ofMinutes(30)));
        complete("t-an-day-2-" + run, publication, false, true);
        runAnalytics(fifteen.plus(Duration.ofMinutes(40)));

        LocalDate day = fourteen.atZone(TOKYO).toLocalDate();
        assertThat(fifteen.atZone(TOKYO).toLocalDate()).isEqualTo(day.plusDays(1));
        List<Map<String, Object>> days = trend(sponsorToken(sponsor), publication, fourteen.minus(Duration.ofDays(1)),
            fifteen.plus(Duration.ofDays(1)), "DAY");
        assertThat(days).extracting(row -> LocalDate.parse(String.valueOf(row.get("day"))))
            .containsExactly(day.minusDays(1), day, day.plusDays(1));
        assertThat(instant(days.get(2).get("bucketStart"))).isEqualTo(fifteen);
        assertThat(instant(days.get(1).get("bucketStart"))).isEqualTo(fifteen.minus(Duration.ofDays(1)));
        assertThat(counts(days.get(0))).containsOnly(0L);
        assertThat(count(days.get(1).get("completions"))).isEqualTo(1);
        assertThat(count(days.get(1).get("spent"))).isEqualTo(100);
        assertThat(count(days.get(2).get("completions"))).isEqualTo(1);
        assertThat(count(days.get(2).get("spent"))).isEqualTo(10);
        // The zone the days are of comes with them, and the totals are the server's (Q9e2 review M2, L1).
        assertThat(days).extracting(row -> row.get("zone")).containsOnly("Asia/Tokyo");
        Map<String, Object> totals = trendTotals(sponsorToken(sponsor), publication, fourteen.minus(Duration.ofDays(1)),
            fifteen.plus(Duration.ofDays(1)), "DAY");
        assertThat(count(totals.get("completions"))).isEqualTo(2);
        assertThat(count(totals.get("spent"))).isEqualTo(110);
        assertThat(totals).containsEntry("zone", "Asia/Tokyo");
        assertThat(instant(totals.get("periodStart"))).isEqualTo(instant(days.get(0).get("bucketStart")));
        assertThat(instant(totals.get("periodEnd"))).isEqualTo(fifteen.plus(Duration.ofDays(1)));
        assertThat(overview(sponsorToken(sponsor), publication)).containsEntry("zone", "Asia/Tokyo");
        // By hour: the UTC hours, each with the Tokyo date of its start.
        List<Map<String, Object>> hours = trend(sponsorToken(sponsor), publication, fourteen, fifteen.plus(Duration.ofHours(1)),
            "HOUR");
        assertThat(hours).extracting(row -> count(row.get("completions"))).containsExactly(1L, 1L);
        assertThat(count(trendTotals(sponsorToken(sponsor), publication, fourteen, fifteen.plus(Duration.ofHours(1)),
            "HOUR").get("completions"))).isEqualTo(2);
        assertThat(count(trendTotals(sponsorToken(sponsor), publication, fifteen, fifteen.plus(Duration.ofHours(1)),
            "HOUR").get("completions"))).isEqualTo(1);
        assertThat(hours).extracting(row -> LocalDate.parse(String.valueOf(row.get("day"))))
            .containsExactly(day, day.plusDays(1));
        // Today starts at midnight in Tokyo: before it the day's spending is 100, after it 10.
        Map<String, Object> before = dashboard(sponsorToken(sponsor), fourteen.plus(Duration.ofMinutes(50)));
        assertThat(instant(before.get("dayStart"))).isEqualTo(fifteen.minus(Duration.ofDays(1)));
        assertThat(count(before.get("spentToday"))).isEqualTo(100);
        Map<String, Object> after = dashboard(sponsorToken(sponsor), fifteen.plus(Duration.ofMinutes(45)));
        assertThat(instant(after.get("dayStart"))).isEqualTo(fifteen);
        assertThat(count(after.get("spentToday"))).isEqualTo(10);
        // At most 400 buckets, whatever the period asked for.
        assertThat(trendTotal(publication, fourteen.minus(Duration.ofDays(1000)), fifteen, "DAY",
            sponsorToken(sponsor))).isEqualTo(400);
        assertThat(trendTotal(publication, fourteen, fourteen.plus(Duration.ofDays(30)), "HOUR",
            sponsorToken(sponsor))).isEqualTo(400);
        assertThat(trend(sponsorToken(sponsor), publication, fourteen, fifteen, "WEEK")).isEmpty();
    }

    @Test
    void questionFiguresWaitForTheMinimumGroupAndChangeOnlyInStepsOfIt() {
        minGroup("5");
        String run = run();
        String sponsor = newSponsor("s-an-k-" + run);
        credit(sponsor, 100_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Groups " + run);
        // Four completions, each of its own time: below the group, nothing a person answered is shown.
        for (int i = 1; i <= 4; i++) {
            timed(newTaker("t-an-k-" + i + "-" + run, null), publication, i % 2 == 0, true, 10L * i);
        }
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        assertThat(questions(sponsorToken(sponsor), publication)).hasSize(2).allSatisfy(row -> assertThat(row)
            .containsEntry("suppressed", true).containsEntry("completions", null).containsEntry("correct", null)
            .containsEntry("correctRate", null).containsEntry("difficulty", null));
        Map<String, Object> small = overview(sponsorToken(sponsor), publication);
        assertThat(small).containsEntry("suppressed", true).containsEntry("avgDurationSec", null)
            .containsEntry("timeouts", null);
        assertThat(count(small.get("basedOn"))).isZero();
        // The counts are not held back (E-4).
        assertThat(count(small.get("completions"))).isEqualTo(4);

        // The fifth: the first figures, over five.
        at(h.plus(Duration.ofMinutes(11)));
        timed(newTaker("t-an-k-5-" + run, null), publication, false, false, 50);
        runAnalytics(h.plus(Duration.ofMinutes(20)));
        List<Map<String, Object>> first = questions(sponsorToken(sponsor), publication);
        Map<String, Object> firstOverview = overview(sponsorToken(sponsor), publication);
        assertThat(first).allSatisfy(row -> assertThat(row).containsEntry("suppressed", false));
        assertThat(count(first.getFirst().get("completions"))).isEqualTo(5);
        assertThat(count(first.getFirst().get("correct"))).isEqualTo(2);
        assertThat(count(first.get(1).get("correct"))).isEqualTo(4);
        assertThat(count(firstOverview.get("basedOn"))).isEqualTo(5);
        assertThat(decimal(firstOverview.get("avgDurationSec"))).isEqualByComparingTo("30");

        // A sixth, all right and slow: two looks a completion apart would tell its answers and time. The figures
        // stay as they were until five more completed, however many looks.
        at(h.plus(Duration.ofMinutes(21)));
        timed(newTaker("t-an-k-6-" + run, null), publication, true, true, 290);
        runAnalytics(h.plus(Duration.ofMinutes(30)));
        assertThat(questions(sponsorToken(sponsor), publication)).isEqualTo(first);
        Map<String, Object> second = overview(sponsorToken(sponsor), publication);
        assertThat(count(second.get("completions"))).isEqualTo(6);
        assertThat(count(second.get("basedOn"))).isEqualTo(5);
        assertThat(second.get("avgDurationSec")).isEqualTo(firstOverview.get("avgDurationSec"));
        assertThat(second.get("timeouts")).isEqualTo(firstOverview.get("timeouts"));
        assertThat(second.get("shownAt")).isEqualTo(firstOverview.get("shownAt"));
        at(h.plus(Duration.ofMinutes(31)));
        for (int i = 7; i <= 9; i++) {
            timed(newTaker("t-an-k-" + i + "-" + run, null), publication, true, false, 20);
        }
        runAnalytics(h.plus(Duration.ofMinutes(40)));
        assertThat(questions(sponsorToken(sponsor), publication)).isEqualTo(first);
        // The tenth: five new ones, the figures move all at once.
        at(h.plus(Duration.ofMinutes(41)));
        timed(newTaker("t-an-k-10-" + run, null), publication, true, true, 20);
        runAnalytics(h.plus(Duration.ofMinutes(50)));
        List<Map<String, Object>> moved = questions(sponsorToken(sponsor), publication);
        assertThat(count(moved.getFirst().get("completions"))).isEqualTo(10);
        assertThat(count(moved.getFirst().get("correct"))).isEqualTo(7);
        assertThat(count(moved.get(1).get("correct"))).isEqualTo(6);
        assertThat(String.valueOf(cursor(publication).get("questions"))).isEqualTo(recount(publication));
        Map<String, Object> tenth = overview(sponsorToken(sponsor), publication);
        assertThat(count(tenth.get("basedOn"))).isEqualTo(10);
        assertThat(decimal(tenth.get("avgDurationSec"))).isEqualByComparingTo("52");

        // Five more, four of them timed out: the questions move, but only one more was submitted in time, so the
        // average time of those (which would tell that one's time) stays.
        at(h.plus(Duration.ofMinutes(51)));
        for (int i = 11; i <= 14; i++) {
            startAttempt(newTaker("t-an-k-" + i + "-" + run, null), publication);
        }
        timed(newTaker("t-an-k-15-" + run, null), publication, true, true, 100);
        at(h.plus(Duration.ofMinutes(56)).plusSeconds(10));
        assertThat(runExpire(clock.instant()).items().succeeded()).isGreaterThanOrEqualTo(4);
        runAnalytics(h.plus(Duration.ofMinutes(62)));
        Map<String, Object> fifteenth = overview(sponsorToken(sponsor), publication);
        assertThat(count(fifteenth.get("basedOn"))).isEqualTo(15);
        assertThat(count(fifteenth.get("timeouts"))).isEqualTo(4);
        assertThat(fifteenth.get("avgDurationSec")).isEqualTo(tenth.get("avgDurationSec"));
        assertThat(count(questions(sponsorToken(sponsor), publication).getFirst().get("completions"))).isEqualTo(15);

        // A larger group holds them back again; a smaller one than 3 is 3.
        minGroup("20");
        assertThat(questions(sponsorToken(sponsor), publication)).allSatisfy(row -> assertThat(row)
            .containsEntry("suppressed", true).containsEntry("correctRate", null));
        assertThat(overview(sponsorToken(sponsor), publication)).containsEntry("avgDurationSec", null).containsEntry("suppressed",
            true);
        minGroup("1");
        assertThat(count(overview(sponsorToken(sponsor), publication).get("minGroup"))).isEqualTo(3);
        assertThat(questions(sponsorToken(sponsor), publication)).allSatisfy(row -> assertThat(row).containsEntry("suppressed", false));
        minGroup("5");
    }

    @Test
    void aSponsorReadsTheirOwnPublicationsOnly() {
        minGroup("3");
        String run = run();
        String sponsor = newSponsor("s-an-own-" + run);
        String other = newSponsor("s-an-other-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Mine " + run);
        for (int i = 0; i < 3; i++) {
            complete("t-an-own-" + i + "-" + run, publication, true, i > 0);
        }
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        assertThat(overview(sponsorToken(sponsor), publication)).containsEntry("suppressed", false);

        // The other sponsor asks for this publication: nothing, however asked.
        assertThat(rows(OVERVIEW, sponsorToken(other), Map.of("publicationId", publication))).isEmpty();
        assertThat(questions(sponsorToken(other), publication)).isEmpty();
        assertThat(trend(sponsorToken(other), publication, h, h.plus(Duration.ofHours(1)), "HOUR")).singleElement()
            .satisfies(row -> assertThat(counts(row)).containsOnly(0L));
        assertThat(trend(sponsorToken(other), null, h, h.plus(Duration.ofHours(1)), "HOUR")).singleElement()
            .satisfies(row -> assertThat(counts(row)).containsOnly(0L));
        for (String asked : java.util.Arrays.asList(publication, null)) {
            Map<String, Object> theirs = trendTotals(sponsorToken(other), asked, h, h.plus(Duration.ofHours(1)), "HOUR");
            assertThat(List.of(count(theirs.get("views")), count(theirs.get("starts")), count(theirs.get("completions")),
                count(theirs.get("spent")))).containsOnly(0L);
        }
        assertThat(count(trendTotals(sponsorToken(sponsor), publication, h, h.plus(Duration.ofHours(1)), "HOUR")
            .get("completions"))).isEqualTo(3);
        assertThat(rows(TOP, sponsorToken(other), Map.of("at", h.plus(Duration.ofMinutes(11)).toString()))).isEmpty();
        Map<String, Object> board = dashboard(sponsorToken(other), h.plus(Duration.ofMinutes(11)));
        assertThat(List.of(count(board.get("live")), count(board.get("answering")), count(board.get("spentToday")),
            count(board.get("budgetRemaining")))).containsOnly(0L);
        // An administrator holding every permission reads as themselves: nobody's.
        assertThat(rows(OVERVIEW, admin(), Map.of("publicationId", publication))).isEmpty();
        assertThat(questions(admin(), publication)).isEmpty();
        // And the sponsor's own view of all their publications is sponsorToken(other).
        assertThat(trend(sponsorToken(sponsor), null, h, h.plus(Duration.ofHours(1)), "HOUR")).singleElement()
            .satisfies(row -> assertThat(count(row.get("completions"))).isEqualTo(3));
        String theirExport = export(OVERVIEW, sponsorToken(other), "csv", Map.of("publicationId", publication), 200, "en");
        assertThat(theirExport.lines().count()).isEqualTo(1);
        assertThat(export(QUESTIONS, sponsorToken(other), "csv", Map.of("publicationId", publication), 200, "en").lines().count())
            .isEqualTo(1);
    }

    @Test
    void nobodyButASponsorReadsTheAnalytics() {
        List<String> refused = List.of(taker(user("t-an-perm")), tokenOf(user("an-content"), QbRoles.ADMIN_CONTENT),
            tokenOf(user("an-finance"), QbRoles.ADMIN_FINANCE), tokenOf(user("an-super"), QbRoles.ADMIN_SUPER));
        Map<String, Object> params = Map.of("publicationId", UUID.randomUUID().toString(), "at",
            clock.instant().toString(), "from", clock.instant().toString(), "to",
            clock.instant().plus(Duration.ofHours(1)).toString(), "bucket", "HOUR");
        for (String token : refused) {
            for (String template : TEMPLATES) {
                client.post().uri("/api/queries/" + template).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of("params", params)).exchange()
                    .expectStatus().isForbidden();
            }
            export(TREND, token, "csv", params, 403, "en");
        }
        for (String template : TEMPLATES) {
            client.post().uri("/api/queries/" + template).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("params", params)).exchange().expectStatus().isUnauthorized();
        }
        // Only the sponsors hold it; the job's processes are the system's.
        assertThat(QbRoles.all()).filteredOn(role -> role.permissions().contains("qb.stats.read"))
            .extracting(QbRoles.Role::code).containsExactly(QbRoles.SPONSOR);
        assertThat(QbRoles.all()).noneMatch(role -> role.permissions().contains("qb.stats.system"));
        refusedAsInternal("QB_ANALYTICS_ITEM", admin(), body("at", clock.instant().toString(), "kind", "RUN"));
    }

    @Test
    void theExportHasTheAggregatesUnderTranslatedHeadings() {
        minGroup("3");
        String run = run();
        String sponsor = newSponsor("s-an-export-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Export " + run);
        List<String> takers = List.of("t-an-x-1-" + run, "t-an-x-2-" + run, "t-an-x-3-" + run);
        for (String taker : takers) {
            complete(taker, publication, true, true);
        }
        runAnalytics(h.plus(Duration.ofMinutes(10)));

        Map<String, Object> trendParams = Map.of("publicationId", publication, "from", h.toString(), "to",
            h.plus(Duration.ofHours(2)).toString(), "bucket", "HOUR");
        List<String> trend = export(TREND, sponsorToken(sponsor), "csv", trendParams, 200, "en").lines().toList();
        assertThat(trend).hasSize(1 + 2);
        assertThat(trend.getFirst()).contains("Completions", "Spent (Kudos)", "Views").doesNotContain("JPY");
        String overview = export(OVERVIEW, sponsorToken(sponsor), "csv", Map.of("publicationId", publication), 200, "en");
        assertThat(overview.lines().count()).isEqualTo(2);
        assertThat(overview.lines().findFirst().orElseThrow()).contains("Average time (s)", "Largest drop");
        String questions = export(QUESTIONS, sponsorToken(sponsor), "csv", Map.of("publicationId", publication), 200, "zh");
        assertThat(questions.lines().count()).isEqualTo(1 + 2);
        assertThat(questions.lines().findFirst().orElseThrow()).contains("正确率", "难度");
        // Aggregates only: no taker, no answer.
        for (String text : List.of(String.join("\n", trend), overview, questions)) {
            assertThat(text).doesNotContain("@example.com", "Paris", "Lyon");
            for (String taker : takers) {
                assertThat(text).doesNotContain(user(taker));
            }
        }
        client.post().uri("/api/queries/" + QUESTIONS + "/export?format=xlsx").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, sponsorToken(sponsor)).bodyValue(Map.of("params", Map.of("publicationId",
                publication))).exchange().expectStatus().isOk();
    }
}
