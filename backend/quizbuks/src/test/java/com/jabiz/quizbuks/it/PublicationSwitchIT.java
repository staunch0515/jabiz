package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication that went on switches to another version of its quiz (Q51): reviewed while it keeps running on its
 * version, one switch at a time; approved, its card and new attempts take the new version, while attempts keep the
 * version they started with and a taker who answered does not answer again. Only the version changes, to one of the
 * same quiz with the same full score; a publication taken down by an administrator does not switch.
 */
class PublicationSwitchIT extends PlayItSupport {

    @BeforeEach
    void reviewedByDefault() {
        autoApprove(true);
    }

    private String quizOf(String versionId) {
        return String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '" + versionId + "'")
            .getFirst().get("quiz_id"));
    }

    private String versionOf(String publicationId) {
        return String.valueOf(row(publicationId).get("version_id"));
    }

    /**
     * Another version of the publication's quiz, titled {@code title}; with {@code extraQuestion} a third question of 1
     * point (full score 6), and {@code cover} when given.
     */
    private String nextVersion(String token, String publicationId, String title, boolean extraQuestion, String cover) {
        String quizId = quizOf(versionOf(publicationId));
        Map<String, Object> save = body("quizId", quizId, "title", title, "timeLimitSec", 300);
        if (cover != null) {
            save.put("cover", cover);
        }
        run("QB_QUIZ_SAVE", token, save);
        if (extraQuestion) {
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Italy?", "points", 1,
                "options", List.of(body("text", "Rome", "correct", true), body("text", "Milan"))));
        }
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    private Map<String, Object> switchTo(String token, String publicationId, String versionId) {
        return run(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId", versionId));
    }

    private List<Object> switchRefused(String token, String publicationId, String versionId) {
        return codes(refused(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId",
            versionId), 422));
    }

    /** A picture uploaded by the sponsor, for a cover. */
    private String cover(String token, String name) {
        return (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), name, "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    /** The publication in the reviewers' queue ({@code qb.admin.publications} with {@code inReview}), if it is. */
    private List<Map<String, Object>> inReview(String publicationId) {
        return items("qb.admin.publications", TestTokens.bearer(tokens, "admin-q51",
            QbPermissions.ADMIN_PUBLICATION_READ), Map.of("params", Map.of("inReview", true), "filters",
            List.of(Map.of("field", "publicationId", "op", "eq", "value", publicationId))));
    }

    private String attemptVersion(String attemptId) {
        return String.valueOf(attempt(attemptId).get("version_id"));
    }

    @Test
    void aSwitchInReviewKeepsTheVersionUntilApprovedAndAttemptsKeepTheirs() {
        String sponsor = newSponsor("s-q51-review");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String publicationId = livePublication(sponsor, "Q51 review");
        String first = versionOf(publicationId);
        // One taker answered on the first version, another is answering it.
        String answered = newTaker("t-q51-answered", "JP");
        play(answered, publicationId, true, true);
        String answeredAttempt = String.valueOf(query("SELECT attempt_id FROM qb_attempt WHERE user_id = ? "
            + "AND publication_id = ?::uuid", answered, publicationId).getFirst().get("attempt_id"));
        String answering = newTaker("t-q51-answering", "JP");
        String answeringAttempt = (String) start(taker(answering), publicationId).get("attemptId");

        String second = nextVersion(token, publicationId, "Q51 review quiz, revised", false, null);
        autoApprove(false);
        switchTo(token, publicationId, second);
        Map<String, Object> pending = row(publicationId);
        String requestId = (String) pending.get("pending_request_id");
        assertThat(requestId).isNotNull();
        assertThat(pending).containsEntry("status", PublicationStatus.LIVE)
            .containsEntry("version_id", UUID.fromString(first))
            .containsEntry("pending_version_id", UUID.fromString(second))
            .containsEntry("pending_since", pending.get("pending_since"));
        assertThat(pending.get("pending_version_label")).isNotNull();
        // While in review, the publication runs on its version: a new start takes it; one switch at a time.
        assertThat(card(publicationId)).containsEntry("version_id", UUID.fromString(first));
        String meanwhile = newTaker("t-q51-meanwhile", "JP");
        assertThat(attemptVersion((String) start(taker(meanwhile), publicationId).get("attemptId")))
            .isEqualTo(first);
        assertThat(switchRefused(token, publicationId, second)).containsExactly(PublishingCodes.SWITCH_PENDING);
        // The reviewers' queue has it, waiting since the switch was asked for.
        assertThat(inReview(publicationId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("status", PublicationStatus.LIVE).containsEntry("pendingRequestId", requestId)
            .containsEntry("waitingSince", row.get("pendingSince")));

        decide(requestId, "APPROVE", null);
        assertThat(inReview(publicationId)).isEmpty();
        Map<String, Object> switched = row(publicationId);
        assertThat(switched).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("approval_request_id", requestId).containsEntry("pending_request_id", null)
            .containsEntry("pending_version_id", null).containsEntry("status", PublicationStatus.LIVE);
        assertThat(card(publicationId)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("quiz_title", "Q51 review quiz, revised");
        assertThat(query("SELECT * FROM qb_publication_past_version WHERE publication_id = ?::uuid", publicationId))
            .singleElement().satisfies(past -> assertThat(past)
                .containsEntry("version_id", UUID.fromString(first))
                .containsEntry("quiz_id", UUID.fromString(quizOf(first))));

        // A new taker answers the new version; the attempts that started before keep the first.
        String after = newTaker("t-q51-after", "JP");
        assertThat(attemptVersion((String) start(taker(after), publicationId).get("attemptId"))).isEqualTo(second);
        Map<String, Object> resumed = start(taker(answering), publicationId);
        assertThat(resumed).containsEntry("attemptId", answeringAttempt).containsEntry("resumed", true);
        assertThat(attemptVersion(answeringAttempt)).isEqualTo(first);
        assertThat(submit(taker(answering), answeringAttempt, answers(true, false))).containsEntry("score", 2);
        assertThat(attemptVersion(answeredAttempt)).isEqualTo(first);
        // Who answered does not answer again: it is the same publication.
        assertThat(codes(refused("QB_ATTEMPT_START", taker(answered), body("publicationId", publicationId), 422)))
            .containsExactly(PlayCodes.ALREADY_TAKEN);
        autoApprove(true);
        assertNothingUpdated();
    }

    @Test
    void onlyAnotherVersionOfTheQuizWithTheSameFullScoreOfARunningPublication() {
        String sponsor = newSponsor("s-q51-refused");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String publicationId = livePublication(sponsor, "Q51 refused");
        String current = versionOf(publicationId);

        assertThat(switchRefused(token, publicationId, current)).containsExactly(PublishingCodes.SWITCH_SAME_VERSION);
        String other = version(token, "Q51 another quiz");
        assertThat(switchRefused(token, publicationId, other)).containsExactly(PublishingCodes.SWITCH_OTHER_QUIZ);
        String larger = nextVersion(token, publicationId, "Q51 refused quiz, longer", true, null);
        assertThat(refused(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId",
            larger), 422).toString()).contains(PublishingCodes.SWITCH_FULL_SCORE);
        assertThat(codes(refused(PublicationVersions.SWITCH_WITHDRAW, token, body("publicationId", publicationId),
            422))).containsExactly(PublishingCodes.SWITCH_NOT_PENDING);
        // Another sponsor's publication is not found for them.
        refused(PublicationVersions.SWITCH, sponsorToken(newSponsor("s-q51-stranger")), body("publicationId",
            publicationId, "versionId", other), 404);

        // A draft never went on.
        String draft = (String) run("QB_PUBLICATION_SAVE", token, complete(other, "Q51 draft",
            clock.instant().plus(Duration.ofDays(1)), clock.instant().plus(Duration.ofDays(2)))).get("publicationId");
        assertThat(switchRefused(token, draft, version(token, "Q51 draft quiz")))
            .containsExactly(PublishingCodes.SWITCH_NOT_RUNNING);

        // Taken down by an administrator: paused, and no switch.
        String takenDown = livePublication(sponsor, "Q51 taken down");
        String revised = nextVersion(token, takenDown, "Q51 taken down quiz, revised", false, null);
        run(PublicationAdmin.PAUSE, TestTokens.bearer(tokens, "content-admin-q51", QbPermissions.ADMIN_CONTENT_WRITE),
            body("publicationId", takenDown, "reason", "Reported"));
        assertThat(switchRefused(token, takenDown, revised)).containsExactly(PublishingCodes.SWITCH_TAKEN_DOWN);
        assertNothingUpdated();
    }

    @Test
    void aSwitchInReviewCanBeWithdrawnOrRejected() {
        String sponsor = newSponsor("s-q51-withdrawn");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String publicationId = livePublication(sponsor, "Q51 withdrawn");
        String first = versionOf(publicationId);
        String second = nextVersion(token, publicationId, "Q51 withdrawn quiz, revised", false, null);
        autoApprove(false);

        switchTo(token, publicationId, second);
        String withdrawn = (String) row(publicationId).get("pending_request_id");
        run(PublicationVersions.SWITCH_WITHDRAW, token, body("publicationId", publicationId));
        assertThat(row(publicationId)).containsEntry("pending_request_id", null)
            .containsEntry("pending_version_id", null).containsEntry("version_id", UUID.fromString(first));
        assertThat(latest("sys_approval_request_version", "request_id", "request_id = '" + withdrawn + "'"))
            .singleElement().satisfies(request -> assertThat(request).containsEntry("status", "WITHDRAWN"));

        switchTo(token, publicationId, second);
        String rejected = (String) row(publicationId).get("pending_request_id");
        assertThat(rejected).isNotEqualTo(withdrawn);
        decide(rejected, "REJECT", "The new cover is misleading.");
        assertThat(row(publicationId)).containsEntry("pending_request_id", null)
            .containsEntry("version_id", UUID.fromString(first))
            .containsEntry("switch_reject_reason", "The new cover is misleading.");
        assertThat(card(publicationId)).containsEntry("version_id", UUID.fromString(first));
        assertThat(query("SELECT 1 FROM qb_publication_past_version WHERE publication_id = ?::uuid", publicationId))
            .isEmpty();

        // Asked again, the reason of the last rejection is cleared.
        switchTo(token, publicationId, second);
        assertThat(row(publicationId)).containsEntry("switch_reject_reason", null);
        autoApprove(true);
        assertNothingUpdated();
    }

    @Test
    void theReviewSwitchMakesItAtOnce() {
        String sponsor = newSponsor("s-q51-auto");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String publicationId = livePublication(sponsor, "Q51 auto");
        String second = nextVersion(token, publicationId, "Q51 auto quiz, revised", false, null);
        switchTo(token, publicationId, second);
        assertThat(row(publicationId)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("approval_request_id", null).containsEntry("pending_request_id", null);
        assertThat(card(publicationId)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("quiz_title", "Q51 auto quiz, revised");
        String taker = newTaker("t-q51-auto", "JP");
        assertThat(attemptVersion((String) start(taker(taker), publicationId).get("attemptId"))).isEqualTo(second);
        assertNothingUpdated();
    }

    @Test
    void theEndDropsASwitchInReview() {
        String sponsor = newSponsor("s-q51-ended");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String publicationId = livePublication(sponsor, "Q51 ended", Map.of("endAt",
            clock.instant().plus(Duration.ofHours(1)).toString()));
        String second = nextVersion(token, publicationId, "Q51 ended quiz, revised", false, null);
        autoApprove(false);
        switchTo(token, publicationId, second);
        String requestId = (String) row(publicationId).get("pending_request_id");
        clock.advance(Duration.ofHours(2));
        assertThat(runTick()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(row(publicationId)).containsEntry("status", PublicationStatus.ENDED)
            .containsEntry("pending_request_id", null).containsEntry("switch_reject_code",
                PublishingCodes.SWITCH_ENDED);
        assertThat(latest("sys_approval_request_version", "request_id", "request_id = '" + requestId + "'"))
            .singleElement().satisfies(request -> assertThat(request).containsEntry("status", "WITHDRAWN"));
        autoApprove(true);
        assertNothingUpdated();
    }

    @Test
    void removingTheQuizKeepsTheFilesOfTheVersionsItRanOn() {
        String sponsor = newSponsor("s-q51-files");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String publicationId = livePublication(sponsor, "Q51 files");
        String quizId = quizOf(versionOf(publicationId));
        String firstCover = cover(token, "first.jpg");
        String secondCover = cover(token, "second.jpg");
        String first = nextVersion(token, publicationId, "Q51 files quiz", false, firstCover);
        switchTo(token, publicationId, first);
        String second = nextVersion(token, publicationId, "Q51 files quiz, revised", false, secondCover);
        switchTo(token, publicationId, second);
        assertThat(card(publicationId)).containsEntry("cover", secondCover);

        run("QB_QUIZ_DELETE", token, body("quizId", quizId));
        List<Object> kept = latest("qb_version_file_version", "version_file_id", "quiz_id = '" + quizId
            + "' AND NOT is_deleted").stream().map(row -> (Object) String.valueOf(row.get("image"))).toList();
        assertThat(kept).contains(firstCover, secondCover);
        assertNothingUpdated();
    }
}
