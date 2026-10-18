package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorApprovals;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sponsor onboarding (M-01, M-03, P-09; docs/quizbuks/plans/Q2-accounts.md, D-Q2-3): applying goes to a content
 * administrator's review (the platform's approval panel); approval lets the sponsor publish and is final, rejection
 * sends it back with the reason and a task to submit again; the review switch approves at once. Only the onboarding's
 * own request, bound to its present content, moves it: a stale or forged result changes nothing.
 */
class SponsorOnboardingIT extends AccountItSupport {

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private static Map<String, Object> application(String name, String company) {
        return body("displayName", name, "company", company, "intro", "We make **quizzes**.", "acceptTerms", true);
    }

    private String newSponsor(String name) {
        return userWithRole(unique(name), QbRoles.SPONSOR);
    }

    private void autoApproveSponsors(boolean on) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.REVIEW_SPONSOR_AUTO, "value", String.valueOf(on)),
            "reason", "Sponsor review switch"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private void decideSponsor(Object requestId, String decision, String reason) {
        Map<String, Object> input = body("requestId", requestId, "decision", decision);
        if (reason != null) {
            input.put("reason", reason);
        }
        run("APPROVAL_DECIDE", sponsorReviewer(), input);
        deliverEvents();
    }

    private static Map<String, Object> request(Object requestId) {
        return query("SELECT status, subject, entity_id, preparer_id FROM (SELECT DISTINCT ON (request_id) * FROM "
            + "sys_approval_request_version ORDER BY request_id, version_no DESC) r WHERE request_id = ?::uuid",
            requestId).getFirst();
    }

    @Test
    void anApplicationIsReviewedAndApproved() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme"));
        assertThat(applied).containsEntry("userId", sara).containsEntry("reviewStatus", SponsorStatus.PENDING)
            .containsEntry("approval", "PENDING").containsEntry("revision", 1);
        Object requestId = applied.get("approvalRequestId");
        assertThat(onboardingOf(sara)).containsEntry("company", "Acme").containsEntry("approval_request_id",
            requestId).satisfies(row -> assertThat(row.get("terms_accepted_at")).isNotNull());
        assertThat(profileOf(sara)).containsEntry("display_name", "Sara").containsEntry("country_code", null);
        assertThat(request(requestId)).containsEntry("subject", SponsorApprovals.SUBJECT)
            .containsEntry("entity_id", applied.get("onboardingId")).containsEntry("preparer_id", sara);
        // The review is the content administrators' task, known by the company.
        assertThat(tasks(sponsorReviewer())).anySatisfy(task -> assertThat(task.toString()).contains("Acme"));
        // Not yet a sponsor: publishing is refused.
        assertThat(mailTo(sara)).isEmpty();

        decideSponsor(requestId, "APPROVE", null);
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.APPROVED)
            .satisfies(row -> assertThat(row.get("approved_at")).isNotNull());
        assertThat(mailTo(sara)).singleElement().satisfies(mail -> assertThat(mail)
            .containsEntry("template", "qb.sponsor.approved").containsEntry("category", "TRANSACTIONAL")
            .satisfies(m -> assertThat(m.get("params").toString()).contains("Acme")));
        // The administrators' queue.
        assertThat(items("qb.admin.sponsors", tokenOf("reader", QbRoles.ADMIN_CONTENT), Map.of("filters",
            List.of(Map.of("field", "reviewStatus", "op", "eq", "value", "APPROVED")))))
            .anySatisfy(row -> assertThat(row).containsEntry("userId", sara).containsEntry("company", "Acme")
                .containsEntry("contactName", "Sara").containsEntry("email", query(
                    "SELECT email FROM sec_user_version WHERE user_id = ?::uuid", sara).getFirst().get("email")));
    }

    /**
     * An application made before the terms were versioned (Q7b review L2): its request is bound to the content
     * without the terms' version. The same application under no version in effect hashes the same, so the pending
     * requests of before V12 are still approved.
     */
    @Test
    void anApplicationWithoutAVersionOfTheTermsIsBoundAsBeforeAndApproved() {
        String sara = newSponsor("legacy");
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", tokenOf(sara, QbRoles.SPONSOR),
            application("Sara", "Acme"));
        Object requestId = applied.get("approvalRequestId");
        Map<String, Object> onboarding = onboardingOf(sara);
        assertThat(onboarding).containsEntry("terms_version_no", null);
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("contactName", "Sara");
        before.put("company", "Acme");
        before.put("intro", "We make **quizzes**.");
        before.put("avatar", null);
        before.put("termsAcceptedAt", ((java.sql.Timestamp) onboarding.get("terms_accepted_at")).toInstant()
            .toString());
        assertThat(query("SELECT content_hash FROM sys_approval_request_version WHERE request_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", requestId).getFirst().get("content_hash"))
            .isEqualTo(com.jabiz.approval.ContentHash.of(before));

        decideSponsor(requestId, "APPROVE", null);
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.APPROVED);
    }

    @Test
    void aRejectedApplicationGetsTheReasonAndATaskToSubmitAgain() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        Object first = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme")).get("approvalRequestId");
        decideSponsor(first, "REJECT", "The logo is unclear.");

        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.REJECTED)
            .containsEntry("reject_reason", "The logo is unclear.");
        assertThat(rows(QbAccounts.MY_SPONSOR_DATASET, token)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reviewStatus", SponsorStatus.REJECTED).containsEntry("rejectReason",
                "The logo is unclear."));
        assertThat(mailTo(sara)).singleElement().satisfies(mail -> assertThat(mail)
            .containsEntry("template", "qb.sponsor.rejected")
            .satisfies(m -> assertThat(m.get("params").toString()).contains("The logo is unclear.")));
        assertThat(openTasksOf(sara)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("task_type", QbAccounts.RESUBMIT_TASK).containsEntry("source_key",
                QbAccounts.taskSource(sara)));

        // Changed and submitted again: a new request, the task is done, the reason cleared.
        Map<String, Object> again = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme Quizzes"));
        assertThat(again).containsEntry("reviewStatus", SponsorStatus.PENDING);
        assertThat(again.get("approvalRequestId")).isNotEqualTo(first);
        assertThat(openTasksOf(sara)).isEmpty();
        // Review 10: the earlier rejection's reason and time are cleared.
        assertThat(onboardingOf(sara)).containsEntry("reject_reason", null).containsEntry("rejected_at", null)
            .containsEntry("company", "Acme Quizzes");
        decideSponsor(again.get("approvalRequestId"), "APPROVE", null);
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.APPROVED);
    }

    @Test
    void applyingAgainWhilePendingReplacesTheRequestAndAStaleResultIsIgnored() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        Map<String, Object> first = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme"));
        Map<String, Object> second = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme Ltd"));
        assertThat(second.get("approvalRequestId")).isNotEqualTo(first.get("approvalRequestId"));
        assertThat(second.get("onboardingId")).isEqualTo(first.get("onboardingId"));
        assertThat(request(first.get("approvalRequestId"))).containsEntry("status", "SUPERSEDED");
        assertThat(tasks(sponsorReviewer())).noneSatisfy(task -> assertThat(task.toString())
            .contains(String.valueOf(first.get("approvalRequestId"))));

        // The old request's rejection, arriving late, changes nothing.
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", SponsorApprovals.SUBJECT, "entityId",
            first.get("onboardingId"), "status", "REJECTED", "requestId", first.get("approvalRequestId"),
            "preparerId", sara, "reason", "Old"));
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.PENDING)
            .containsEntry("approval_request_id", second.get("approvalRequestId"));
        decideSponsor(second.get("approvalRequestId"), "APPROVE", null);
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.APPROVED)
            .containsEntry("company", "Acme Ltd");
    }

    /**
     * Review 1: the name the reviewer approves is the onboarding's own ({@code contactName}); a person who is also a
     * taker and renames their profile in the app meanwhile does not unbind the request.
     */
    @Test
    void aProfileRenamedDuringTheReviewDoesNotUnbindIt() {
        String sara = newSponsor("sara");
        assignRole(sara, QbRoles.TAKER);
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", tokenOf(sara, QbRoles.SPONSOR),
            application("Sara", "Acme"));
        run("QB_PROFILE_SAVE", tokenOf(sara, QbRoles.TAKER), body("displayName", "Sara Lee", "countryCode", "JP",
            "preferredCurrency", "JPY"));
        decideSponsor(applied.get("approvalRequestId"), "APPROVE", null);
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.APPROVED)
            .containsEntry("contact_name", "Sara");
        assertThat(profileOf(sara)).containsEntry("display_name", "Sara Lee");
    }

    /** Review 6: the terms accepted once stay accepted, so the same application again is the same request. */
    @Test
    void theSameApplicationAgainIsTheSameRequest() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        Map<String, Object> first = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme"));
        Object accepted = onboardingOf(sara).get("terms_accepted_at");
        clock.set(clock.instant().plusSeconds(600));
        Map<String, Object> again = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme"));
        assertThat(again.get("approvalRequestId")).isEqualTo(first.get("approvalRequestId"));
        assertThat(onboardingOf(sara).get("terms_accepted_at")).isEqualTo(accepted);
        assertThat(request(first.get("approvalRequestId"))).containsEntry("status", "PENDING");
    }

    /** S3: a replaced avatar is released, once nothing refers to it. */
    @Test
    void aReplacedAvatarIsReleased() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        String first = (String) upload(QbAccounts.AVATAR_POLICY, FileSamples.jpeg(60, 60), "a.jpg", "image/jpeg",
            token).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        Map<String, Object> withFirst = application("Sara", "Acme");
        withFirst.put("avatar", first);
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", token, withFirst);
        decideSponsor(applied.get("approvalRequestId"), "APPROVE", null);
        String second = (String) upload(QbAccounts.AVATAR_POLICY, FileSamples.jpeg(60, 60), "b.jpg", "image/jpeg",
            token).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        run("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara", "company", "Acme", "avatar", second));
        assertThat(fileExists(first)).isFalse();
        assertThat(fileExists(second)).isTrue();
        // Saved again with the same avatar, nothing is released.
        run("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara", "company", "Acme Ltd", "avatar", second));
        assertThat(fileExists(second)).isTrue();
        // Removed: released too.
        run("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara", "company", "Acme Ltd"));
        assertThat(fileExists(second)).isFalse();
    }

    private static boolean fileExists(String fileId) {
        return !query("SELECT 1 FROM sys_file WHERE file_id = ?::uuid", fileId).isEmpty();
    }

    @Test
    void theReviewSwitchApprovesAtOnce() {
        autoApproveSponsors(true);
        try {
            String sara = newSponsor("sara");
            Map<String, Object> applied = run("QB_SPONSOR_APPLY", tokenOf(sara, QbRoles.SPONSOR),
                application("Sara", "Acme"));
            assertThat(applied).containsEntry("reviewStatus", SponsorStatus.APPROVED)
                .containsEntry("approval", "NOT_REQUIRED");
            assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.APPROVED);
            // Written pending, then approved: the lifecycle starts pending.
            assertThat(query("SELECT review_status FROM qb_sponsor_version WHERE user_id = ? ORDER BY version_no",
                sara)).extracting(row -> row.get("review_status")).containsExactly(SponsorStatus.PENDING,
                SponsorStatus.APPROVED);
            assertThat(query("SELECT outcome, facts FROM sys_approval_evaluation WHERE entity_id = ?",
                String.valueOf(applied.get("onboardingId")))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("outcome", "NOT_REQUIRED")
                .satisfies(r -> assertThat(r.get("facts").toString()).contains("\"autoApprove\":true")));
            assertThat(templatesTo(sara)).containsExactly("qb.sponsor.approved");
        } finally {
            autoApproveSponsors(false);
        }
    }

    @Test
    void applicationsAreRefusedForTheirReasons() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, body("displayName", "Sara", "company", "Acme"), 422)))
            .containsExactly(AccountCodes.SPONSOR_TERMS_REQUIRED);
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, body("displayName", "Sara", "company", "Acme",
            "acceptTerms", false), 422))).containsExactly(AccountCodes.SPONSOR_TERMS_REQUIRED);
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, application("Sara", "  "), 400)))
            .contains(AccountCodes.COMPANY_BLANK);
        // A file another person uploaded is not one's avatar; one's own is.
        String others = (String) upload(QbAccounts.AVATAR_POLICY, FileSamples.jpeg(60, 60), "b.jpg", "image/jpeg",
            sponsorB()).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        Map<String, Object> withOthers = application("Sara", "Acme");
        withOthers.put("avatar", others);
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, withOthers, 422)))
            .containsExactly(AccountCodes.FILE_NOT_OWN);
        String own = (String) upload(QbAccounts.AVATAR_POLICY, FileSamples.jpeg(60, 60), "s.jpg", "image/jpeg",
            token).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        Map<String, Object> withOwn = application("Sara", "Acme");
        withOwn.put("avatar", own);
        Object requestId = run("QB_SPONSOR_APPLY", token, withOwn).get("approvalRequestId");
        assertThat(onboardingOf(sara).get("avatar").toString()).isEqualTo(own);
        // Not approved: the profile is changed by applying again.
        assertThat(codes(refused("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara", "company", "X"),
            422))).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);
        // The sponsor cannot approve their own request (the platform's separation of duties).
        String selfReviewer = TestTokens.bearer(tokens, sara, QbPermissions.REVIEW_SPONSOR, "approval.decide");
        assertThat(refusalCode(refused("APPROVAL_DECIDE", selfReviewer, body("requestId", requestId,
            "decision", "APPROVE"), 422))).isEqualTo("APPROVAL_OWN_REQUEST");

        decideSponsor(requestId, "APPROVE", null);
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, application("Sara", "Acme"), 422)))
            .containsExactly(AccountCodes.SPONSOR_ALREADY_APPROVED);
    }

    @Test
    void anApprovedSponsorChangesTheirProfileWithoutReview() {
        String sara = newSponsor("sara");
        String token = tokenOf(sara, QbRoles.SPONSOR);
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", token, application("Sara", "Acme"));
        decideSponsor(applied.get("approvalRequestId"), "APPROVE", null);
        long requests = query("SELECT count(*) AS n FROM sys_approval_request_version").stream()
            .mapToLong(row -> ((Number) row.get("n")).longValue()).sum();

        Map<String, Object> saved = run("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara Lee",
            "company", "Acme Quizzes", "intro", "New intro", "baseRevision", 2));
        assertThat(saved).containsEntry("reviewStatus", SponsorStatus.APPROVED).containsEntry("revision", 3);
        assertThat(onboardingOf(sara)).containsEntry("company", "Acme Quizzes").containsEntry("intro", "New intro")
            .containsEntry("review_status", SponsorStatus.APPROVED);
        assertThat(profileOf(sara)).containsEntry("display_name", "Sara Lee");
        assertThat(query("SELECT count(*) AS n FROM sys_approval_request_version").getFirst().get("n"))
            .isEqualTo(requests);
        assertThat(codes(refused("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara", "company", "Acme",
            "baseRevision", 2), 422))).containsExactly(AccountCodes.SPONSOR_CHANGED);
        // The same again writes nothing.
        assertThat(run("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Sara Lee", "company", "Acme Quizzes",
            "intro", "New intro"))).containsEntry("revision", 3);
    }

    @Test
    void forgedOrForeignResultsChangeNothing() {
        String sara = newSponsor("sara");
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", tokenOf(sara, QbRoles.SPONSOR),
            application("Sara", "Acme"));
        Object onboarding = applied.get("onboardingId");
        Object requestId = applied.get("approvalRequestId");
        // Holding "*", the platform's first administrator cannot call the process (internal(), platform D41).
        refusedAsInternal("QB_SPONSOR_APPROVAL_RESULT", admin(), body("subject", SponsorApprovals.SUBJECT,
            "entityId", onboarding, "status", "APPROVED", "requestId", requestId, "preparerId", sara));
        // Run as the system, as the events run it, what a forged event claims is checked.
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", SponsorApprovals.SUBJECT, "entityId", onboarding,
            "status", "APPROVED", "requestId", requestId, "preparerId", sara));
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", SponsorApprovals.SUBJECT, "entityId", onboarding,
            "status", "REJECTED", "requestId", requestId, "preparerId", sara, "reason", "Forged"));
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", SponsorApprovals.SUBJECT, "entityId", onboarding,
            "status", "APPROVED", "requestId", "00000000-0000-7000-8000-000000000000", "preparerId", sara));
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", "qb.publication", "entityId", onboarding,
            "status", "APPROVED", "requestId", requestId, "preparerId", sara));
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", SponsorApprovals.SUBJECT, "entityId",
            "not-a-key", "status", "APPROVED", "requestId", requestId));
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.PENDING)
            .containsEntry("reject_reason", null).containsEntry("revision", java.math.BigDecimal.ONE);
        assertThat(mailTo(sara)).isEmpty();
        // Nor a content administrator, who holds the review permission.
        refusedAsInternal("QB_SPONSOR_APPROVAL_RESULT", tokenOf("content-admin", QbRoles.ADMIN_CONTENT),
            body("subject", SponsorApprovals.SUBJECT, "entityId", onboarding, "status", "APPROVED", "requestId",
                requestId));
        // Once really rejected, a forged reason does not stand: the reviewer's decision gives it.
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", requestId, "decision", "REJECT", "reason",
            "Real reason"));
        runAsSystem("QB_SPONSOR_APPROVAL_RESULT", body("subject", SponsorApprovals.SUBJECT, "entityId", onboarding,
            "status", "REJECTED", "requestId", requestId, "preparerId", sara, "reason", "Forged reason"));
        assertThat(onboardingOf(sara)).containsEntry("review_status", SponsorStatus.REJECTED)
            .containsEntry("reject_reason", "Real reason");
        // The event itself, delivered after, finds it rejected already: nothing more.
        deliverEvents();
        assertThat(openTasksOf(sara)).hasSize(1);
        assertThat(templatesTo(sara)).containsExactly("qb.sponsor.rejected");
    }

    @Test
    void onlyVerifiedSponsorsApplyAndEachReadsOnlyTheirOwn() {
        String sara = newSponsor("sara");
        assertThat(refused("QB_SPONSOR_APPLY", unverifiedTokenOf(sara, QbRoles.SPONSOR),
            application("Sara", "Acme"), 403).toString()).contains("EMAIL_NOT_VERIFIED");
        String tom = userWithRole(unique("tom"), QbRoles.TAKER);
        refused("QB_SPONSOR_APPLY", tokenOf(tom, QbRoles.TAKER), application("Tom", "Tom Inc"), 403);
        assertThat(onboardingOf(sara)).isNull();
        assertThat(onboardingOf(tom)).isNull();

        run("QB_SPONSOR_APPLY", tokenOf(sara, QbRoles.SPONSOR), application("Sara", "Acme"));
        assertThat(rows(QbAccounts.MY_SPONSOR_DATASET, tokenOf(sara, QbRoles.SPONSOR))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", sara));
        assertThat(rows(QbAccounts.MY_SPONSOR_DATASET, sponsorA())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", SPONSOR_A));
        queryDataset(SPONSORS, tokenOf(sara, QbRoles.SPONSOR)).expectStatus().isForbidden();
        assertThat(rows(SPONSORS, tokenOf("reader", QbRoles.ADMIN_CONTENT))).extracting(row -> row.get("userId"))
            .contains(sara, SPONSOR_A, SPONSOR_B);
    }
}
