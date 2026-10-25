package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.invitation.InvitationProcesses;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The investors' figures (docs/quizbuks/plans/Q27-investor-metrics.md): a month of merchants, takers, attempts,
 * prizes, invitations and money played through, then read back month by month ({@code qb.admin.investor-metrics})
 * and merchant by merchant ({@code qb.admin.investor-sponsors}), finance's permission on both. The months are UTC;
 * a merchant counts as retained when active this month and three months earlier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3049",
    "jabiz.security.entries.app.self-registration=true"})
class InvestorMetricsIT extends PayoutItSupport {

    static final String MONTHS = "qb.admin.investor-metrics";
    static final String SPONSORS = "qb.admin.investor-sponsors";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3049, null,
        ServerSetup.PROTOCOL_SMTP));

    @Autowired
    @Qualifier("qbInvitationMailJob")
    JobDefinition<InvitationProcesses.InvitationRef> mailJob;

    String finance() {
        return tokenOf(user("q27-finance"), QbRoles.ADMIN_FINANCE);
    }

    String methodsAdmin() {
        return TestTokens.bearer(tokens, "q27-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    List<Map<String, Object>> report(String id, Instant from, Instant to) {
        return items(id, finance(), Map.of("params", Map.of("from", from.toString(), "to", to.toString()),
            "limit", 100));
    }

    static Instant monthStart(Instant at, int monthsAhead) {
        return at.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).plusMonths(monthsAhead)
            .toInstant();
    }

    static String label(Instant monthStart) {
        return monthStart.atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7);
    }

    static long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    static BigDecimal money(Map<String, Object> row, String key) {
        return new BigDecimal(String.valueOf(row.get(key)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPeriodIsReadBackByMonthAndByMerchant() {
        // Months after the one the shared players were made in, so that only this test's own doings count.
        Instant monthA = monthStart(clock.instant(), 1);
        Instant monthB = monthStart(clock.instant(), 4);
        Instant end = monthStart(clock.instant(), 5);
        List<String> labels = List.of(label(monthA), label(monthStart(monthA, 1)), label(monthStart(monthA, 2)),
            label(monthB));

        // Month A: a merchant approved, funded and attempted once by a new taker who passes (a Kudos reward).
        clock.set(monthA.plus(Duration.ofDays(1)));
        String sponsorA = newSponsor("s-q27-a");
        credit(sponsorA, 100_000);
        String firstPublication = livePublication(sponsorA, "Q27 A", Map.of("regions", List.of("GLOBAL")));
        String taker1 = newTaker("t-q27-t1", "JP");
        Map<String, Object> firstPlay = play(taker1, firstPublication, true, true);
        assertThat(firstPlay).containsEntry("rewardStatus", "PAID");
        BigDecimal feeA = new BigDecimal(String.valueOf(attempt((String) firstPlay.get("attemptId")).get("fee")));

        // Month B, three months later: the first merchant is attempted again (retained); a second merchant is
        // approved, publishes a prize quiz that one taker wins and collects at the counter and another fails, and an
        // invitation-only quiz whose one invitation is sent; the second merchant also tops up by card.
        clock.set(monthB.plus(Duration.ofDays(1)));
        String secondPublication = livePublication(sponsorA, "Q27 B", Map.of("regions", List.of("GLOBAL")));
        String taker2 = newTaker("t-q27-t2", "JP");
        Map<String, Object> secondPlay = play(taker2, secondPublication, true, true);
        assertThat(secondPlay).containsEntry("rewardStatus", "PAID");
        BigDecimal feeB = new BigDecimal(String.valueOf(attempt((String) secondPlay.get("attemptId")).get("fee")));

        String sponsorB = newSponsor("s-q27-b");
        run(RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsorB, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q27 test"));
        String prizePublication = livePublication(sponsorB, "Q27 prize", Map.of("regions", List.of("GLOBAL"),
            "rewardMethod", RewardMethod.OFFLINE_REDEEM, "redeemDescription", "A coffee"));
        String token1 = taker(taker1);
        String prizeAttempt = (String) start(token1, prizePublication).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(token1, prizeAttempt, answers(true, true))).containsEntry("rewardStatus", "REDEEM");
        deliverEvents();
        assertThat(play(taker2, prizePublication, false, false)).containsEntry("rewardStatus", "NONE");
        String redeemKey = (String) run(RedemptionProcesses.ISSUE, sponsorToken(sponsorB), body("kind", "ROTATING"))
            .get("redeemKey");
        List<Map<String, Object>> prizes = (List<Map<String, Object>>) run(RedemptionProcesses.CHECK, token1,
            body("scannedKey", redeemKey)).get("prizes");
        assertThat(run(RedemptionProcesses.CLAIM, token1, body("scannedKey", redeemKey, "redemptionId",
            prizes.getFirst().get("redemptionId")))).containsEntry("status", "REDEEMED");

        String invitedPublication = livePublication(sponsorB, "Q27 invited", Map.of("audience", "INVITED",
            "regions", List.of()));
        run("QB_INVITATION_ADD", sponsorToken(sponsorB), body("publicationId", invitedPublication, "emails",
            List.of("q27-invitee@example.com")));
        run("QB_INVITATION_SEND", sponsorToken(sponsorB), body("publicationId", invitedPublication));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobs.execute(mailJob, clock.instant());
        deliverEvents();

        String topUpId = openTopUp(sponsorB, 5_000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");

        // By month: four rows for the four months, the two quiet ones all zeros.
        List<Map<String, Object>> months = report(MONTHS, monthA, end);
        assertThat(months).extracting(row -> (String) row.get("month")).containsExactlyElementsOf(labels);
        Map<String, Object> a = months.get(0);
        assertThat(n(a, "newSponsors")).isEqualTo(1);
        assertThat(n(a, "activeSponsors")).isEqualTo(1);
        assertThat(n(a, "retainedSponsors")).isZero();
        assertThat(n(a, "publicationsWentOn")).isEqualTo(1);
        assertThat(n(a, "newTakers")).isEqualTo(1);
        assertThat(n(a, "activeTakers")).isEqualTo(1);
        assertThat(n(a, "attempts")).isEqualTo(1);
        assertThat(n(a, "completions")).isEqualTo(1);
        assertThat(n(a, "passes")).isEqualTo(1);
        assertThat(n(a, "prizesWon")).isZero();
        assertThat(n(a, "invitationsSent")).isZero();
        assertThat(n(a, "topUps")).isZero();
        assertThat(money(a, "rewardsPaid")).isEqualByComparingTo("100");
        assertThat(money(a, "commission")).isEqualByComparingTo(feeA);
        for (Map<String, Object> quiet : months.subList(1, 3)) {
            for (String key : List.of("newSponsors", "activeSponsors", "retainedSponsors", "publicationsWentOn",
                "newTakers", "activeTakers", "attempts", "completions", "passes", "prizesWon", "prizesClaimed",
                "prizesRedeemed", "invitationsSent", "invitationsAnswered", "topUps")) {
                assertThat(n(quiet, key)).as(quiet.get("month") + " " + key).isZero();
            }
            assertThat(money(quiet, "topUpAmount")).isZero();
            assertThat(money(quiet, "rewardsPaid")).isZero();
        }
        Map<String, Object> b = months.get(3);
        assertThat(n(b, "newSponsors")).isEqualTo(1);
        assertThat(n(b, "activeSponsors")).isEqualTo(2);
        assertThat(n(b, "retainedSponsors")).isEqualTo(1);
        assertThat(n(b, "publicationsWentOn")).isEqualTo(3);
        assertThat(n(b, "newTakers")).isEqualTo(1);
        assertThat(n(b, "activeTakers")).isEqualTo(2);
        assertThat(n(b, "attempts")).isEqualTo(3);
        assertThat(n(b, "completions")).isEqualTo(3);
        assertThat(n(b, "passes")).isEqualTo(2);
        assertThat(n(b, "prizesWon")).isEqualTo(1);
        // The rotating code claims and collects in one step: the claim stands.
        assertThat(n(b, "prizesClaimed")).isEqualTo(1);
        assertThat(n(b, "prizesRedeemed")).isEqualTo(1);
        assertThat(n(b, "invitationsSent")).isEqualTo(1);
        assertThat(n(b, "invitationsAnswered")).isZero();
        assertThat(n(b, "topUps")).isEqualTo(1);
        assertThat(money(b, "topUpAmount")).isEqualByComparingTo("5000");
        assertThat(money(b, "rewardsPaid")).isEqualByComparingTo("100");
        assertThat(money(b, "commission")).isEqualByComparingTo(feeB);

        // By merchant for month B: the prize merchant first (two attempts), then the first merchant (one).
        List<Map<String, Object>> sponsors = report(SPONSORS, monthB, end);
        assertThat(sponsors).extracting(row -> row.get("sponsorId")).containsExactly(sponsorB, sponsorA);
        Map<String, Object> second = sponsors.get(0);
        assertThat(second.get("company")).isNotNull();
        assertThat(n(second, "takers")).isEqualTo(2);
        assertThat(n(second, "attempts")).isEqualTo(2);
        assertThat(n(second, "completions")).isEqualTo(2);
        assertThat(n(second, "passes")).isEqualTo(1);
        assertThat(n(second, "prizesWon")).isEqualTo(1);
        assertThat(n(second, "prizesRedeemed")).isEqualTo(1);
        assertThat(n(second, "invitationsSent")).isEqualTo(1);
        Map<String, Object> first = sponsors.get(1);
        assertThat(n(first, "attempts")).isEqualTo(1);
        assertThat(n(first, "passes")).isEqualTo(1);
        assertThat(n(first, "prizesWon")).isZero();
        // Month A alone has the first merchant only.
        assertThat(report(SPONSORS, monthA, monthStart(monthA, 1))).extracting(row -> row.get("sponsorId"))
            .containsExactly(sponsorA);

        // A `to` inside month B still yields month B (the end is exclusive, the month is whole); a period of more than
        // 36 months is cut at 36 (the admin page refuses one); a quiet period lists no merchant.
        assertThat(report(MONTHS, monthA, monthB.plus(Duration.ofHours(2)))).hasSize(4);
        assertThat(report(MONTHS, monthA, monthStart(monthA, 37))).hasSize(36);
        assertThat(report(SPONSORS, monthStart(monthA, 1), monthStart(monthA, 3))).isEmpty();

        // Finance's templates: a sponsor cannot read them.
        client.post().uri("/api/queries/" + MONTHS).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, sponsorToken(sponsorA))
            .bodyValue(Map.of("params", Map.of("from", monthA.toString(), "to", end.toString()))).exchange()
            .expectStatus().isForbidden();
    }
}
