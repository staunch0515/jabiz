package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Publishing a quiz (M-30 – M-34, M-36, P-09; docs/quizbuks/plans/Q4-publishing.md): drafts, submission with every
 * problem at once, review by a person, rejection with a reason, withdrawal, expiry during review, the frozen policy,
 * copies, the two templates and repeated submissions.
 */
class PublicationIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> problemCodes(Map<String, Object> output) {
        return ((List<Map<String, Object>>) output.get("problems")).stream().map(p -> p.get("code")).toList();
    }

    @Test
    void aDraftNeedsOnlyANameAndListsWhatSubmittingItWouldNeed() {
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", sponsorA(), body("name", "Only a name"));
        assertThat(saved).containsEntry("status", PublicationStatus.DRAFT).containsEntry("revision", 1);
        assertThat(problemCodes(saved)).containsExactly(PublishingCodes.NO_VERSION, PublishingCodes.FIELD_REQUIRED,
            PublishingCodes.FIELD_REQUIRED, PublishingCodes.FIELD_REQUIRED, PublishingCodes.FIELD_REQUIRED,
            PublishingCodes.FIELD_REQUIRED, PublishingCodes.TIER_NO_REWARD);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) saved.get("problems")).getFirst();
        assertThat(first).containsEntry("field", "versionId").containsEntry("message", "Choose a version of a quiz.");
        String id = (String) saved.get("publicationId");
        assertThat(row(id)).containsEntry("publisher_kind", "SPONSOR")
            .containsEntry("publisher_id", "sponsor-a").containsEntry("status", "DRAFT");

        // The whole policy: the time limit is the version's, the derived values follow the tiers.
        String versionId = version(sponsorA(), "Capitals");
        Instant now = clock.instant();
        Map<String, Object> full = complete(versionId, "Autumn quiz", now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        full.put("publicationId", id);
        full.put("baseRevision", 1);
        Map<String, Object> complete = run("QB_PUBLICATION_SAVE", sponsorA(), full);
        assertThat(complete).containsEntry("revision", 2).containsEntry("fullScore", 5)
            .containsEntry("maxReward", 100).containsEntry("maxSpend", 5000)
            .containsEntry("problems", List.of());
        assertThat((List<?>) complete.get("tierIds")).hasSize(3);
        assertThat(row(id)).containsEntry("regions", "JP,ASIA").containsEntry("version_label", "v1.0")
            .satisfies(r -> assertThat(r.get("time_limit_sec").toString()).isEqualTo("300"))
            .satisfies(r -> assertThat(r.get("tier_count").toString()).isEqualTo("3"));

        // Saving the same again (the tiers by their keys) writes nothing; a stale revision is refused.
        @SuppressWarnings("unchecked")
        List<Object> tierIds = (List<Object>) complete.get("tierIds");
        List<Object> same = new java.util.ArrayList<>();
        for (int i = 0; i < tierIds.size(); i++) {
            Map<String, Object> tier = new java.util.LinkedHashMap<>((Map<String, Object>) tiers().get(i));
            tier.put("tierId", tierIds.get(i));
            same.add(tier);
        }
        full.put("tiers", same);
        full.put("baseRevision", 2);
        long rows = count("qb_publication_version");
        assertThat(run("QB_PUBLICATION_SAVE", sponsorA(), full)).containsEntry("revision", 2);
        assertThat(count("qb_publication_version")).isEqualTo(rows);
        full.put("baseRevision", 1);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), full, 422)))
            .containsExactly(PublishingCodes.CHANGED);

        // Field rules (400) and the order of the times (422).
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", " "), 400)))
            .containsExactly(PublishingCodes.NAME_BLANK);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "timeLimitSec", 10), 400)))
            .containsExactly(PublishingCodes.TIME_LIMIT_RANGE);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "budgetCap", 0), 400)))
            .containsExactly(PublishingCodes.BUDGET_RANGE);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "tiers",
            List.of(body("fromScore", 0, "toScore", 1, "reward", -1))), 400)))
            .containsExactly(PublishingCodes.TIER_REWARD_RANGE);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "regions",
            List.of("MARS")), 422))).containsExactly(PublishingCodes.REGIONS_FORMAT);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "startAt",
            now.plusSeconds(60).toString(), "endAt", now.toString()), 422)))
            .containsExactly(PublishingCodes.TIME_ORDER);
        refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "tiers", java.util.Collections.nCopies(21,
            body("fromScore", 0, "toScore", 1, "reward", 1))), 400);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("publicationId", id, "name", "x",
            "tiers", List.of(body("tierId", UUID.randomUUID(), "fromScore", 0, "toScore", 5, "reward", 1))), 422)))
            .containsExactly(PublishingCodes.TIER_NOT_IN_PUBLICATION);
    }

    @Test
    void submittingAnIncompletePublicationReportsEveryProblemAtOnce() {
        String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), body("name", "Incomplete", "tiers",
            List.of(body("fromScore", 1, "toScore", 3, "reward", 0)))).get("publicationId");
        Map<String, Object> problem = refused("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId", id), 422);
        assertThat(violations(problem)).extracting(v -> v.get("field"), v -> v.get("ruleCode")).containsExactly(
            tuple("versionId", PublishingCodes.NO_VERSION),
            tuple("regions", PublishingCodes.FIELD_REQUIRED),
            tuple("startAt", PublishingCodes.FIELD_REQUIRED),
            tuple("endAt", PublishingCodes.FIELD_REQUIRED),
            tuple("timeLimitSec", PublishingCodes.FIELD_REQUIRED),
            tuple("passScore", PublishingCodes.FIELD_REQUIRED),
            tuple("tiers[0].fromScore", PublishingCodes.TIER_NOT_FROM_ZERO),
            tuple("tiers", PublishingCodes.TIER_NO_REWARD));
        assertThat(status(id)).isEqualTo(PublicationStatus.DRAFT);
    }

    @Test
    void aReviewerApprovesAndThePublicationGoesOnWithItsCounters() {
        String versionId = version(sponsorA(), "Reviewed");
        String later = draft(sponsorA(), versionId, "Starts tomorrow");
        Map<String, Object> submitted = submit(sponsorA(), later);
        assertThat(submitted).containsEntry("status", PublicationStatus.PENDING_REVIEW)
            .containsEntry("approval", "PENDING");
        String requestId = (String) submitted.get("approvalRequestId");
        assertThat(row(later)).containsEntry("approval_request_id", requestId)
            .satisfies(r -> assertThat(r.get("submitted_at")).isNotNull());
        // The review is a task of the content administrators, known by the publication's name.
        assertThat(tasks(reviewer())).anySatisfy(task -> assertThat(String.valueOf(task.get("title")))
            .contains("Starts tomorrow"));

        decide(requestId, "APPROVE", null);
        assertThat(row(later)).containsEntry("status", PublicationStatus.SCHEDULED)
            .satisfies(r -> assertThat(r.get("approved_at")).isNotNull());
        // Both sides of the counters (Q10a4), inserted together with zeros.
        assertThat(query("SELECT completions, passes, winners, released, spent FROM qb_publication_counter "
            + "WHERE publication_id = ?", UUID.fromString(later))).singleElement().satisfies(counter ->
            assertThat(counter.values()).allSatisfy(value -> assertThat(value.toString()).isEqualTo("0")));
        assertThat(query("SELECT starts, reserved_total FROM qb_publication_start_counter "
            + "WHERE publication_id = ?", UUID.fromString(later))).singleElement().satisfies(counter ->
            assertThat(counter.values()).allSatisfy(value -> assertThat(value.toString()).isEqualTo("0")));

        // A publication whose start has passed goes live on approval.
        Instant now = clock.instant();
        String started = (String) run("QB_PUBLICATION_SAVE", sponsorA(), complete(versionId, "Started",
            now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(2)))).get("publicationId");
        decide((String) submit(sponsorA(), started).get("approvalRequestId"), "APPROVE", "Fine.");
        assertThat(status(started)).isEqualTo(PublicationStatus.LIVE);

        // Submitted, the policy is frozen: to change it, copy it.
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("publicationId", later, "name", "New"),
            422))).containsExactly(PublishingCodes.NOT_DRAFT);
        assertThat(codes(refused("QB_PUBLICATION_DELETE", sponsorA(), body("publicationId", later), 422)))
            .containsExactly(PublishingCodes.NOT_DRAFT);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId", later), 422)))
            .containsExactly(PublishingCodes.NOT_DRAFT);
    }

    @Test
    void aRejectionSendsThePublicationBackWithTheReasonAndAChangeMakesANewRequest() {
        String versionId = version(sponsorA(), "Rejected");
        String id = draft(sponsorA(), versionId, "Too generous");
        String first = (String) submit(sponsorA(), id).get("approvalRequestId");
        decide(first, "REJECT", "The rewards are too high.");
        assertThat(row(id)).containsEntry("status", PublicationStatus.DRAFT)
            .containsEntry("reject_reason", "The rewards are too high.").containsEntry("approval_request_id", null);
        assertThat(items("qb.sponsor.publications", sponsorA(), body("params", Map.of("q", "too generous"))))
            .singleElement().satisfies(item -> assertThat(item)
                .containsEntry("rejectReason", "The rewards are too high.").containsEntry("status", "DRAFT"));

        Map<String, Object> changed = complete(versionId, "Too generous", clock.instant().plus(Duration.ofDays(1)),
            clock.instant().plus(Duration.ofDays(8)));
        changed.put("publicationId", id);
        changed.put("tiers", List.of(body("fromScore", 0, "toScore", 4, "reward", 0),
            body("fromScore", 5, "toScore", 5, "reward", 20)));
        run("QB_PUBLICATION_SAVE", sponsorA(), changed);
        String second = (String) submit(sponsorA(), id).get("approvalRequestId");
        assertThat(second).isNotEqualTo(first);
        assertThat(row(id)).containsEntry("status", PublicationStatus.PENDING_REVIEW)
            .containsEntry("reject_reason", null);
        decide(second, "APPROVE", null);
        assertThat(status(id)).isEqualTo(PublicationStatus.SCHEDULED);
        assertThat(row(id).get("max_reward").toString()).isEqualTo("20");
    }

    @Test
    void aPublicationInReviewCanBeWithdrawn() {
        String id = draft(sponsorA(), version(sponsorA(), "Withdrawn"), "Second thoughts");
        String requestId = (String) submit(sponsorA(), id).get("approvalRequestId");
        assertThat(run("QB_PUBLICATION_WITHDRAW", sponsorA(), body("publicationId", id)))
            .containsEntry("status", PublicationStatus.DRAFT);
        assertThat(latest("sys_approval_request_version", "request_id", "request_id = '" + requestId + "'"))
            .singleElement().satisfies(request -> assertThat(request).containsEntry("status", "WITHDRAWN"));
        // Only a publication in review is withdrawn.
        assertThat(codes(refused("QB_PUBLICATION_WITHDRAW", sponsorA(), body("publicationId", id), 422)))
            .containsExactly("ILLEGAL_TRANSITION");
        // It can be submitted again: the same content, a new request.
        assertThat(submit(sponsorA(), id)).containsEntry("status", PublicationStatus.PENDING_REVIEW);
    }

    @Test
    void anApprovalAfterTheEndSendsThePublicationBack() {
        Instant now = clock.instant();
        String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), complete(version(sponsorA(), "Late"),
            "Short", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)))).get("publicationId");
        String requestId = (String) submit(sponsorA(), id).get("approvalRequestId");
        clock.advance(Duration.ofHours(3));
        decide(requestId, "APPROVE", null);
        assertThat(row(id)).containsEntry("status", PublicationStatus.DRAFT)
            .containsEntry("reject_code", PublishingCodes.EXPIRED_IN_REVIEW);
        assertThat(query("SELECT 1 FROM qb_publication_counter WHERE publication_id = ?", UUID.fromString(id)))
            .isEmpty();
        assertThat(query("SELECT 1 FROM qb_publication_start_counter WHERE publication_id = ?", UUID.fromString(id)))
            .isEmpty();
    }

    @Test
    void aPublicationStillInReviewAtItsEndGoesBackToItsDraft() {
        Instant now = clock.instant();
        String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), complete(version(sponsorA(), "Unreviewed"),
            "Never reviewed", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)))).get("publicationId");
        String requestId = (String) submit(sponsorA(), id).get("approvalRequestId");
        clock.advance(Duration.ofHours(2));
        assertThat(runTick()).isEqualTo(com.jabiz.runtime.job.JobRunner.Outcome.SUCCEEDED);
        assertThat(row(id)).containsEntry("status", PublicationStatus.DRAFT)
            .containsEntry("reject_code", PublishingCodes.EXPIRED_IN_REVIEW)
            .containsEntry("approval_request_id", null);
        assertThat(latest("sys_approval_request_version", "request_id", "request_id = '" + requestId + "'"))
            .singleElement().satisfies(request -> assertThat(request).containsEntry("status", "WITHDRAWN"));
        assertThat(tasks(reviewer())).noneSatisfy(task -> assertThat(String.valueOf(task.get("title")))
            .contains("Never reviewed"));
    }

    @Test
    void aCopyKeepsThePolicyAndMayTakeAnotherVersionOrOtherTimes() {
        String versionId = version(sponsorA(), "Copied");
        String source = draft(sponsorA(), versionId, "Original");
        decide((String) submit(sponsorA(), source).get("approvalRequestId"), "APPROVE", null);

        Map<String, Object> copy = run("QB_PUBLICATION_COPY", sponsorA(), body("publicationId", source));
        String copyId = (String) copy.get("publicationId");
        assertThat(copy).containsEntry("status", PublicationStatus.DRAFT).containsEntry("problems", List.of());
        Map<String, Object> original = row(source);
        assertThat(row(copyId)).containsEntry("name", "Original (copy)").containsEntry("copied_from",
            source).satisfies(r -> {
                for (String column : List.of("version_id", "regions", "start_at", "end_at", "time_limit_sec",
                    "pass_score", "budget_cap", "max_winners", "max_reward", "max_spend", "full_score")) {
                    assertThat(r.get(column)).as(column).isEqualTo(original.get(column));
                }
            });
        assertThat(latest("qb_reward_tier_version", "tier_id", "publication_id = '" + copyId + "' AND NOT is_deleted"))
            .extracting(t -> t.get("from_score").toString(), t -> t.get("to_score").toString(),
                t -> t.get("reward").toString())
            .containsExactlyInAnyOrder(tuple("0", "2", "0"), tuple("3", "4", "10"), tuple("5", "5", "100"));

        // Another version with another full score: the tiers no longer reach it, which the copy reports.
        String quizId = newQuiz(sponsorA(), "Longer");
        question(sponsorA(), quizId, "One");
        question(sponsorA(), quizId, "Two");
        String other = (String) run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId)).get("versionId");
        Instant start = clock.instant().plus(Duration.ofDays(10));
        Map<String, Object> moved = run("QB_PUBLICATION_COPY", sponsorA(), body("publicationId", source,
            "versionId", other, "startAt", start.toString(), "endAt", start.plus(Duration.ofDays(3)).toString(),
            "name", "Second run"));
        assertThat(moved).containsEntry("fullScore", 2);
        assertThat(problemCodes(moved)).contains(PublishingCodes.TIER_NOT_TO_FULL, PublishingCodes.PASS_SCORE_RANGE);
        assertThat(row((String) moved.get("publicationId"))).containsEntry("name", "Second run")
            .containsEntry("quiz_id", UUID.fromString(quizId));
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId",
            moved.get("publicationId")), 422))).contains(PublishingCodes.TIER_NOT_TO_FULL);

        // A quiz may be published many times, a version too.
        String again = draft(sponsorA(), versionId, "Original again");
        assertThat(submit(sponsorA(), again)).containsEntry("status", PublicationStatus.PENDING_REVIEW);
        // In the caller's language.
        Map<String, Object> zh = client.post().uri("/api/processes/QB_PUBLICATION_COPY/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header("Authorization", sponsorA()).header("Accept-Language", "zh")
            .bodyValue(body("publicationId", source)).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(row((String) ((Map<?, ?>) zh.get("output")).get("publicationId")))
            .containsEntry("name", "Original（副本）");
    }

    @Test
    void theTemplatesListPublicationsAndPublishedQuizzes() {
        String versionId = version(sponsorB(), "Listed quiz");
        String spring = draft(sponsorB(), versionId, "Spring 100% sale");
        draft(sponsorB(), versionId, "Summer");
        decide((String) submit(sponsorB(), spring).get("approvalRequestId"), "APPROVE", null);

        List<Map<String, Object>> all = items("qb.sponsor.publications", sponsorB(), body());
        assertThat(all).extracting(item -> item.get("name")).containsExactlyInAnyOrder("Spring 100% sale", "Summer");
        assertThat(all).filteredOn(item -> "Spring 100% sale".equals(item.get("name"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("quizTitle", "Listed quiz")
                .containsEntry("versionLabel", "v1.0").containsEntry("status", "SCHEDULED")
                .containsEntry("regions", "JP,ASIA").containsEntry("starts", 0).containsEntry("winners", 0)
                .containsEntry("maxWinners", 50).containsEntry("spent", 0).containsEntry("budgetCap", 10000)
                .containsEntry("maxSpend", 5000));
        assertThat(items("qb.sponsor.publications", sponsorB(), body("params", Map.of("q", "100%"))))
            .extracting(item -> item.get("name")).containsExactly("Spring 100% sale");
        assertThat(items("qb.sponsor.publications", sponsorB(), body("filters", List.of(Map.of("field", "status",
            "op", "eq", "value", "DRAFT"))))).extracting(item -> item.get("name")).containsExactly("Summer");

        assertThat(items("qb.sponsor.quizzes", sponsorB(), body())).filteredOn(item -> "Listed quiz"
            .equals(item.get("title"))).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("publicationCount", 1).containsEntry("published", true));
        String unpublished = newQuiz(sponsorB(), "Not published");
        assertThat(items("qb.sponsor.quizzes", sponsorB(), body("filters", List.of(Map.of("field", "published",
            "op", "eq", "value", false))))).extracting(item -> item.get("quizId")).contains(unpublished)
            .doesNotContain(row(spring).get("quiz_id").toString());

        // The administrators see every sponsor's.
        assertThat(items("qb.admin.publications", reviewer(), body("filters", List.of(Map.of("field", "publisherId",
            "op", "eq", "value", "sponsor-b"))))).hasSize(2).allSatisfy(item -> assertThat(item)
                .containsEntry("publisherKind", "SPONSOR").containsEntry("publisherId", "sponsor-b"));
    }

    @Test
    void aRepeatedSubmissionRunsOnce() {
        String id = draft(sponsorA(), version(sponsorA(), "Twice"), "Twice");
        String key = "submit-" + UUID.randomUUID();
        Map<String, Object> first = runWithKey("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId", id), key);
        Map<String, Object> second = runWithKey("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId", id), key);
        assertThat(second).isEqualTo(first);
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE idempotency_key = ?", key).getFirst())
            .containsEntry("n", 1L);
        assertThat(query("SELECT count(DISTINCT request_id) AS n FROM sys_approval_request_version "
            + "WHERE entity_id = ?", id).getFirst()).containsEntry("n", 1L);
    }

    @Test
    void aStaleRejectionIsIgnoredAndTheNewRequestStillCounts() {
        String id = draft(sponsorA(), version(sponsorA(), "Stale"), "Stale rejection");
        String first = (String) submit(sponsorA(), id).get("approvalRequestId");
        // Rejected, but the event is not delivered yet; meanwhile the sponsor withdraws and submits it unchanged.
        run("APPROVAL_DECIDE", reviewer(), body("requestId", first, "decision", "REJECT", "reason", "Old reason."));
        run("QB_PUBLICATION_WITHDRAW", sponsorA(), body("publicationId", id));
        String second = (String) submit(sponsorA(), id).get("approvalRequestId");
        assertThat(second).isNotEqualTo(first);
        deliverEvents();
        // The rejection was of the first request: the publication still waits for the second.
        assertThat(row(id)).containsEntry("status", PublicationStatus.PENDING_REVIEW)
            .containsEntry("approval_request_id", second).containsEntry("reject_reason", null);
        decide(second, "APPROVE", null);
        assertThat(status(id)).isEqualTo(PublicationStatus.SCHEDULED);
    }

    @Test
    void forgedOrForeignApprovalResultsChangeNothing() {
        String id = draft(sponsorA(), version(sponsorA(), "Forged"), "Forged");
        String requestId = (String) submit(sponsorA(), id).get("approvalRequestId");
        // An administrator holding every permission does not run it at all (internal(), platform D41).
        refusedAsInternal("QB_PUBLICATION_APPROVAL_RESULT", admin(), body("subject", "qb.publication",
            "entityId", id, "status", "REJECTED", "requestId", requestId, "preparerId", "sponsor-a",
            "reason", "Forged"));
        // Even run as the system, a forged event cannot reject without a rejected request.
        runAsSystem("QB_PUBLICATION_APPROVAL_RESULT", body("subject", "qb.publication", "entityId", id,
            "status", "REJECTED", "requestId", requestId, "preparerId", "sponsor-a", "reason", "Forged"));
        runAsSystem("QB_PUBLICATION_APPROVAL_RESULT", body("subject", "qb.publication", "entityId", id,
            "status", "REJECTED", "requestId", UUID.randomUUID().toString(), "reason", "Forged"));
        // An event of another subject is not about publications, whatever its entity.
        runAsSystem("QB_PUBLICATION_APPROVAL_RESULT", body("subject", "commerce.certification", "entityId", id,
            "status", "APPROVED", "requestId", requestId, "preparerId", "sponsor-a"));
        assertThat(row(id)).containsEntry("status", PublicationStatus.PENDING_REVIEW)
            .containsEntry("reject_reason", null);
    }

    @Test
    void moneyStaysWithinItsColumns() {
        String versionId = version(sponsorA(), "Money");
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "tiers",
            List.of(body("fromScore", 0, "toScore", 5, "reward", new java.math.BigDecimal("10000000000000")))),
            400))).containsExactly(PublishingCodes.TIER_REWARD_RANGE);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorA(), body("name", "x", "budgetCap",
            new java.math.BigDecimal("10000000000000")), 400))).containsExactly(PublishingCodes.BUDGET_RANGE);
        // The most winners at the highest reward is more than any spend: refused at submission, never stored.
        Map<String, Object> policy = complete(versionId, "Huge", clock.instant().plus(Duration.ofDays(1)),
            clock.instant().plus(Duration.ofDays(2)));
        policy.remove("budgetCap");
        policy.put("maxWinners", 999_999_999);
        policy.put("tiers", List.of(body("fromScore", 0, "toScore", 4, "reward", 0), body("fromScore", 5,
            "toScore", 5, "reward", new java.math.BigDecimal("1000000000000"))));
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", sponsorA(), policy);
        assertThat(problemCodes(saved)).containsExactly(PublishingCodes.SPEND_RANGE);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId",
            saved.get("publicationId")), 422))).containsExactly(PublishingCodes.SPEND_RANGE);
    }

    @Test
    void aCopiedDraftCanStillBeDeletedAndCopyNamesKeepWholeCharacters() {
        String versionId = version(sponsorA(), "Copied draft");
        String source = draft(sponsorA(), versionId, "x".repeat(92) + "\uD83D\uDE00");
        String copy = (String) run("QB_PUBLICATION_COPY", sponsorA(), body("publicationId", source))
            .get("publicationId");
        String name = (String) row(copy).get("name");
        assertThat(name).endsWith(" (copy)").doesNotContain("\uFFFD");
        assertThat(name.codePointCount(0, name.length())).isLessThanOrEqualTo(100);
        // 93 characters as people count them, and " (copy)": exactly the 100 a name may have, the emoji whole.
        assertThat(name).isEqualTo("x".repeat(92) + "\uD83D\uDE00 (copy)");
        run("QB_PUBLICATION_DELETE", sponsorA(), body("publicationId", source));
        assertThat(row(source)).containsEntry("is_deleted", true);
        assertThat(row(copy)).containsEntry("copied_from", source);
    }

    @Test
    void aDraftIsDeletedWithItsTiers() {
        String id = draft(sponsorA(), version(sponsorA(), "Deleted"), "Gone");
        run("QB_PUBLICATION_DELETE", sponsorA(), body("publicationId", id));
        assertThat(row(id)).containsEntry("is_deleted", true);
        assertThat(latest("qb_reward_tier_version", "tier_id", "publication_id = '" + id + "'"))
            .hasSize(3).allSatisfy(tier -> assertThat(tier).containsEntry("is_deleted", true));
        refused("QB_PUBLICATION_SAVE", sponsorA(), body("publicationId", id, "name", "Back"), 404);
    }
}
