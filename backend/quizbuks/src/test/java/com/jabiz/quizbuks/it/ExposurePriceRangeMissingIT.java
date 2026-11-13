package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposurePrices;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8: while a bound of the headquarters' range of the exposure prices is not published, no price is saved: 422
 * {@code QB_EXPOSURE_RANGE_MISSING}, never read as a range from 0 (a class of its own: it withdraws a bound of the
 * schema's parameters).
 */
class ExposurePriceRangeMissingIT extends PrepaymentItSupport {

    @Test
    void withoutAPublishedBoundNoPriceIsSaved() {
        String hq = TestTokens.bearer(tokens, "fm8-ops-range", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
        String finance = TestTokens.bearer(tokens, "fm8-finance-range", QbPermissions.FEE_MANAGE);
        String operator = newOperator(hq, unique("fm8 range missing"));
        // The bound is withdrawn the way the platform withdraws a parameter: a version marked deleted.
        execute("INSERT INTO sys_param_version (param_id, version_no, effect_start_time, created_time, process_seq_id, "
            + "is_deleted, param_key, value_kind, param_value, description) SELECT param_id, version_no + 1, ?, ?, "
            + "process_seq_id, true, param_key, value_kind, param_value, description FROM sys_param_version "
            + "WHERE param_key = 'qb.exposure.unit.max' ORDER BY version_no DESC LIMIT 1",
            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        assertThat(codes(refused(ExposurePrices.SET, finance, body("operatorId", operator, "unit", "3",
            "reason", "x"), 422))).containsExactly(FeeCodes.EXPOSURE_RANGE_MISSING);
        assertThat(query("SELECT 1 FROM qb_exposure_price WHERE operator_id = ?", operator)).isEmpty();
    }
}
