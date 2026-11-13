package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorWithdrawals;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 (requester 2026-11-13, question 6): the platform operator's account is not withdrawn from. Its lead's request
 * is refused with {@code QB_OPERATOR_WITHDRAWAL_PLATFORM} alone and writes nothing; another operator's is not; the
 * platform operator is the default one ({@code defaultForNew}) when the request is made, so the refusal follows the
 * default when it moves. Transfers to the direct Sponsor are not touched ({@code OperatorTransferIT}).
 */
class OperatorPlatformWithdrawalIT extends PlayItSupport {

    String leadOf(String operatorId) {
        return String.valueOf(query("SELECT user_id FROM qb_operator_member WHERE operator_id = ? AND lead "
            + "AND removed_at IS NULL", operatorId).getFirst().get("user_id"));
    }

    long withdrawals(String operatorId) {
        return ((Number) query("SELECT count(*) AS n FROM qb_operator_withdrawal WHERE operator_id = ?", operatorId)
            .getFirst().get("n")).longValue();
    }

    /** What the lead's request of 3 is refused for (it is refused: no bank account, at least). */
    java.util.List<Object> refusalOf(String operatorId) {
        return codes(refused(OperatorWithdrawals.REQUEST, memberSession(leadOf(operatorId), operatorId),
            body("amount", 3), 422));
    }

    @Test
    void thePlatformOperatorsLeadDoesNotWithdrawAndTheRefusalFollowsTheDefault() {
        String platform = platformOperator();
        assertThat(refusalOf(platform)).containsExactly(OperatorCodes.WITHDRAWAL_PLATFORM);
        assertThat(withdrawals(platform)).isZero();

        // Another operator is refused for other reasons only (here: no bank account yet).
        NewOperator other = newOperatorWithLead(operatorsAdmin(), unique("Not the platform"), null);
        assertThat(refusalOf(other.operatorId())).contains(OperatorCodes.WITHDRAWAL_NO_ACCOUNT)
            .doesNotContain(OperatorCodes.WITHDRAWAL_PLATFORM);

        // The default moves to a new operator: it is refused now, and the former one no longer for this.
        String lead = verifiedUser(unique("lead-newdefault"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", unique("New default"));
        input.put("contactName", "Owner");
        input.put("contactEmail", "ops@newdefault.example");
        input.put("leadUserId", lead);
        input.put("defaultForNew", true);
        String newDefault = (String) run(OperatorProcesses.SAVE, operatorsAdmin(), input).get("operatorId");
        assertThat(platformOperator()).isEqualTo(newDefault);
        assertThat(refusalOf(newDefault)).containsExactly(OperatorCodes.WITHDRAWAL_PLATFORM);
        assertThat(refusalOf(platform)).doesNotContain(OperatorCodes.WITHDRAWAL_PLATFORM);
        assertThat(withdrawals(newDefault)).isZero();
    }
}
