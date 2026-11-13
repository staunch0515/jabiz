package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8 (Fable M4 of #685): the statements of a submission that buys exposures against one that buys none, and of a top-up.
 * A submission that buys none sends what {@link SubmitStatementBudgetIT} counts (the price read is keyed by nothing and
 * sends no SQL); the ones that buy are counted here, each once before it is counted (warm caches).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class ExposureSubmitStatementBudgetIT extends ExposureFeeItSupport {

    /** Another's video, no audience rule, 105 exposures bought (prepaid): measured, then fixed here on purpose. */
    static final int SUBMIT_WITH_EXPOSURES = 43;
    /** A top-up: the locks, the account and the prepayment, its parts, the freeze's sub-process, the purchase's sub-process. */
    static final int TOP_UP = 24;

    @Test
    void theStatementsOfABuyingSubmissionAndATopUp() {
        autoApprove(true);
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-budget-provider"));
        String sponsor = reviewedSponsor("fm8-budget");
        grant(sponsor, 100_000, clock.instant().plus(Duration.ofDays(90)));
        counted(sponsor, draft(sponsor, "warm", sharedVideoId, 105L));
        String publicationId = draft(sponsor, "buy", sharedVideoId, 105L);
        List<String> buying = counted(sponsor, publicationId);
        List<String> locks = SqlStatementLog.locks();
        // The same order as any submission: the quiz, the publication, then the sponsor's wallet; the sub-process that
        // sets the bought exposures takes the publication's lock again (held already: no wait).
        assertThat(locks).hasSize(4);
        assertThat(locks.get(0)).startsWith("qb.quiz:");
        assertThat(locks.get(1)).startsWith("qb.pub:");
        assertThat(locks.get(2)).isEqualTo("qb.wallet:SPONSOR:" + sponsor);
        assertThat(locks.get(3)).isEqualTo(locks.get(1));

        SqlStatementLog.clear();
        run(com.jabiz.quizbuks.publishing.PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), body(
            "publicationId", publicationId, "expectedPurchased", 105, "additional", 5));
        List<List<String>> own = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        List<String> topUp = own.getFirst();
        // The top-up's locks: the publication, then the sponsor's wallet, then the publication again (the purchase's
        // sub-process; held already, no wait).
        List<String> topUpLocks = SqlStatementLog.locks();
        assertThat(topUpLocks).hasSize(3);
        assertThat(topUpLocks.get(0)).startsWith("qb.pub:");
        assertThat(topUpLocks.get(1)).isEqualTo("qb.wallet:SPONSOR:" + sponsor);
        assertThat(topUpLocks.get(2)).isEqualTo(topUpLocks.get(0));
        System.out.println("[statement-budget] submit with exposures: " + buying.size() + "\\n" + listing(buying));
        System.out.println("[statement-budget] top-up: " + topUp.size() + "\\n" + listing(topUp));
        System.out.println("[statement-budget] locks: " + locks);
        assertThat(buying).as("submit with exposures:\\n" + listing(buying)).hasSize(SUBMIT_WITH_EXPOSURES);
        assertThat(topUp).as("top-up:\\n" + listing(topUp)).hasSize(TOP_UP);
    }

    /**
     * A month's settlement of a publication with 5 successes, paid from its deposit [20, measured]: the operation
     * record, the parameters, the wallet lock, the four reads and the count, the charge (a sub-process), the deposit's
     * use and the settlement row.
     */
    static final int SETTLE_MONTH = 20;
    /**
     * The final settlement of an ended publication [26, measured]: the month's as above, and the release that gives
     * the rest of the deposit back (its reads, the prepayment's closing and the posting's parts).
     */
    static final int SETTLE_FINAL = 26;

    @Test
    void theStatementsOfAMonthsAndOfTheFinalSettlement() {
        autoApprove(true);
        String provider = reviewedSponsor("fm8-settle-budget-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor("fm8-settle-budget");
        grant(sponsor, 100_000, clock.instant().plus(Duration.ofDays(90)));
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Tokyo");
        List<String> month = null;
        List<String> last = null;
        for (int i = 0; i < 2; i++) {
            String publicationId = draft(sponsor, "settle " + i, sharedVideoId, 105L, clock.instant());
            submit(sponsorToken(sponsor), publicationId);
            successes(publicationId, sponsor, provider, 5);
            java.time.YearMonth first = java.time.YearMonth.parse(com.jabiz.quizbuks.fee.FeeRules.period(clock.instant(),
                zone));
            // The month is over: its settlement is run in the next one (as the job does, 00:47 of the 2nd).
            clock.set(first.plusMonths(1).atDay(2).atTime(0, 47).atZone(zone).toInstant());
            month = settled(sponsor, publicationId, first.toString(), false);
            successes(publicationId, sponsor, provider, 5);
            run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "budget"));
            // Past the grace after the end (qb.exposure.final-end-grace-sec, an hour), as the final job at 03:12.
            clock.set(clock.instant().plus(Duration.ofHours(2)));
            last = settled(sponsor, publicationId, first.plusMonths(1).toString(), true);
        }
        System.out.println("[statement-budget] settlement of a month: " + month.size() + "\n" + listing(month));
        System.out.println("[statement-budget] final settlement: " + last.size() + "\n" + listing(last));
        assertThat(month).as("month:\n" + listing(month)).hasSize(SETTLE_MONTH);
        assertThat(last).as("final:\n" + listing(last)).hasSize(SETTLE_FINAL);
    }

    @Override
    String contentAdmin() {
        return com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm8-content-admin-budget",
            com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE);
    }

    private void successes(String publicationId, String sponsor, String provider, int n) {
        for (int i = 0; i < n; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", java.util.UUID.randomUUID().toString(), publicationId,
                "fm8-" + java.util.UUID.randomUUID(), sponsor, provider,
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        }
    }

    /** The statements of the transaction that wrote the settlement. */
    private List<String> settled(String sponsor, String publicationId, String period, boolean last) {
        SqlStatementLog.clear();
        systemRun(com.jabiz.quizbuks.fee.ExposureSettlements.SETTLE, body("publicationId", publicationId, "sponsorId",
            sponsor, "period", period, "last", last));
        List<List<String>> own = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.contains("INSERT INTO qb_exposure_settlement_version"))).toList();
        assertThat(own).as("one transaction; all:\n" + SqlStatementLog.transactions().stream().map(
            ExposureSubmitStatementBudgetIT::listing).collect(Collectors.joining("\n----\n"))).hasSize(1);
        return own.getFirst();
    }

    List<String> counted(String sponsor, String publicationId) {
        SqlStatementLog.clear();
        submit(sponsorToken(sponsor), publicationId);
        List<List<String>> own = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        assertThat(own).as("one transaction").hasSize(1);
        return own.getFirst();
    }

    private static String listing(List<String> statements) {
        return IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, statements.get(i).replaceAll("\\s+", " ")))
            .collect(Collectors.joining("\n"));
    }
}
