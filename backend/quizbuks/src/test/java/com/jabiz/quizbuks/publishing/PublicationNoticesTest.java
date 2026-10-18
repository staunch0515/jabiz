package com.jabiz.quizbuks.publishing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which moves of a publication its sponsor is mailed about (Q7a, D-Q7-2), and which not. */
class PublicationNoticesTest {

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    void aReviewsOutcomeIsANoticeOnlyWhenAReviewerDecided() {
        assertThat(PublicationNotices.kindOf("PENDING_REVIEW", values("status", "SCHEDULED")))
            .isEqualTo(PublicationNotices.APPROVED);
        assertThat(PublicationNotices.kindOf("PENDING_REVIEW", values("status", "LIVE")))
            .isEqualTo(PublicationNotices.APPROVED);
        assertThat(PublicationNotices.kindOf("PENDING_REVIEW", values("status", "DRAFT", "rejectReason", "Too high")))
            .isEqualTo(PublicationNotices.REJECTED);
        // Withdrawn by the sponsor, out of time in review, or a ban during the review: no reviewer's reason.
        assertThat(PublicationNotices.kindOf("PENDING_REVIEW", values("status", "DRAFT"))).isNull();
        assertThat(PublicationNotices.kindOf("PENDING_REVIEW", values("status", "DRAFT", "rejectCode",
            "QB_PUBLICATION_EXPIRED_IN_REVIEW", "rejectReason", null))).isNull();
        assertThat(PublicationNotices.kindOf("PENDING_REVIEW", values("status", "DRAFT", "rejectReason", " ")))
            .isNull();
        // Straight on at submission (no review asked): the sponsor sees it on their page.
        assertThat(PublicationNotices.kindOf("DRAFT", values("status", "LIVE"))).isNull();
    }

    @Test
    void anAdministratorsPauseOrEndIsATakedownButABansPauseIsNot() {
        assertThat(PublicationNotices.kindOf("LIVE", values("status", "PAUSED", "pausedBy", "ADMIN",
            "adminReason", "Misleading"))).isEqualTo(PublicationNotices.TAKEN_DOWN);
        // A publication its sponsor had paused stays paused, now as the administrator's.
        assertThat(PublicationNotices.kindOf("PAUSED", values("pausedBy", "ADMIN", "adminReason", "Misleading")))
            .isEqualTo(PublicationNotices.TAKEN_DOWN);
        assertThat(PublicationNotices.kindOf("LIVE", values("status", "ENDED", "endReason", "ADMIN",
            "adminReason", "Fraud"))).isEqualTo(PublicationNotices.TAKEN_DOWN);
        assertThat(PublicationNotices.kindOf("LIVE", values("status", "PAUSED", "pausedBy", "ADMIN",
            "adminReason", PublicationAdmin.ACCOUNT_SUSPENDED))).isNull();
        // The sponsor's own pause and resume, and an administrator's resume to live.
        assertThat(PublicationNotices.kindOf("LIVE", values("status", "PAUSED", "pausedBy", "SPONSOR"))).isNull();
        assertThat(PublicationNotices.kindOf("PAUSED", values("status", "LIVE", "pausedBy", null,
            "adminReason", "Checked"))).isNull();
    }

    @Test
    void fundsShortAndTheEndsByTimeWinnersOrBudget() {
        assertThat(PublicationNotices.kindOf("LIVE", values("status", "FUNDS_SHORT")))
            .isEqualTo(PublicationNotices.FUNDS_SHORT);
        assertThat(PublicationNotices.kindOf("FUNDS_SHORT", values("status", "LIVE"))).isNull();
        for (String reason : new String[] {"EXPIRED", "QUOTA", "BUDGET"}) {
            assertThat(PublicationNotices.kindOf("LIVE", values("status", "ENDED", "endReason", reason)))
                .as(reason).isEqualTo(PublicationNotices.ENDED);
        }
        assertThat(PublicationNotices.kindOf("SCHEDULED", values("status", "LIVE"))).isNull();
        assertThat(PublicationNotices.kindOf("LIVE", values())).isNull();
    }
}
