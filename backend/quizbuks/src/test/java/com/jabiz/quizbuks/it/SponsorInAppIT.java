package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sponsor in the taker app (Q68, docs/quizbuks/plans/Q68-sponsor-in-app.md): with the app's sign-in (the taker's
 * role only) a sponsor reads their own account, figures and publications on, and pauses and resumes their own
 * publication; another sponsor's are not there and a taker who is no sponsor finds nothing.
 */
class SponsorInAppIT extends PlayItSupport {

    private String admin68() {
        return TestTokens.bearer(tokens, "q68-content-admin", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    @Test
    void aSponsorReadsTheirOwnInTheAppAndNobodyElseDoes() {
        String sponsor = newSponsor("s-q68");
        String other = newSponsor("s-q68-other");
        credit(sponsor, 10_000);
        String live = livePublication(sponsor, "Q68 live");
        String theirs = livePublication(other, "Q68 other's");
        start(taker(u1()), live);
        // The app's token: the taker's role, as the app's sign-in gives it to a sponsor.
        String app = taker(sponsor);

        assertThat(rows("qb.app.my-sponsor", app, Map.of())).singleElement().satisfies(row ->
            assertThat(row).containsEntry("company", "Company of " + sponsor).containsEntry("reviewStatus", "APPROVED"));
        assertThat(rows("qb.app.sponsor-publications", app, Map.of())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("publicationId", live).containsEntry("name", "Q68 live")
                .containsEntry("status", "LIVE").containsEntry("pausedBy", null);
            assertThat(AnalyticsItSupport.count(row.get("starts"))).isEqualTo(1);
            assertThat(AnalyticsItSupport.count(row.get("answering"))).isEqualTo(1);
            assertThat(AnalyticsItSupport.count(row.get("completions"))).isZero();
        });
        Map<String, Object> board = rows("qb.sponsor.dashboard", app, Map.of("at", clock.instant().toString()))
            .getFirst();
        assertThat(AnalyticsItSupport.count(board.get("live"))).isEqualTo(1);
        assertThat(AnalyticsItSupport.count(board.get("answering"))).isEqualTo(1);

        // A taker who is no sponsor: no account, no publications.
        assertThat(rows("qb.app.my-sponsor", taker(u2()), Map.of())).isEmpty();
        assertThat(rows("qb.app.sponsor-publications", taker(u2()), Map.of())).isEmpty();
        // Nor anything of the other sponsor's, nor theirs of this one's.
        assertThat(rows("qb.app.sponsor-publications", taker(other), Map.of()))
            .extracting(row -> row.get("publicationId")).containsExactly(theirs);
    }

    @Test
    void aSponsorPausesAndResumesTheirOwnFromTheAppButNoOneElsesNorWhatAnAdministratorPaused() {
        String sponsor = newSponsor("s-q68-pause");
        String other = newSponsor("s-q68-pause-other");
        credit(sponsor, 10_000);
        String live = livePublication(sponsor, "Q68 pause");
        String app = taker(sponsor);

        assertThat(run("QB_PUBLICATION_PAUSE", app, body("publicationId", live))).containsEntry("status", "PAUSED");
        assertThat(rows("qb.app.sponsor-publications", app, Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "PAUSED").containsEntry("pausedBy", "SPONSOR"));
        // Another's publication is not found, from the app or the console; a taker's neither.
        refused("QB_PUBLICATION_RESUME", taker(other), body("publicationId", live), 404);
        refused("QB_PUBLICATION_RESUME", sponsorToken(other), body("publicationId", live), 404);
        refused("QB_PUBLICATION_PAUSE", taker(u1()), body("publicationId", live), 404);
        assertThat(run("QB_PUBLICATION_RESUME", app, body("publicationId", live))).containsEntry("status", "LIVE");

        // What an administrator paused only an administrator resumes, from the app as from the console.
        run(PublicationAdmin.PAUSE, admin68(), body("publicationId", live, "reason", "Checking"));
        assertThat(rows("qb.app.sponsor-publications", app, Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("pausedBy", "ADMIN"));
        assertThat(codes(refused("QB_PUBLICATION_RESUME", app, body("publicationId", live), 422)))
            .containsExactly(PublishingCodes.ADMIN_PAUSED);
        // Nothing that moves Kudos is open to the app's sign-in.
        for (String process : List.of("QB_PUBLICATION_SAVE", "QB_PUBLICATION_SUBMIT", "QB_TOPUP_START",
            "QB_CASH_REWARD_MARK_PAID")) {
            call(process, app, body()).expectStatus().isForbidden();
        }
    }
}
