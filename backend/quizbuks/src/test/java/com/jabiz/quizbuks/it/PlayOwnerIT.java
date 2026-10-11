package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose is what (docs/quizbuks/plans/Q5-answering.md, 测试 PlayOwnerIT): an attempt is its taker's alone; the system's
 * processes and the development credit are nobody's; no dataset writes answering's tables; administrators read them.
 */
class PlayOwnerIT extends PlayItSupport {

    @Test
    void anAttemptIsItsTakersAlone() {
        String sponsor = newSponsor("s-owner");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Owner", Map.of("regions", List.of("GLOBAL")));
        String attemptId = (String) start(taker(u1()), publicationId).get("attemptId");
        String other = taker(u2());
        refused("QB_ATTEMPT_SAVE", other, body("attemptId", attemptId, "currentIndex", 1), 404);
        refused("QB_ATTEMPT_SUBMIT", other, body("attemptId", attemptId, "answers", answers(true, true)), 404);
        assertThat(items("qb.app.history", other, Map.of())).noneMatch(row -> attemptId.equals(row.get("attemptId")));
        assertThat(rows(QbPlay.TAKER_ATTEMPTS, other)).noneMatch(row -> attemptId.equals(row.get("attemptId")));
        assertThat(attempt(attemptId)).containsEntry("status", "IN_PROGRESS").containsEntry("current_index",
            java.math.BigDecimal.ZERO);
        // Without qb.play, no answering at all.
        String visitor = TestTokens.bearer(tokens, u2(), QbPermissions.ME);
        call("QB_ATTEMPT_START", visitor, body("publicationId", publicationId)).expectStatus().isForbidden();
        call("QB_VIEW", visitor, body("publicationId", publicationId)).expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.app.feed").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, visitor).bodyValue(Map.of("params", Map.of("at",
                clock.instant().toString()))).exchange().expectStatus().isForbidden();
    }

    @Test
    void theFirstViewIsRecordedOnceForWhatTheTakerCanSee() {
        String sponsor = newSponsor("s-view");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Viewed");
        assertThat(run("QB_VIEW", taker(u1()), body("publicationId", publicationId)))
            .containsEntry("firstView", true);
        assertThat(run("QB_VIEW", taker(u1()), body("publicationId", publicationId)))
            .containsEntry("firstView", false);
        assertThat(query("SELECT count(*) AS n FROM qb_publication_view WHERE publication_id = ?::uuid",
            publicationId).getFirst()).containsEntry("n", 1L);
        // Paused, it is not shown: unless the caller has an attempt of it, as the detail page.
        String paused = livePublication(sponsor, "Viewed paused", Map.of("regions", List.of("GLOBAL")));
        start(taker(u2()), paused);
        run("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", paused));
        assertThat(run("QB_VIEW", taker(u2()), body("publicationId", paused))).containsEntry("firstView", true);
        refused("QB_VIEW", taker(u1()), body("publicationId", paused), 404);
        // Not aimed at U2's region; and nothing that never went on.
        refused("QB_VIEW", taker(u2()), body("publicationId", publicationId), 404);
        refused("QB_VIEW", taker(u1()), body("publicationId", UUID.randomUUID().toString()), 404);
    }

    @Test
    void theSystemsProcessesAndTheCreditAreNobodys() {
        String sponsor = newSponsor("s-system");
        String id = UUID.randomUUID().toString();
        for (String token : List.of(taker(u1()), sponsorToken(sponsor),
            tokenOf("content", QbRoles.ADMIN_CONTENT), tokenOf("finance", QbRoles.ADMIN_FINANCE),
            tokenOf("super", QbRoles.ADMIN_SUPER))) {
            call("QB_ATTEMPT_EXPIRE", token, body("attemptId", id)).expectStatus().isForbidden();
            call("QB_PENDING_SETTLE", token, body("sponsorId", sponsor)).expectStatus().isForbidden();
            call("QB_PENDING_PAY", token, body("sponsorId", sponsor, "attemptId", id)).expectStatus().isForbidden();
            call("QB_PUBLICATION_FUNDS_RESTORED", token, body("sponsorId", sponsor)).expectStatus().isForbidden();
            call("QB_PUBLICATION_FUNDED", token, body("publicationId", id)).expectStatus().isForbidden();
            call("QB_PUBLICATION_STATS_REFRESH", token, body("scheduledTime", clock.instant().toString()))
                .expectStatus().isForbidden();
            call("QB_WALLET_DEV_CREDIT", token, body("sponsorId", sponsor, "amount", 1_000_000))
                .expectStatus().isForbidden();
        }
        assertThat(wallet(sponsor)).isNull();
    }

    @Test
    void noDatasetWritesAnsweringsTables() {
        String sponsor = newSponsor("s-datasets");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Datasets", Map.of("regions", List.of("GLOBAL")));
        String attemptId = (String) start(taker(u1()), publicationId).get("attemptId");
        Map<String, Object> attempt = attempt(attemptId);
        Map<String, Object> wallet = wallet(sponsor);
        Map<String, Object> card = card(publicationId);
        Map<String, Object> writes = Map.of(
            QbPlay.defaultDataset(QbPlay.ATTEMPT), change(attemptId, attempt.get("version"), Map.of("answers",
                "[[1],[1]]")),
            QbPlay.TAKER_ATTEMPTS, change(attemptId, attempt.get("version"), Map.of("currentIndex", 1)),
            QbWallets.defaultDataset(QbWallets.WALLET), change(wallet.get("wallet_id"), wallet.get("version"),
                Map.of("balance", 1_000_000)),
            QbPublications.defaultDataset(QbPublications.CARD), change(publicationId, card.get("version"),
                Map.of("status", "ENDED")));
        for (String token : List.of(taker(u1()), admin())) {
            writes.forEach((dataset, change) -> {
                int status = client.post().uri("/api/datasets/" + dataset + "/commit")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                    .bodyValue(Map.of("changes", List.of(change))).exchange().expectBody(MAP).returnResult()
                    .getStatus().value();
                assertThat(status).as(dataset).isBetween(400, 499);
            });
        }
        assertThat(attempt(attemptId)).containsEntry("answers", "[[],[]]");
        assertThat(balance(sponsor)).isEqualByComparingTo("1000");
        assertThat(card(publicationId)).containsEntry("status", "LIVE");
    }

    @Test
    void administratorsReadEverything() {
        String sponsor = newSponsor("s-admin-read");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Admin read", Map.of("regions", List.of("GLOBAL")));
        play(u1(), publicationId, true, true);
        String content = tokenOf("content", QbRoles.ADMIN_CONTENT);
        String finance = tokenOf("finance", QbRoles.ADMIN_FINANCE);
        assertThat(rows(QbPlay.defaultDataset(QbPlay.ATTEMPT), content)).isNotEmpty();
        assertThat(rows(QbWallets.defaultDataset(QbWallets.WALLET), finance)).isNotEmpty();
        assertThat(rows(QbPublications.defaultDataset(QbPublications.CARD), content)).isNotEmpty();
        // Takers and sponsors do not.
        queryDataset(QbPlay.defaultDataset(QbPlay.ATTEMPT), taker(u1())).expectStatus().isForbidden();
        queryDataset(QbWallets.defaultDataset(QbWallets.WALLET), sponsorToken(sponsor)).expectStatus().isForbidden();
        queryDataset(QbWallets.defaultDataset(QbWallets.WALLET), content).expectStatus().isForbidden();
    }

    static Map<String, Object> change(Object id, Object version, Map<String, Object> attributes) {
        return Map.of("action", "UPDATE", "id", String.valueOf(id), "version", ((Number) version).longValue(),
            "attributes", attributes);
    }
}
