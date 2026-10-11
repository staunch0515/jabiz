package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only an approved sponsor who is not banned publishes (M-31 "there is a sponsor"; docs/quizbuks/plans/Q2-accounts.md,
 * D-Q2-6): submitting is refused otherwise, with the submission's other problems, and saving a draft lists the same
 * problems for the confirmation page. A ban takes effect at once here, though a token issued before it still works.
 */
class SponsorChecksIT extends AccountItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
        assertAccountsOnlyInserted();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> p.get("code")).toList();
    }

    /** A complete draft of the sponsor's; returns the save's output. */
    private Map<String, Object> completeDraft(String token) {
        java.time.Instant now = clock.instant();
        return run("QB_PUBLICATION_SAVE", token, complete(version(token, "Checks"), "Checked",
            now.plus(java.time.Duration.ofDays(1)), now.plus(java.time.Duration.ofDays(8))));
    }

    @Test
    void withoutAnApprovedOnboardingThereIsNoPublishing() {
        // Never applied.
        String none = sponsor("sponsor-none");
        Map<String, Object> saved = completeDraft(none);
        assertThat(problemCodes(saved)).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", none, body("publicationId", saved.get("publicationId")),
            422))).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);

        // Applied, in review.
        String pending = sponsor("sponsor-pending");
        Object requestId = apply(pending, "Pending Co").get("approvalRequestId");
        Map<String, Object> draft = completeDraft(pending);
        assertThat(problemCodes(draft)).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", pending, body("publicationId",
            draft.get("publicationId")), 422))).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);

        // Sent back.
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", requestId, "decision", "REJECT", "reason",
            "Incomplete"));
        deliverEvents();
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", pending, body("publicationId",
            draft.get("publicationId")), 422))).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);

        // Approved after applying again: submitting goes through, the problem is gone.
        Object again = apply(pending, "Pending Co").get("approvalRequestId");
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", again, "decision", "APPROVE"));
        deliverEvents();
        assertThat(problemCodes(run("QB_PUBLICATION_COPY", pending, body("publicationId",
            draft.get("publicationId"))))).isEmpty();
        assertThat(submit(pending, (String) draft.get("publicationId")))
            .containsEntry("status", PublicationStatus.PENDING_REVIEW);
    }

    @Test
    void aBannedSponsorCannotSubmitWithTheTokenTheyStillHold() {
        String sara = userWithRole(unique("sara"), QbRoles.SPONSOR);
        String token = sponsor(sara);
        onboard(sara);
        Map<String, Object> draft = completeDraft(token);
        assertThat(problemCodes(draft)).isEmpty();

        String admin = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(admin, QbRoles.ADMIN_SUPER), body("userId", sara, "reason", "Fraud"));
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft.get("publicationId")),
            422))).containsExactly(AccountCodes.USER_BANNED);
        assertThat(problemCodes(run("QB_PUBLICATION_COPY", token, body("publicationId",
            draft.get("publicationId"))))).containsExactly(AccountCodes.USER_BANNED);

        // Lifted: submitting goes through.
        run("QB_USER_UNBAN", tokenOf(admin, QbRoles.ADMIN_SUPER), body("userId", sara));
        assertThat(submit(token, (String) draft.get("publicationId")))
            .containsEntry("status", PublicationStatus.PENDING_REVIEW);
    }
}
