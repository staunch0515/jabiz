package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.quizbuks.publishing.PublicationOutput;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After an administrator takes a publication down (Q10b, review M-d): it is not copied, and every later publication of
 * its quiz goes to a person's review, whatever the review switch or the approval rules say; the reviewer reads the
 * takedown and its reason ({@code qb.admin.quiz-takedowns}). And the ban's side (reviews L1, L2, L3, L9): the pauses
 * record a fixed reason, leave a publication that no longer runs as it is, an administrator does not resume a banned
 * sponsor's publication, and a ban and an approval take turns.
 */
class TakedownIT extends PlayItSupport {

    /** A test-only root that runs the ban's pause as its sub-process: as the ban would, for any publication. */
    static final String BAN_PAUSE_RELAY = "IT_QB_BAN_PAUSE_RELAY";

    @TestConfiguration
    static class Processes {

        @Bean
        ProcessDefinition<PublicationAdmin.BanPauseInput, PublicationOutput, ProcessContext> itBanPauseRelay() {
            return ProcessDefinition.define(BAN_PAUSE_RELAY, 1, PublicationAdmin.BanPauseInput.class,
                PublicationOutput.class, ProcessContext.class, pb -> pb
                    .permissions(QbPermissions.ADMIN_USERS_BAN)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("out", PublicationOutput.class))
                    .step("Pause", CallProcess.<ProcessContext>of(PublicationAdmin.BAN_PAUSE, 1,
                        ctx -> ctx.get("in"), "out")));
        }
    }

    private String takedownAdmin() {
        return TestTokens.bearer(tokens, "content-admin-takedown", QbPermissions.ADMIN_CONTENT_WRITE,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private Map<String, Object> publication(String publicationId) {
        return latest("qb_publication_version", "publication_id", "publication_id = '" + publicationId + "'")
            .getFirst();
    }

    private String versionOf(String publicationId) {
        return String.valueOf(publication(publicationId).get("version_id"));
    }

    private String boss() {
        return tokenOf(userWithRole("takedown-boss-" + UUID.randomUUID(), QbRoles.ADMIN_SUPER), QbRoles.ADMIN_SUPER);
    }

    @Test
    void aPublicationTakenDownIsNotCopied() {
        String sponsor = newSponsor("s-takedown-copy");
        credit(sponsor, 1_000);
        String token = sponsorToken(sponsor);
        String paused = livePublication(sponsor, "Takedown copy paused");
        String ended = livePublication(sponsor, "Takedown copy ended");
        String byThemselves = livePublication(sponsor, "Takedown copy own");
        run(PublicationAdmin.PAUSE, takedownAdmin(), body("publicationId", paused, "reason", "Misleading"));
        run(PublicationAdmin.END, takedownAdmin(), body("publicationId", ended, "reason", "Fraudulent"));
        run("QB_PUBLICATION_PAUSE", token, body("publicationId", byThemselves));
        for (String publicationId : List.of(paused, ended)) {
            Map<String, Object> refused = refused("QB_PUBLICATION_COPY", token, body("publicationId", publicationId),
                422);
            assertThat(codes(refused)).as(publicationId).containsExactly(PublishingCodes.TAKEN_DOWN);
            assertThat(String.valueOf(violations(refused).getFirst().get("message"))).contains("cannot be copied");
        }
        assertThat(query("SELECT count(*) AS n FROM qb_publication_version WHERE copied_from IN (?, ?)", paused,
            ended).getFirst()).containsEntry("n", 0L);
        // What the sponsor paused is copied as before.
        assertThat(run("QB_PUBLICATION_COPY", token, body("publicationId", byThemselves))).containsKey("publicationId");
        // Resumed by an administrator, it is no longer taken down.
        run(PublicationAdmin.RESUME, takedownAdmin(), body("publicationId", paused, "reason", "Checked"));
        assertThat(run("QB_PUBLICATION_COPY", token, body("publicationId", paused))).containsKey("publicationId");
    }

    @Test
    void laterPublicationsOfAQuizTakenDownAreReviewedByAPerson() {
        String sponsor = newSponsor("s-takedown-review");
        credit(sponsor, 1_000);
        String token = sponsorToken(sponsor);
        String first = livePublication(sponsor, "Takedown review");
        String versionId = versionOf(first);
        // Before the takedown the switch lets another publication of the quiz go on at once.
        assertThat(submit(token, draft(token, versionId, "Before"))).containsEntry("status", "SCHEDULED");

        run(PublicationAdmin.END, takedownAdmin(), body("publicationId", first, "reason", "Copied answers"));
        String next = draft(token, versionId, "After");
        Map<String, Object> submitted = submit(token, next);
        assertThat(submitted).containsEntry("status", "PENDING_REVIEW");
        assertThat(query("SELECT facts FROM sys_approval_evaluation WHERE entity_id = ? ORDER BY process_seq_id",
            next).getLast().get("facts").toString()).contains("\"takenDown\":true").contains("\"autoApprove\":false");
        // The reviewer reads the takedown and its reason.
        Map<String, Object> row = publication(next);
        assertThat(items("qb.admin.quiz-takedowns", takedownAdmin(), Map.of("params", Map.of("sponsorId",
            row.get("sponsor_id"), "quizId", String.valueOf(row.get("quiz_id")))))).singleElement()
            .satisfies(takedown -> assertThat(takedown).containsEntry("publicationId", first)
                .containsEntry("endReason", "ADMIN").containsEntry("adminReason", "Copied answers"));
        assertThat(items("qb.admin.quiz-takedowns", takedownAdmin(), Map.of("params", Map.of("sponsorId",
            row.get("sponsor_id"), "quizId", UUID.randomUUID().toString())))).isEmpty();
        // A person approves it: it goes on.
        decide((String) submitted.get("approvalRequestId"), "APPROVE", null);
        assertThat(publication(next)).containsEntry("status", "SCHEDULED");
        // Another quiz of the same sponsor is not affected.
        assertThat(submit(token, draft(token, version(token, "Takedown other quiz"), "Other")))
            .containsEntry("status", "SCHEDULED");
    }

    /**
     * Verification of review M-d: an administrator's pause stays a takedown when the publication later ends with its
     * time (the tick ends a paused publication, keeping {@code pausedBy}).
     */
    @Test
    void anAdminPauseThatRunsIntoItsEndStaysATakedown() {
        String sponsor = newSponsor("s-takedown-expiry");
        credit(sponsor, 1_000);
        String token = sponsorToken(sponsor);
        String first = livePublication(sponsor, "Takedown expiry");
        String versionId = versionOf(first);
        run(PublicationAdmin.PAUSE, takedownAdmin(), body("publicationId", first, "reason", "Fraudulent"));
        clock.set(clock.instant().plus(java.time.Duration.ofDays(8)));
        runTick();
        assertThat(publication(first)).containsEntry("status", "ENDED").containsEntry("end_reason", "EXPIRED")
            .containsEntry("paused_by", "ADMIN");
        token = sponsorToken(sponsor);
        assertThat(codes(refused("QB_PUBLICATION_COPY", token, body("publicationId", first), 422)))
            .containsExactly(PublishingCodes.TAKEN_DOWN);
        assertThat(submit(token, draft(token, versionId, "After expiry"))).containsEntry("status", "PENDING_REVIEW");
        Map<String, Object> row = publication(first);
        assertThat(items("qb.admin.quiz-takedowns", takedownAdmin(), Map.of("params", Map.of("sponsorId",
            row.get("sponsor_id"), "quizId", String.valueOf(row.get("quiz_id")))))).singleElement()
            .satisfies(takedown -> assertThat(takedown).containsEntry("publicationId", first)
                .containsEntry("pausedBy", "ADMIN").containsEntry("adminReason", "Fraudulent"));
    }

    /** The same for a ban's pause that a completion then ends (its last winner: QUOTA). */
    @Test
    void aBansPauseThatEndsByItsQuotaStaysATakedown() {
        String sponsor = newSponsor("s-takedown-quota");
        credit(sponsor, 1_000);
        String first = livePublication(sponsor, "Takedown quota", Map.of("maxWinners", 1,
            "regions", List.of("GLOBAL")));
        String versionId = versionOf(first);
        String takerToken = taker(newTaker("t-takedown-quota-" + UUID.randomUUID(), null));
        String attemptId = (String) start(takerToken, first).get("attemptId");
        String boss = boss();
        run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Internal"));
        assertThat(publication(first)).containsEntry("status", "PAUSED").containsEntry("paused_by", "ADMIN");
        // The attempt in progress completes and takes the last place: the publication ends by its quota.
        assertThat(submit(takerToken, attemptId, answers(true, true))).containsEntry("passed", true);
        assertThat(publication(first)).containsEntry("status", "ENDED").containsEntry("end_reason", "QUOTA")
            .containsEntry("paused_by", "ADMIN");
        run("QB_USER_UNBAN", boss, body("userId", sponsor));
        String token = sponsorToken(sponsor);
        assertThat(codes(refused("QB_PUBLICATION_COPY", token, body("publicationId", first), 422)))
            .containsExactly(PublishingCodes.TAKEN_DOWN);
        assertThat(submit(token, draft(token, versionId, "After the ban"))).containsEntry("status", "PENDING_REVIEW");
    }

    @Test
    void anExemptingRuleDoesNotLetAPublicationOfAQuizTakenDownThrough() {
        String sponsor = newSponsor("s-takedown-exempt");
        credit(sponsor, 1_000);
        String token = sponsorToken(sponsor);
        String first = livePublication(sponsor, "Takedown exempt");
        run(PublicationAdmin.PAUSE, takedownAdmin(), body("publicationId", first, "reason", "Reported"));
        // An administrator publishes a rule that would exempt such publications from review (four eyes).
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("ruleCode", "it.takedown.exempt");
        values.put("subject", PublicationApprovals.SUBJECT);
        // Only this sponsor's: the class's other tests share the rules.
        values.put("condition", Map.of("fact", "sponsorId", "op", "eq", "value", sponsor));
        values.put("levels", List.of());
        values.put("priority", 1);
        values.put("enabled", true);
        values.put("description", "Exempts what was taken down (test)");
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysApprovalRule",
            "values", values, "reason", "Test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));

        String next = draft(token, versionOf(first), "Exempt?");
        Map<String, Object> refused = refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", next), 422);
        assertThat(codes(refused)).containsExactly(PublishingCodes.REVIEW_REQUIRED);
        assertThat(publication(next)).containsEntry("status", "DRAFT");
        assertThat(query("SELECT 1 FROM qb_publication_card WHERE publication_id = ?::uuid", next)).isEmpty();
    }

    @Test
    void theBansPausesRecordAFixedReasonAndLeaveWhatNoLongerRuns() {
        String sponsor = newSponsor("s-takedown-ban");
        credit(sponsor, 1_000);
        String live = livePublication(sponsor, "Takedown ban live");
        String ended = livePublication(sponsor, "Takedown ban ended");
        run(PublicationAdmin.END, takedownAdmin(), body("publicationId", ended, "reason", "Gone"));
        String draft = draft(sponsorToken(sponsor), version(sponsorToken(sponsor), "Takedown ban draft"), "Draft");
        String boss = boss();

        run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Internal: chargeback ring #42"));
        assertThat(publication(live)).containsEntry("status", "PAUSED").containsEntry("paused_by", "ADMIN")
            .containsEntry("admin_reason", PublicationAdmin.ACCOUNT_SUSPENDED);

        // Review L2: a publication that ended, or is a draft, when its pause takes the lock is left as it is.
        for (String publicationId : List.of(ended, draft)) {
            Map<String, Object> before = publication(publicationId);
            Map<String, Object> output = run(BAN_PAUSE_RELAY, boss, body("publicationId", publicationId));
            assertThat(output).containsEntry("status", before.get("status"));
            assertThat(publication(publicationId)).containsEntry("version_no", before.get("version_no"))
                .containsEntry("admin_reason", before.get("admin_reason"));
        }
        // Not run directly.
        refusedAsInternal(PublicationAdmin.BAN_PAUSE, boss, body("publicationId", live));

        // Review L9: while the sponsor is banned an administrator does not resume it; after the unban they do.
        assertThat(codes(refused(PublicationAdmin.RESUME, takedownAdmin(), body("publicationId", live, "reason",
            "Too early"), 422))).containsExactly(PublishingCodes.SPONSOR_BANNED);
        run("QB_USER_UNBAN", boss, body("userId", sponsor));
        assertThat(run(PublicationAdmin.RESUME, takedownAdmin(), body("publicationId", live, "reason", "Cleared")))
            .containsEntry("status", "LIVE");
    }

    /**
     * Verification review: a ban and an administrator's resume take turns on the sponsor's account lock: either the
     * resume comes first and the ban pauses the publication again, or the ban comes first and the resume is refused.
     * Never live with its sponsor banned.
     */
    @Test
    void aBanAndAnAdminResumeTakeTurns() throws Exception {
        warmUp();
        for (int i = 0; i < 3; i++) {
            String sponsor = newSponsor("s-takedown-resume-race-" + i);
            credit(sponsor, 1_000);
            String publicationId = livePublication(sponsor, "Takedown resume race " + i);
            run(PublicationAdmin.PAUSE, takedownAdmin(), body("publicationId", publicationId, "reason", "Check"));
            String boss = boss();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Callable<Integer>> calls = new ArrayList<>();
                calls.add(() -> call(PublicationAdmin.RESUME, takedownAdmin(), body("publicationId", publicationId,
                    "reason", "Cleared")).expectBody(MAP).returnResult().getStatus().value());
                calls.add(() -> call("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Race"))
                    .expectBody(MAP).returnResult().getStatus().value());
                for (Future<Integer> done : pool.invokeAll(calls)) {
                    statuses.add(done.get());
                }
            } finally {
                pool.shutdown();
            }
            assertThat(statuses.get(1)).as("the ban").isEqualTo(200);
            Map<String, Object> row = publication(publicationId);
            assertThat(row).as("resume " + statuses.get(0)).containsEntry("status", "PAUSED")
                .containsEntry("paused_by", "ADMIN");
            if (statuses.get(0) == 200) {
                assertThat(row).containsEntry("admin_reason", PublicationAdmin.ACCOUNT_SUSPENDED);
            } else {
                assertThat(statuses.get(0)).isEqualTo(422);
            }
        }
    }

    /**
     * Review L1: a ban and the approval of one of the sponsor's publications take turns on the sponsor's account lock:
     * either the approval comes first and the ban pauses the publication, or the ban comes first and the approval
     * sends it back. Never on and running with its sponsor banned.
     */
    @Test
    void aBanAndAnApprovalTakeTurns() throws Exception {
        warmUp();
        autoApprove(false);
        try {
            for (int i = 0; i < 3; i++) {
                String sponsor = newSponsor("s-takedown-race-" + i);
                credit(sponsor, 1_000);
                String token = sponsorToken(sponsor);
                String publicationId = draft(token, version(token, "Takedown race " + i), "Race " + i);
                Map<String, Object> submitted = submit(token, publicationId);
                assertThat(submitted).containsEntry("status", "PENDING_REVIEW");
                run("APPROVAL_DECIDE", reviewer(), body("requestId", submitted.get("approvalRequestId"), "decision",
                    "APPROVE"));
                String boss = boss();
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    List<Callable<Object>> calls = new ArrayList<>();
                    calls.add(() -> {
                        deliverEvents();
                        return null;
                    });
                    calls.add(() -> run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Race")));
                    for (Future<Object> done : pool.invokeAll(calls)) {
                        done.get();
                    }
                } finally {
                    pool.shutdown();
                }
                deliverEvents();
                Map<String, Object> row = publication(publicationId);
                if ("DRAFT".equals(row.get("status"))) {
                    assertThat(row).containsEntry("reject_code", "QB_USER_BANNED");
                } else {
                    assertThat(row).as("approved before the ban").containsEntry("status", "PAUSED")
                        .containsEntry("paused_by", "ADMIN");
                }
            }
        } finally {
            autoApprove(true);
        }
    }
}
