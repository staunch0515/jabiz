package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationVersions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question analysis per version of a publication that switched its version (Q51b): each attempt counts for the
 * version it ran on, the sponsor chooses the version (the one it runs on by default) among those it ran on only, and
 * a version's figures change only after the minimum group of new completions of that version.
 */
class AnalyticsVersionsIT extends AnalyticsItSupport {

    static final String VERSIONS = "qb.sponsor.analytics.versions";

    private String versionOf(String publicationId) {
        return String.valueOf(card(publicationId).get("version_id"));
    }

    /** Another version of the publication's quiz (the same questions, another title): full score 5 as well. */
    private String nextVersion(String token, String publicationId, String title) {
        String quizId = String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '"
            + versionOf(publicationId) + "'").getFirst().get("quiz_id"));
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    private List<Map<String, Object>> questionsOf(String token, String publicationId, String versionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("publicationId", publicationId);
        if (versionId != null) {
            params.put("versionId", versionId);
        }
        return rows(QUESTIONS, token, params);
    }

    /** Each question's completions and right rate, in order: {@code [completions, correctRate]}. */
    private static List<List<Object>> figures(List<Map<String, Object>> questions) {
        return questions.stream().map(row -> List.<Object>of(count(row.get("completions")),
            row.get("correctRate") == null ? "-" : decimal(row.get("correctRate")).stripTrailingZeros().toPlainString()))
            .toList();
    }

    @Test
    void eachVersionHasItsOwnQuestionFiguresAndMinimumGroup() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        minGroup("3");
        String sponsor = newSponsor("s-q51b-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h);
        String publication = analysedPublication(sponsor, "Q51b " + run);
        String first = versionOf(publication);
        // Three on the first version answer question 1 only right; one more starts it and has not submitted.
        for (int i = 1; i <= 3; i++) {
            complete("t-q51b-a" + i + "-" + run, publication, true, false);
        }
        String late = newTaker("t-q51b-late-" + run, null);
        String lateAttempt = startAttempt(late, publication);

        String second = nextVersion(sponsorToken(sponsor), publication, "Q51b " + run + " revised");
        run(PublicationVersions.SWITCH, sponsorToken(sponsor), body("publicationId", publication, "versionId", second));
        assertThat(versionOf(publication)).isEqualTo(second);
        // Three on the second version answer question 2 only right.
        for (int i = 1; i <= 3; i++) {
            complete("t-q51b-b" + i + "-" + run, publication, false, true);
        }
        // The late one submits within its time, after what the first run reads (attempts submitted by its lag).
        at(h.plus(Duration.ofMinutes(4)));
        submitAttempt(late, lateAttempt, false, false);
        runAnalytics(h.plus(Duration.ofMinutes(5)));

        // By default the version it runs on; the first one when asked for, each over its own completions.
        List<Map<String, Object>> current = questionsOf(sponsorToken(sponsor), publication, null);
        assertThat(figures(current)).containsExactly(List.of(3L, "0"), List.of(3L, "1"));
        assertThat(current).allSatisfy(row -> assertThat(count(row.get("basedOn"))).isEqualTo(3));
        assertThat(figures(questionsOf(sponsorToken(sponsor), publication, second))).isEqualTo(figures(current));
        assertThat(figures(questionsOf(sponsorToken(sponsor), publication, first))).containsExactly(List.of(3L, "1"), List.of(3L, "0"));
        // The versions it ran on, newest first; the first one with when it was replaced.
        List<Map<String, Object>> versions = rows(VERSIONS, sponsorToken(sponsor), Map.of("publicationId", publication));
        assertThat(versions).extracting(row -> String.valueOf(row.get("versionId"))).containsExactly(second, first);
        assertThat(versions.getFirst()).containsEntry("current", true).containsEntry("replacedAt", null);
        assertThat(versions.get(1)).containsEntry("current", false);
        assertThat(versions.get(1).get("replacedAt")).isNotNull();

        // A version the publication never ran on gives nothing, whoever owns it; another sponsor sees nothing.
        String stranger = newSponsor("s-q51b-other-" + run);
        String otherVersion = version(sponsorToken(stranger), "Q51b other " + run);
        assertThat(questionsOf(sponsorToken(sponsor), publication, otherVersion)).isEmpty();
        assertThat(questionsOf(sponsorToken(stranger), publication, first)).isEmpty();
        assertThat(rows(VERSIONS, sponsorToken(stranger), Map.of("publicationId", publication))).isEmpty();

        // The late one on the first version (started before the switch) and two more on the second: three new
        // completions in all, but fewer than three of either version, so neither version's figures change.
        at(h.plus(Duration.ofMinutes(20)));
        for (int i = 4; i <= 5; i++) {
            complete("t-q51b-b" + i + "-" + run, publication, false, true);
        }
        runAnalytics(h.plus(Duration.ofMinutes(35)));
        assertThat(figures(questionsOf(sponsorToken(sponsor), publication, first))).containsExactly(List.of(3L, "1"), List.of(3L, "0"));
        assertThat(figures(questionsOf(sponsorToken(sponsor), publication, second))).containsExactly(List.of(3L, "0"), List.of(3L, "1"));
        assertThat(String.valueOf(cursor(publication).get("questions"))).isEqualTo(recount(publication));
        // The overview counts every completion, whatever the version.
        assertThat(count(overview(sponsorToken(sponsor), publication).get("completions"))).isEqualTo(9);

        // A third new one of the second version: its figures move, the first version's stay.
        at(h.plus(Duration.ofMinutes(40)));
        complete("t-q51b-b6-" + run, publication, true, true);
        runAnalytics(h.plus(Duration.ofMinutes(55)));
        List<Map<String, Object>> moved = questionsOf(sponsorToken(sponsor), publication, second);
        assertThat(figures(moved)).containsExactly(List.of(6L, "0.1667"), List.of(6L, "1"));
        assertThat(moved).allSatisfy(row -> assertThat(count(row.get("basedOn"))).isEqualTo(6));
        assertThat(figures(questionsOf(sponsorToken(sponsor), publication, first))).containsExactly(List.of(3L, "1"), List.of(3L, "0"));
    }
}
