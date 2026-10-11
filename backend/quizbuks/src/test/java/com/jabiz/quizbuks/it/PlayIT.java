package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.PlayLimits;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starting, continuing, saving and submitting an attempt (U-23, U-30 – U-34, U-41, 5.3-5, 5.3-6;
 * docs/quizbuks/plans/Q5-answering.md, D-Q5-1, D-Q5-2).
 */
class PlayIT extends PlayItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void startSaveContinueAndSubmit() {
        String publicationId = livePublication(sponsorA(), "Full round");
        credit(sponsorA(), 1_000);
        String token = taker(u1());
        Map<String, Object> started = start(token, publicationId);
        assertThat(started).containsEntry("status", "IN_PROGRESS").containsEntry("resumed", false)
            .containsEntry("currentIndex", 0).containsEntry("timeLimitSec", 300);
        assertThat(Instant.parse((String) started.get("deadline")))
            .isEqualTo(Instant.parse((String) started.get("startedAt")).plusSeconds(300));
        List<Map<String, Object>> questions = (List<Map<String, Object>>) started.get("questions");
        assertThat(questions).extracting(q -> q.get("no")).containsExactly(1, 2);
        assertThat(questions).allSatisfy(q -> assertThat((List<Map<String, Object>>) q.get("options"))
            .allSatisfy(o -> assertThat(o).containsOnlyKeys("no", "text", "image")));
        String attemptId = (String) started.get("attemptId");

        // A save, then the attempt is continued with the same answers and question.
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> saved = run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "currentIndex", 1,
            "answers", List.of(body("questionNo", 1, "selected", List.of(1)))));
        assertThat(saved).containsEntry("currentIndex", 1);
        Map<String, Object> again = start(token, publicationId);
        assertThat(again).containsEntry("attemptId", attemptId).containsEntry("resumed", true)
            .containsEntry("currentIndex", 1).containsEntry("deadline", started.get("deadline"));
        assertThat((List<Object>) again.get("answers")).containsExactly(Map.of("questionNo", 1, "selected",
            List.of(1)));
        assertThat(again.get("questions")).isEqualTo(started.get("questions"));
        assertThat(counter(publicationId).get("starts")).isEqualTo(new java.math.BigDecimal("1"));

        // Submit with the second answer: full marks, the top tier, paid.
        Map<String, Object> result = submit(token, attemptId, List.of(body("questionNo", 2, "selected",
            List.of(1))));
        assertThat(result).containsEntry("status", "SUBMITTED").containsEntry("score", 5)
            .containsEntry("fullScore", 5).containsEntry("passScore", 3).containsEntry("passed", true)
            .containsEntry("rewardStatus", "PAID").containsEntry("message", null);
        assertThat(((Number) result.get("reward")).intValue()).isEqualTo(100);
        assertThat((List<Map<String, Object>>) result.get("questions")).containsExactly(
            Map.of("no", 1, "selected", List.of(1), "correct", List.of(1), "points", 2, "earned", 2),
            Map.of("no", 2, "selected", List.of(1), "correct", List.of(1), "points", 3, "earned", 3));

        // One attempt each (5.3-6): started again it is refused, saved again too.
        assertThat(codes(refused("QB_ATTEMPT_START", token, body("publicationId", publicationId), 422)))
            .containsExactly(PlayCodes.ALREADY_TAKEN);
        assertThat(codes(refused("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "currentIndex", 0), 422)))
            .containsExactly(PlayCodes.NOT_IN_PROGRESS);
        assertThat(attempt(attemptId)).containsEntry("status", "SUBMITTED").containsEntry("reward_status", "PAID");
    }

    @Test
    void aRepeatedSubmissionPaysOnce() {
        credit(sponsorA(), 1_000);
        String token = taker(u2());
        // U2 is in the United States: a publication for everyone.
        String global = livePublication(sponsorA(), "Everyone", Map.of("regions", List.of("GLOBAL")));
        String attemptId = (String) start(token, global).get("attemptId");
        Map<String, Object> input = body("attemptId", attemptId, "answers", answers(true, true));
        Map<String, Object> first = runWithKey("QB_ATTEMPT_SUBMIT", token, input, "submit-" + attemptId);
        Map<String, Object> replay = runWithKey("QB_ATTEMPT_SUBMIT", token, input, "submit-" + attemptId);
        assertThat(replay.get("output")).isEqualTo(first.get("output"));
        assertThat(codes(refused("QB_ATTEMPT_SUBMIT", token, input, 422))).containsExactly(PlayCodes.FINISHED);
        assertThat(postingOf(attemptId).stream().map(row -> row.get("transaction_id")).distinct()).hasSize(1);
        assertThat(counter(global).get("completions")).isEqualTo(java.math.BigDecimal.ONE);
        assertBooksBalance();
    }

    @Test
    void aSubmissionWithinTheGraceCountsAndOneAfterItIsRefusedWithoutItsAnswers() {
        String publicationId = livePublication(sponsorA(), "Grace", Map.of("timeLimitSec", 60));
        credit(sponsorA(), 1_000);
        String token = taker(u1());
        String late = user("u-late");
        profile(late, "JP");
        String inTime = (String) start(token, publicationId).get("attemptId");
        String lateAttempt = (String) start(taker(late), publicationId).get("attemptId");
        run("QB_ATTEMPT_SAVE", taker(late), body("attemptId", lateAttempt, "answers",
            List.of(body("questionNo", 1, "selected", List.of(1)))));

        clock.advance(Duration.ofSeconds(60).plus(PlayLimits.SUBMIT_GRACE));
        // Saving is over at the deadline; submitting within the grace still counts.
        assertThat(codes(refused("QB_ATTEMPT_SAVE", token, body("attemptId", inTime, "currentIndex", 1), 422)))
            .containsExactly(PlayCodes.EXPIRED);
        assertThat(submit(token, inTime, answers(true, false))).containsEntry("score", 2);

        clock.advance(Duration.ofMillis(1));
        Map<String, Object> refused = refused("QB_ATTEMPT_SUBMIT", taker(late), body("attemptId", lateAttempt,
            "answers", answers(true, true)), 422);
        assertThat(codes(refused)).containsExactly(PlayCodes.EXPIRED);
        assertThat(attempt(lateAttempt)).containsEntry("status", "IN_PROGRESS")
            .containsEntry("answers", "[[1],[]]");
        // Continuing it is refused too; the system finishes it with what was saved.
        assertThat(codes(refused("QB_ATTEMPT_START", taker(late), body("publicationId", publicationId), 422)))
            .containsExactly(PlayCodes.EXPIRED);
        Map<String, Object> expired = run("QB_ATTEMPT_EXPIRE", system(), body("attemptId", lateAttempt));
        assertThat(expired).containsEntry("status", "EXPIRED").containsEntry("finished", true);
        assertThat(attempt(lateAttempt)).containsEntry("status", "EXPIRED").containsEntry("score",
            new java.math.BigDecimal("2"));
        // Once more changes nothing.
        assertThat(run("QB_ATTEMPT_EXPIRE", system(), body("attemptId", lateAttempt)))
            .containsEntry("status", "EXPIRED").containsEntry("finished", false);
        assertBooksBalance();
    }

    @Test
    void anAttemptNotDueYetIsNotExpired() {
        String publicationId = livePublication(sponsorA(), "Not due");
        credit(sponsorA(), 1_000);
        String attemptId = (String) start(taker(u1()), publicationId).get("attemptId");
        assertThat(run("QB_ATTEMPT_EXPIRE", system(), body("attemptId", attemptId)))
            .containsEntry("status", "IN_PROGRESS").containsEntry("finished", false);
        assertThat(run("QB_ATTEMPT_EXPIRE", system(), body("attemptId", "no-such-attempt")))
            .containsEntry("status", null).containsEntry("finished", false);
    }

    @Test
    void invalidAnswersAreReportedAllAtOnce() {
        String publicationId = livePublication(sponsorA(), "Invalid answers");
        credit(sponsorA(), 1_000);
        String token = taker(u1());
        String attemptId = (String) start(token, publicationId).get("attemptId");
        Map<String, Object> problem = refused("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId,
            "currentIndex", 2, "answers", List.of(body("questionNo", 3, "selected", List.of(1)),
                body("questionNo", 1, "selected", List.of(1, 5)))), 422);
        assertThat(violations(problem)).extracting(v -> v.get("field")).containsExactly("currentIndex",
            "answers[0].questionNo", "answers[1].selected[1]");
        assertThat(codes(problem)).containsOnly(PlayCodes.ANSWER_INVALID);
        assertThat(codes(refused("QB_ATTEMPT_SUBMIT", token, body("attemptId", attemptId, "answers",
            List.of(body("questionNo", 2, "selected", List.of(9)))), 422))).containsExactly(PlayCodes.ANSWER_INVALID);
        assertThat(attempt(attemptId)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void eachRefusalOfAStartHasItsCode() {
        credit(sponsorA(), 10_000);
        String open = livePublication(sponsorA(), "Refusals");
        // Paused.
        run("QB_PUBLICATION_PAUSE", sponsorToken(sponsorA()), body("publicationId", open));
        assertThat(codes(refused("QB_ATTEMPT_START", taker(u1()), body("publicationId", open), 422)))
            .containsExactly(PlayCodes.NOT_OPEN);
        run("QB_PUBLICATION_RESUME", sponsorToken(sponsorA()), body("publicationId", open));
        // Not started yet.
        Instant now = clock.instant();
        String later = livePublication(sponsorA(), "Later", Map.of("startAt", now.plus(Duration.ofDays(1))
            .toString(), "endAt", now.plus(Duration.ofDays(3)).toString()));
        assertThat(codes(refused("QB_ATTEMPT_START", taker(u1()), body("publicationId", later), 422)))
            .containsExactly(PlayCodes.NOT_OPEN);
        // Another region.
        assertThat(codes(refused("QB_ATTEMPT_START", taker(u2()), body("publicationId", open), 422)))
            .containsExactly(PlayCodes.NOT_VISIBLE);
        // One's own.
        String own = livePublication(sponsorB(), "Own", Map.of("regions", List.of("GLOBAL")));
        credit(sponsorB(), 1_000);
        assertThat(codes(refused("QB_ATTEMPT_START", sponsorToken(sponsorB()), body("publicationId", own), 422)))
            .containsExactly(PlayCodes.OWN_PUBLICATION);
        // A sponsor may answer another sponsor's quiz (G1).
        profile(sponsorB(), "JP");
        assertThat(start(sponsorToken(sponsorB()), open)).containsEntry("status", "IN_PROGRESS");
        // Ended.
        clock.advance(Duration.ofDays(8));
        assertThat(codes(refused("QB_ATTEMPT_START", taker(u1()), body("publicationId", open), 422)))
            .containsExactly(PlayCodes.NOT_OPEN);
        // A draft or no publication at all: 404.
        refused("QB_ATTEMPT_START", taker(u1()), body("publicationId", java.util.UUID.randomUUID().toString()), 404);
    }

    @Test
    void anUnverifiedOrBannedTakerCannotAnswer() {
        String publicationId = livePublication(sponsorA(), "Verified only", Map.of("regions", List.of("GLOBAL")));
        credit(sponsorA(), 1_000);
        String unverified = unverifiedTokenOf(u3(), QbRoles.TAKER);
        assertThat(refused("QB_ATTEMPT_START", unverified, body("publicationId", publicationId), 403).toString())
            .contains("EMAIL_NOT_VERIFIED");
        String banned = user("u-banned");
        profile(banned, "JP");
        run("QB_USER_BAN", tokenOf(user("q-admin"), QbRoles.ADMIN_SUPER), body("userId", banned, "reason",
            "Cheating"));
        assertThat(codes(refused("QB_ATTEMPT_START", taker(banned), body("publicationId", publicationId), 422)))
            .containsExactly("QB_USER_BANNED");
    }

    @Test
    void twoStartsAtOnceMakeOneAttempt() throws Exception {
        warmUp();
        String publicationId = livePublication(sponsorA(), "Concurrent starts");
        credit(sponsorA(), 1_000);
        String userId = user("u-twice");
        profile(userId, "JP");
        String token = taker(userId);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> client.post().uri("/api/processes/QB_ATTEMPT_START/latest")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                    .bodyValue(body("publicationId", publicationId)).exchange().expectBody(MAP).returnResult()
                    .getResponseBody());
            }
            List<Object> ids = new ArrayList<>();
            for (Future<Map<String, Object>> result : pool.invokeAll(calls)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) result.get().get("output");
                assertThat(output).as(String.valueOf(result.get())).isNotNull();
                ids.add(output.get("attemptId"));
            }
            assertThat(ids).hasSize(4).containsOnly(ids.getFirst());
        } finally {
            pool.shutdown();
        }
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE user_id = ?", userId).getFirst())
            .containsEntry("n", 1L);
        assertThat(counter(publicationId).get("starts")).isEqualTo(java.math.BigDecimal.ONE);
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).contains(QbPermissions.PLAY);
    }
}
