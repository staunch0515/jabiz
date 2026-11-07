package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S1: a reassignment moves a Sponsor's open reviews in one call of the platform's {@code APPROVAL_SCOPE_MOVE}, so
 * the most it moves is the platform's single-call limit ({@code jabiz.approval.scope-move.max-items}, here 1); more is
 * refused with the limit, never split.
 */
@TestPropertySource(properties = "jabiz.approval.scope-move.max-items=1")
class OperatorMoveLimitIT extends PlayItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "fs1d-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            "approval.scope.move");
    }

    private void submitNew(String sponsorId, String name) {
        String token = sponsorToken(sponsorId);
        String versionId = version(token, name + " quiz");
        Instant now = clock.instant();
        String publication = (String) run("QB_PUBLICATION_SAVE", token, complete(versionId, name, now,
            now.plus(Duration.ofDays(7)))).get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publication)))
            .containsEntry("status", "PENDING_REVIEW");
    }

    @Test
    void aSponsorWithMoreOpenReviewsThanOneMoveTakesIsNotMoved() {
        String sponsor = newSponsor(unique("s-fs1d"));
        credit(sponsor, 10_000);
        NewOperator kyushu = newOperatorWithLead(hq(), unique("Kyushu"), null);
        autoApprove(false);
        try {
            submitNew(sponsor, "FS1d one");
            // One open review: within the limit, it moves.
            assertThat(run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", kyushu.operatorId(),
                "reason", "Move"))).containsEntry("changed", true);
            submitNew(sponsor, "FS1d two");
            NewOperator kanto = newOperatorWithLead(hq(), unique("Kanto"), null);
            // Two: more than one call takes; refused with the limit, and nothing moved.
            Map<String, Object> refusal = refused(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor,
                "operatorId", kanto.operatorId(), "reason", "Move"), 422);
            assertThat(codes(refusal)).containsExactly(OperatorCodes.REVIEW_PENDING);
            assertThat(String.valueOf(refusal)).contains("1");
            assertThat(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", sponsor).getFirst())
                .containsEntry("operator_id", kyushu.operatorId());
        } finally {
            autoApprove(true);
        }
    }
}
