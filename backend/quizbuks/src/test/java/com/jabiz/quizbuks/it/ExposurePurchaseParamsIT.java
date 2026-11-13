package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeCodes;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8: while {@code qb.exposure.min-purchase} is not published, a submission that would buy exposures is refused like
 * one without the tax rate: 422 {@code QB_FEE_PARAMS}, never read as a least of 1 (a class of its own: it withdraws a
 * parameter of the schema).
 */
class ExposurePurchaseParamsIT extends ExposureFeeItSupport {

    @Test
    void withoutTheLeastPurchaseTheSubmissionIsRefusedAndWritesNothing() {
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-params-provider"));
        String sponsor = reviewedSponsor("fm8-params");
        grant(sponsor, 5_000, clock.instant().plus(java.time.Duration.ofDays(90)));
        String publicationId = draft(sponsor, "Params", sharedVideoId, 105L);
        execute("INSERT INTO sys_param_version (param_id, version_no, effect_start_time, created_time, process_seq_id, "
            + "is_deleted, param_key, value_kind, param_value, description) SELECT param_id, version_no + 1, ?, ?, "
            + "process_seq_id, true, param_key, value_kind, param_value, description FROM sys_param_version "
            + "WHERE param_key = 'qb.exposure.min-purchase' ORDER BY version_no DESC LIMIT 1",
            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).contains(FeeCodes.FEE_PARAMS);
        assertThat(status(publicationId)).isEqualTo("DRAFT");
        assertThat(prepayments(publicationId)).isEmpty();
    }
}
