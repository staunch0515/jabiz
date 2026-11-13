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
        System.out.println("[statement-budget] submit with exposures: " + buying.size() + "\\n" + listing(buying));
        System.out.println("[statement-budget] top-up: " + topUp.size() + "\\n" + listing(topUp));
        System.out.println("[statement-budget] locks: " + locks);
        assertThat(buying).as("submit with exposures:\\n" + listing(buying)).hasSize(SUBMIT_WITH_EXPOSURES);
        assertThat(topUp).as("top-up:\\n" + listing(topUp)).hasSize(TOP_UP);
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
