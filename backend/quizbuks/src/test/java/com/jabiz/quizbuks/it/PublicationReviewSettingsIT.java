package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.SponsorApprovals;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether publications are reviewed is decided by data that changes only with four eyes (P-09; docs/quizbuks/plans/
 * Q4-publishing.md, D-Q4-4): the controlled parameter {@code qb.review.publication.auto} and the approval rule
 * {@code qb.publication.review}, both proposed by {@code QB_SETUP}. Until both are published a submission is refused,
 * never let through unreviewed; with the switch on, a publication goes on at once and the evaluation is kept.
 */
class PublicationReviewSettingsIT extends PublicationItSupport {

    @Override
    boolean installs() {
        return false;
    }

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    @Test
    void reviewIsRefusedUntilConfiguredAndSkippedOnlyByTheSwitch() {
        Map<String, Object> installed = setup();
        @SuppressWarnings("unchecked")
        List<Object> proposals = (List<Object>) installed.get("proposals");
        assertThat(installed.get("rulesProposed")).isEqualTo(List.of(PublicationApprovals.RULE,
            SponsorApprovals.RULE, com.jabiz.quizbuks.wallet.QbPayouts.REVIEW_RULE,
            com.jabiz.quizbuks.wallet.BankTopUps.REVIEW_RULE, com.jabiz.quizbuks.setup.QbSodRules.CONTROL.code(), com.jabiz.quizbuks.setup.QbSodRules.SECURITY.code()));
        String id = draft(sponsorA(), version(sponsorA(), "Settings"), "Configured?");
        // The separation of duties (Q10b) comes last and is left unpublished: the rules are counted before it.
        int count = proposals.size() - com.jabiz.quizbuks.setup.QbSodRules.ALL.size();

        // Nothing published yet: the switch has no value, which refuses.
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId", id), 422)))
            .containsExactly("PARAM_NOT_FOUND");

        // The parameters and the sponsors' rule published (the sponsor is onboarded), not the publications' rule: no
        // review would be asked for, which is refused too.
        publishProposals(Map.of("proposals", proposals.subList(0, count - 4)));
        publishProposals(Map.of("proposals", proposals.subList(count - 3, count - 2)));
        onboard(SPONSOR_A);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorA(), body("publicationId", id), 422)))
            .containsExactly(PublishingCodes.REVIEW_NOT_CONFIGURED);

        // With the rule, a review is asked for.
        publishProposals(Map.of("proposals", proposals.subList(count - 4, count - 3)));
        assertThat(submit(sponsorA(), id)).containsEntry("status", PublicationStatus.PENDING_REVIEW);
        run("QB_PUBLICATION_WITHDRAW", sponsorA(), body("publicationId", id));

        // The switch on (four eyes): the publication goes on at once, and the evaluation records the switch.
        autoApprove(true);
        assertThat(submit(sponsorA(), id)).containsEntry("status", PublicationStatus.SCHEDULED)
            .containsEntry("approval", "NOT_REQUIRED");
        assertThat(query("SELECT outcome, facts FROM sys_approval_evaluation WHERE entity_id = ? "
            + "ORDER BY process_seq_id", id)).extracting(row -> row.get("outcome"))
            .containsExactly("PENDING", "NOT_REQUIRED");
        assertThat(query("SELECT facts FROM sys_approval_evaluation WHERE entity_id = ? AND outcome = "
            + "'NOT_REQUIRED'", id).getFirst().get("facts").toString()).contains("\"autoApprove\":true");
        assertThat(query("SELECT 1 FROM qb_publication_counter WHERE publication_id = ?::uuid", id)).hasSize(1);
        assertThat(query("SELECT 1 FROM qb_publication_start_counter WHERE publication_id = ?::uuid", id)).hasSize(1);

        // Directly, nobody turns the switch: it is controlled.
        assertThat(refused("PARAM_SET", admin(), body("key", QbParams.REVIEW_PUBLICATION_AUTO, "value", "false"),
            422).toString()).contains("PARAM_CONTROLLED");
    }
}
