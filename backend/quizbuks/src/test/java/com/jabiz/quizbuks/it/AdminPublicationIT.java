package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Administrators take publications down (Q10b, review M2): pause, resume and end with a reason, a second factor and
 * {@code qb.admin.content.write}; a sponsor cannot resume what an administrator paused; banning a sponsor pauses their
 * running publications in the same transaction; the processes take the publication's locks in order, so completions
 * running at the same time all finish.
 */
class AdminPublicationIT extends PlayItSupport {

    private String contentAdmin2() {
        return TestTokens.bearer(tokens, "content-admin-m2", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    private Map<String, Object> publication(String publicationId) {
        return latest("qb_publication_version", "publication_id", "publication_id = '" + publicationId + "'")
            .getFirst();
    }

    @Test
    void onlyAContentAdministratorWithASecondFactorAndAReason() {
        String sponsor = newSponsor("s-m2-access");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "M2 access");
        Map<String, Object> input = body("publicationId", publicationId, "reason", "Misleading");
        refused(PublicationAdmin.PAUSE, sponsorToken(sponsor), input, 403);
        refused(PublicationAdmin.PAUSE, taker(u1()), input, 403);
        refused(PublicationAdmin.PAUSE, tokenOf("finance-m2", QbRoles.ADMIN_FINANCE), input, 403);
        // A second factor always, whatever the administration's setting says.
        refused(PublicationAdmin.PAUSE, TestTokens.withoutMfa(tokens, "content-admin-m2",
            QbPermissions.ADMIN_CONTENT_WRITE), input, 403);
        refused(PublicationAdmin.PAUSE, TestTokens.unverified(tokens, "content-admin-m2",
            QbPermissions.ADMIN_CONTENT_WRITE), input, 403);
        refused(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", publicationId), 400);
        refused(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", publicationId, "reason", " "), 400);
        assertThat(publication(publicationId)).containsEntry("status", "LIVE");
        // The roles: content and super administrators hold it.
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions()).contains(QbPermissions.ADMIN_CONTENT_WRITE);
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions()).contains(QbPermissions.ADMIN_CONTENT_WRITE);
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).doesNotContain(QbPermissions.ADMIN_CONTENT_WRITE);
    }

    @Test
    void pauseResumeAndEnd() {
        String sponsor = newSponsor("s-m2-life");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "M2 life");
        String sponsorToken = sponsorToken(sponsor);

        Map<String, Object> paused = run(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", publicationId,
            "reason", "Reported as misleading"));
        assertThat(paused).containsEntry("status", "PAUSED");
        assertThat(publication(publicationId)).containsEntry("paused_by", "ADMIN")
            .containsEntry("admin_reason", "Reported as misleading");
        assertThat(card(publicationId)).containsEntry("status", "PAUSED");
        // No new attempt; the sponsor reads why, and cannot resume it.
        assertThat(codes(refused("QB_ATTEMPT_START", taker(u1()), body("publicationId", publicationId), 422)))
            .containsExactly(PlayCodes.NOT_OPEN);
        assertThat(rows(com.jabiz.quizbuks.publishing.QbPublications.sponsorDataset(
            com.jabiz.quizbuks.publishing.QbPublications.PUBLICATION), sponsorToken))
            .filteredOn(row -> publicationId.equals(row.get("publicationId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("pausedBy", "ADMIN")
                .containsEntry("adminReason", "Reported as misleading"));
        assertThat(codes(refused("QB_PUBLICATION_RESUME", sponsorToken, body("publicationId", publicationId), 422)))
            .containsExactly(PublishingCodes.ADMIN_PAUSED);
        // Paused again: nothing changes.
        assertThat(run(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", publicationId, "reason", "Again")))
            .containsEntry("revision", paused.get("revision"));

        Map<String, Object> resumed = run(PublicationAdmin.RESUME, contentAdmin2(), body("publicationId", publicationId,
            "reason", "Checked: fine"));
        assertThat(resumed).containsEntry("status", "LIVE");
        assertThat(publication(publicationId)).containsEntry("paused_by", null).containsEntry("paused_at", null);
        assertThat(codes(refused(PublicationAdmin.RESUME, contentAdmin2(), body("publicationId", publicationId,
            "reason", "Twice"), 422))).containsExactly("ILLEGAL_TRANSITION");

        // Paused by its sponsor: an administrator's pause keeps it paused as theirs; the admin resume is for those.
        run("QB_PUBLICATION_PAUSE", sponsorToken, body("publicationId", publicationId));
        assertThat(publication(publicationId)).containsEntry("paused_by", "SPONSOR");
        assertThat(codes(refused(PublicationAdmin.RESUME, contentAdmin2(), body("publicationId", publicationId,
            "reason", "Not mine"), 422))).containsExactly(PublishingCodes.NOT_ADMIN_PAUSED);
        run(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", publicationId, "reason", "Under review"));
        assertThat(publication(publicationId)).containsEntry("status", "PAUSED").containsEntry("paused_by", "ADMIN");
        assertThat(codes(refused("QB_PUBLICATION_RESUME", sponsorToken, body("publicationId", publicationId), 422)))
            .containsExactly(PublishingCodes.ADMIN_PAUSED);

        Map<String, Object> ended = run(PublicationAdmin.END, contentAdmin2(), body("publicationId", publicationId,
            "reason", "Fraudulent"));
        assertThat(ended).containsEntry("status", "ENDED");
        assertThat(publication(publicationId)).containsEntry("end_reason", "ADMIN")
            .containsEntry("admin_reason", "Fraudulent");
        assertThat(card(publicationId)).containsEntry("status", "ENDED");
        assertThat(query("SELECT next_due_at FROM qb_publication_schedule WHERE publication_id = ?::uuid",
            publicationId).getFirst()).containsEntry("next_due_at", null);
        assertThat(codes(refused(PublicationAdmin.END, contentAdmin2(), body("publicationId", publicationId,
            "reason", "Again"), 422))).containsExactly("ILLEGAL_TRANSITION");
        refused(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", java.util.UUID.randomUUID().toString(),
            "reason", "Ghost"), 404);
    }

    @Test
    void banningASponsorPausesTheirRunningPublications() {
        String sponsor = newSponsor("s-m2-ban");
        credit(sponsor, 1_000);
        String live = livePublication(sponsor, "M2 ban live");
        String byThemselves = livePublication(sponsor, "M2 ban paused");
        run("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", byThemselves));
        String other = livePublication(sponsorB(), "M2 ban other");
        String boss = userWithRole("m2-boss-" + java.util.UUID.randomUUID(), QbRoles.ADMIN_SUPER);
        String bossToken = tokenOf(boss, QbRoles.ADMIN_SUPER);

        run("QB_USER_BAN", bossToken, body("userId", sponsor, "reason", "Fraud"));
        for (String publicationId : List.of(live, byThemselves)) {
            assertThat(publication(publicationId)).as(publicationId).containsEntry("status", "PAUSED")
                .containsEntry("paused_by", "ADMIN").containsEntry("admin_reason", "Fraud");
            assertThat(card(publicationId)).containsEntry("status", "PAUSED");
        }
        assertThat(publication(other)).containsEntry("status", "LIVE");
        // The pauses ran inside the ban: one root process, with the pauses as its sub-processes.
        assertThat(query("SELECT count(*) AS n FROM op_process c JOIN op_process p ON p.process_seq_id = "
            + "c.parent_seq_id WHERE p.process_name = 'QB_USER_BAN' AND c.process_name = ?", PublicationAdmin.PAUSE)
            .getFirst().get("n")).isEqualTo(2L);

        // Lifted, the ban leaves them paused: only an administrator resumes them.
        run("QB_USER_UNBAN", bossToken, body("userId", sponsor));
        assertThat(codes(refused("QB_PUBLICATION_RESUME", sponsorToken(sponsor), body("publicationId", live), 422)))
            .containsExactly(PublishingCodes.ADMIN_PAUSED);
        assertThat(run(PublicationAdmin.RESUME, contentAdmin2(), body("publicationId", live, "reason", "Cleared")))
            .containsEntry("status", "LIVE");
    }

    @Test
    void completionsRunningAlongsideAPauseAllFinish() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-m2-race");
        credit(sponsor, 10_000);
        String publicationId = livePublication(sponsor, "M2 race", Map.of("regions", List.of("GLOBAL")));
        List<String> tokens = new ArrayList<>();
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String token = taker(newTaker("t-m2-race-" + java.util.UUID.randomUUID(), null));
            tokens.add(token);
            attempts.add((String) start(token, publicationId).get("attemptId"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(7);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < attempts.size(); i++) {
                String token = tokens.get(i);
                Map<String, Object> input = submission(attempts.get(i), "answers", answers(true, true));
                calls.add(() -> post("QB_ATTEMPT_SUBMIT", token, input));
            }
            calls.add(() -> post(PublicationAdmin.PAUSE, contentAdmin2(), body("publicationId", publicationId,
                "reason", "Race")));
            for (Future<Integer> status : pool.invokeAll(calls)) {
                assertThat(status.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(publication(publicationId)).containsEntry("status", "PAUSED");
        Map<String, Object> counter = counter(publicationId);
        assertThat(counter.get("completions")).isEqualTo(new BigDecimal("6"));
        assertThat(counter.get("in_progress")).isEqualTo(BigDecimal.ZERO);
        assertThat(counter.get("reserved")).isEqualTo(BigDecimal.ZERO);
        assertBooksBalance();
    }

    private int post(String process, String token, Map<String, Object> body) {
        return client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
            .bodyValue(body).exchange().expectBody(MAP).returnResult().getStatus().value();
    }
}
