package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication's own cover (Q64, docs/quizbuks/plans/Q64-home-polish.md section 4): set in the draft through
 * {@code QB_PUBLICATION_SAVE}, reviewed and frozen with the rest of the policy; its card, and so the takers' home,
 * detail page and history, show it instead of the quiz version's cover, also after a switch to another version. Without
 * one, the version's cover is shown as before.
 */
class PublicationCoverIT extends PlayItSupport {

    @BeforeEach
    void reviewedByDefault() {
        autoApprove(true);
    }

    /** A picture uploaded by the sponsor. */
    private String picture(String token, String name) {
        return (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), name, "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    /** A version of a new quiz of the sponsor's whose cover is {@code cover}. */
    private String versionWithCover(String token, String title, String cover) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300, "cover", cover));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** Saves and submits a complete publication of the version, live now for a week, with {@code cover} if given. */
    private String live(String token, String versionId, String name, String cover) {
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        if (cover != null) {
            save.put("cover", cover);
        }
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(submit(token, publicationId).get("status")).isEqualTo("LIVE");
        return publicationId;
    }

    private Map<String, Object> detail(String token, String publicationId) {
        return items("qb.app.publication", token, Map.of("params", Map.of("publicationId", publicationId, "at",
            clock.instant().toString(), "country", "JP"))).getFirst();
    }

    private Object inFeed(String token, String publicationId) {
        return feed(token, "JP", Map.of()).stream().filter(item -> publicationId.equals(item.get("publicationId")))
            .findFirst().orElseThrow().get("cover");
    }

    private Object inSponsorList(String token, String publicationId) {
        return items("qb.sponsor.publications", token, Map.of("limit", 100)).stream()
            .filter(row -> publicationId.equals(row.get("publicationId"))).findFirst().orElseThrow().get("cover");
    }

    private int fileStatus(String fileId, String token) {
        return client.get().uri("/api/files/{id}/content", fileId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectBody(byte[].class).returnResult().getStatus().value();
    }

    @Test
    void theCardShowsThePublicationsOwnCoverElseTheQuizs() {
        String sponsor = newSponsor("s-q64-cover");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String quizCover = picture(token, "quiz.jpg");
        String ownCover = picture(token, "own.jpg");
        String versionId = versionWithCover(token, "Q64 cover quiz", quizCover);

        // Two publications of the same version: one with a cover of its own, one without.
        String own = live(token, versionId, "Q64 own cover", ownCover);
        String plain = live(token, versionId, "Q64 quiz cover", null);
        assertThat(row(own)).containsEntry("cover", UUID.fromString(ownCover));
        assertThat(row(plain)).containsEntry("cover", null);
        assertThat(card(own)).containsEntry("cover", ownCover);
        assertThat(card(plain)).containsEntry("cover", quizCover);

        String taker = taker(newTaker("t-q64-cover", "JP"));
        assertThat(inFeed(taker, own)).isEqualTo(ownCover);
        assertThat(inFeed(taker, plain)).isEqualTo(quizCover);
        assertThat(detail(taker, own)).containsEntry("cover", ownCover);
        assertThat(detail(taker, plain)).containsEntry("cover", quizCover);
        // The sponsor's list shows the same, and the takers read the file.
        assertThat(inSponsorList(token, own)).isEqualTo(ownCover);
        assertThat(inSponsorList(token, plain)).isEqualTo(quizCover);
        assertThat(fileStatus(ownCover, taker)).isEqualTo(200);

        // The history shows the publication's own cover too.
        String player = newTaker("t-q64-history", "JP");
        play(player, own, true, true);
        assertThat(items("qb.app.history", taker(player), Map.of()).stream()
            .filter(row -> own.equals(row.get("publicationId"))).findFirst().orElseThrow())
            .containsEntry("cover", ownCover);
        assertNothingUpdated();
    }

    @Test
    void theCoverIsReviewedAndFrozenWithThePolicy() {
        String sponsor = newSponsor("s-q64-review");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String ownCover = picture(token, "own.jpg");
        String versionId = version(token, "Q64 review quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Q64 review", now, now.plus(Duration.ofDays(7)));
        save.put("cover", ownCover);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        autoApprove(false);
        Map<String, Object> submitted = submit(token, publicationId);
        assertThat(submitted.get("status")).isEqualTo("PENDING_REVIEW");
        String requestId = (String) row(publicationId).get("approval_request_id");
        Map<String, Object> request = latest("sys_approval_request_version", "request_id",
            "request_id = '" + requestId + "'").getFirst();
        // The approval is bound to the cover: the content's hash names it (without a cover, the hash would not).
        Map<String, Object> version = latest("qb_quiz_version_version", "version_id", "version_id = '" + versionId
            + "'").getFirst();
        var draft = new com.jabiz.quizbuks.publishing.PublicationReadiness.Draft(UUID.fromString(versionId),
            "Q64 review", "JP,ASIA", Instant.parse((String) save.get("startAt")),
            Instant.parse((String) save.get("endAt")), 300, 3, java.math.BigDecimal.valueOf(10_000), 50);
        List<com.jabiz.quizbuks.publishing.TierCoverage.Tier> tiers = List.of(
            new com.jabiz.quizbuks.publishing.TierCoverage.Tier(0, 2, java.math.BigDecimal.ZERO),
            new com.jabiz.quizbuks.publishing.TierCoverage.Tier(3, 4, java.math.BigDecimal.TEN),
            new com.jabiz.quizbuks.publishing.TierCoverage.Tier(5, 5, java.math.BigDecimal.valueOf(100)));
        String hashWith = PublicationApprovals.hash(PublicationApprovals.content(draft,
            String.valueOf(version.get("content_hash")).trim(), tiers, ownCover));
        String hashWithout = PublicationApprovals.hash(PublicationApprovals.content(draft,
            String.valueOf(version.get("content_hash")).trim(), tiers));
        assertThat(String.valueOf(request.get("content_hash")).trim()).isEqualTo(hashWith).isNotEqualTo(hashWithout);

        // Submitted, the cover cannot change (nor while it runs): a new cover takes a copy, reviewed again.
        Map<String, Object> change = new LinkedHashMap<>(save);
        change.put("publicationId", publicationId);
        change.put("cover", picture(token, "other.jpg"));
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, change, 422)))
            .containsExactly(PublishingCodes.NOT_DRAFT);
        decide(requestId, "APPROVE", null);
        assertThat(card(publicationId)).containsEntry("cover", ownCover);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, change, 422)))
            .containsExactly(PublishingCodes.NOT_DRAFT);
        // A copy keeps the cover, as a draft of its own.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId))
            .get("publicationId");
        assertThat(row(copy)).containsEntry("cover", UUID.fromString(ownCover)).containsEntry("status", "DRAFT");
        autoApprove(true);
        assertNothingUpdated();
    }

    @Test
    void aSwitchToAnotherVersionKeepsThePublicationsOwnCover() {
        String sponsor = newSponsor("s-q64-switch");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String ownCover = picture(token, "own.jpg");
        String firstCover = picture(token, "first.jpg");
        String secondCover = picture(token, "second.jpg");
        String first = versionWithCover(token, "Q64 switch quiz", firstCover);
        String own = live(token, first, "Q64 switch own", ownCover);
        String plain = live(token, first, "Q64 switch plain", null);
        String quizId = String.valueOf(row(own).get("quiz_id"));
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Q64 switch quiz, revised", "timeLimitSec", 300,
            "cover", secondCover));
        String second = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        for (String publicationId : List.of(own, plain)) {
            run(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId", second));
        }
        assertThat(card(own)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("quiz_title", "Q64 switch quiz, revised").containsEntry("cover", ownCover);
        assertThat(card(plain)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("cover", secondCover);
        assertNothingUpdated();
    }

    @Test
    void onlyTheSponsorsOwnFileIsACover() {
        String sponsor = newSponsor("s-q64-own");
        String other = newSponsor("s-q64-other");
        String token = sponsorToken(sponsor);
        String theirs = picture(sponsorToken(other), "theirs.jpg");
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(token, "Q64 own quiz"), "Q64 own", now,
            now.plus(Duration.ofDays(7)));
        save.put("cover", theirs);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(ContentCodes.FILE_NOT_OWN);
        // Saved with the sponsor's own, then cleared: the draft has no cover, the version's is shown.
        String mine = picture(token, "mine.jpg");
        save.put("cover", mine);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(row(publicationId)).containsEntry("cover", UUID.fromString(mine));
        Map<String, Object> cleared = new LinkedHashMap<>(save);
        cleared.remove("cover");
        cleared.put("publicationId", publicationId);
        run("QB_PUBLICATION_SAVE", token, cleared);
        assertThat(row(publicationId)).containsEntry("cover", null);
        assertNothingUpdated();
    }
}
